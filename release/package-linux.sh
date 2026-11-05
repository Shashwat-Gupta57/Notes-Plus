#!/usr/bin/env bash
# Builds the Linux release, inside WSL (or any x86-64 Linux), into dist/<version>/linux/:
#   notes-plus_<v>_amd64.deb          Debian / Ubuntu / Mint: double-click, or sudo apt install ./…deb
#   Notes-Plus-<v>-x86_64.AppImage    any distribution: chmod +x, then run it
#   Notes-Plus-Linux-<v>.tar.gz       the app folder itself: unpack anywhere and run Notes+/bin/Notes+
# and prints the "linux" entry for version.json. Builds only: publishing is a separate step.
#
#   wsl -d Ubuntu-24.04 -- bash /mnt/d/User/Data/projects/Notes-Plus-Desktop/release/package-linux.sh
#
# Needs: openjdk-21-jdk, maven, fakeroot, binutils, dpkg-dev, file (apt). jpackage only builds for
# the system it runs on, which is why this isn't part of package.sh.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$HOME/np-build"
VERSION=$(grep -m1 -oP '(?<=<version>)[0-9][^<]*' "$SRC/pom.xml")
OUT="$SRC/dist/$VERSION/linux"
APPIMAGETOOL_VERSION=1.9.0

if [ ! -f "$SRC/src/main/resources/org/flexstudios/notes/plus/oauth-client.json" ]; then
  echo "WARNING: no oauth-client.json in resources: Google sign-in and Drive won't work in this build." >&2
fi

echo "== Copy ($VERSION) to $WORK"
rm -rf "$WORK"
mkdir -p "$WORK"
( cd "$SRC" && tar --exclude=./target --exclude=./dist --exclude=./.git --exclude=./logs -cf - . ) | ( cd "$WORK" && tar -xf - )

echo "== Tests and jar (Linux JavaFX)"
cd "$WORK"
mvn -q -B clean package -DskipTests

rm -rf target/pkg target/linux "$OUT"
mkdir -p target/pkg target/linux "$OUT"
cp "target/Notes-Plus-Desktop-$VERSION-all.jar" target/pkg/
cp src/main/resources/org/flexstudios/notes/plus/icon.png target/linux/notes-plus.png

echo "== App image"
jpackage --type app-image \
  --name "Notes+" \
  --app-version "$VERSION" \
  --vendor "Flex Studios" \
  --description "Notes+ for Linux" \
  --copyright "Shashwat Gupta" \
  --icon target/linux/notes-plus.png \
  --input target/pkg \
  --main-jar "Notes-Plus-Desktop-$VERSION-all.jar" \
  --main-class org.flexstudios.notes.plus.Main \
  --add-modules java.base,java.desktop,java.sql,java.net.http,java.logging,java.naming,java.xml,java.management,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.httpserver \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6" \
  --java-options "-Xmx1g -Dfile.encoding=UTF-8" \
  --dest target/linux

echo "== .deb"
jpackage --type deb \
  --app-image "target/linux/Notes+" \
  --name "Notes+" \
  --app-version "$VERSION" \
  --vendor "Flex Studios" \
  --description "Notes+: notes, lists, journals, mind maps, sketches and recordings, synced with your phone." \
  --copyright "Shashwat Gupta" \
  --icon target/linux/notes-plus.png \
  --linux-package-name notes-plus \
  --linux-deb-maintainer "guptashashwat57@gmail.com" \
  --linux-menu-group "Office;Utility" \
  --linux-app-category "editors" \
  --linux-shortcut \
  --dest target/linux
DEB=$(ls target/linux/*.deb)
# jpackage writes this machine's exact package names (Ubuntu 24.04's "t64" ones), which older
# Ubuntu and Debian don't have, and leaves out GTK, which JavaFX needs: the list is rewritten with
# either name allowed, so it installs on Ubuntu 22.04+, Debian 12+ and Mint.
FIX=$(mktemp -d)
dpkg-deb -R "$DEB" "$FIX"
sed -i -E 's/^Depends: .*/Depends: libasound2t64 | libasound2, libc6, libfreetype6, libglib2.0-0t64 | libglib2.0-0, libgtk-3-0t64 | libgtk-3-0, libgl1, libpng16-16t64 | libpng16-16, libx11-6, libxext6, libxi6, libxrender1, libxtst6, xdg-utils, zlib1g/' "$FIX/DEBIAN/control"
# The menu entry ships in the package itself. jpackage's scripts add it with xdg-desktop-menu, which
# fails ("no writable system menu directory") wherever /usr/share/applications doesn't exist yet,
# and a failing script leaves the whole package half-installed.
mkdir -p "$FIX/usr/share/applications"
sed -e 's/^Categories=.*/Categories=Office;Utility;/' -e '/^MimeType=$/d' \
  "$FIX/opt/notes-plus/lib/notes-plus-Notes+.desktop" > "$FIX/usr/share/applications/notes-plus.desktop"
for s in postinst postrm; do
  printf '#!/bin/sh\nset -e\nif command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database -q /usr/share/applications || true; fi\nexit 0\n' > "$FIX/DEBIAN/$s"
  chmod 755 "$FIX/DEBIAN/$s"
done
printf '#!/bin/sh\nexit 0\n' > "$FIX/DEBIAN/prerm"
chmod 755 "$FIX/DEBIAN/prerm"
fakeroot dpkg-deb --build "$FIX" "$OUT/notes-plus_${VERSION}_amd64.deb" >/dev/null
rm -rf "$FIX"

echo "== .tar.gz"
tar -C target/linux -czf "$OUT/Notes-Plus-Linux-$VERSION.tar.gz" "Notes+"

echo "== AppImage"
APPDIR=target/linux/Notes+.AppDir
mkdir -p "$APPDIR/usr"
cp -a "target/linux/Notes+/." "$APPDIR/usr/"
cp target/linux/notes-plus.png "$APPDIR/notes-plus.png"
cat > "$APPDIR/notes-plus.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=Notes+
Comment=Notes, lists, journals, mind maps, sketches and recordings
Exec=Notes+
Icon=notes-plus
Categories=Office;Utility;
Terminal=false
DESK
cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/Notes+" "$@"
RUN
chmod +x "$APPDIR/AppRun"
TOOL="$HOME/appimagetool-$APPIMAGETOOL_VERSION"
if [ ! -x "$TOOL" ]; then
  curl -fsSL -o "$TOOL" "https://github.com/AppImage/appimagetool/releases/download/$APPIMAGETOOL_VERSION/appimagetool-x86_64.AppImage"
  chmod +x "$TOOL"
fi
# The AppImage runtime, fetched once and kept (the tool would download it on every run, and GitHub
# sometimes answers 504).
RUNTIME="$HOME/appimage-runtime-x86_64"
if [ ! -s "$RUNTIME" ]; then
  if ! curl -fsSL --retry 3 --retry-delay 5 --retry-all-errors -o "$RUNTIME" \
      "https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64"; then
    # Offline or refused: appimagetool is an AppImage itself, so its first bytes (up to its
    # squashfs) are that same runtime.
    OFFSET=$("$TOOL" --appimage-offset)
    head -c "$OFFSET" "$TOOL" > "$RUNTIME"
  fi
fi
# No FUSE in WSL: the tool runs extracted.
APPIMAGE_EXTRACT_AND_RUN=1 ARCH=x86_64 "$TOOL" -n --runtime-file "$RUNTIME" "$APPDIR" "$OUT/Notes-Plus-$VERSION-x86_64.AppImage" >/dev/null

echo
echo "Built in $OUT:"
ls -l "$OUT"
cd "$OUT"
echo
echo 'version.json "linux" entry:'
cat <<JSON
  "linux": {
    "versionName": "$VERSION",
    "url": "https://notes.talken.in/download.html#linux",
    "deb": "https://releases.talken.in/linux/notes-plus_${VERSION}_amd64.deb",
    "debSha256": "$(sha256sum "notes-plus_${VERSION}_amd64.deb" | cut -d' ' -f1)",
    "appImage": "https://releases.talken.in/linux/Notes-Plus-$VERSION-x86_64.AppImage",
    "appImageSha256": "$(sha256sum "Notes-Plus-$VERSION-x86_64.AppImage" | cut -d' ' -f1)",
    "tarGz": "https://releases.talken.in/linux/Notes-Plus-Linux-$VERSION.tar.gz",
    "tarGzSha256": "$(sha256sum "Notes-Plus-Linux-$VERSION.tar.gz" | cut -d' ' -f1)",
    "size": "$(du -m "notes-plus_${VERSION}_amd64.deb" | cut -f1) MB"
  }
JSON
