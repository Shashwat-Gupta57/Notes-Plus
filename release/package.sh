#!/usr/bin/env bash
# Builds the Windows release, into dist/<version>/:
#   Notes+/                          the app image: Notes+.exe, Notes+ Setup.exe, unins.exe (one runtime)
#   Notes-Plus-Windows-<v>.zip       that folder zipped: what Setup's Update downloads
#   Notes-Plus-Setup-<v>.exe         the installer people download (IExpress, built into Windows):
#                                    unpacks the zip to %TEMP% and starts Notes+ Setup
# and prints the "desktop" entry for version.json. Builds only: publishing is a separate,
# deliberate step (RELEASING.md).
#
#   release/package.sh            # version from pom.xml
set -euo pipefail
cd "$(dirname "$0")/.."

JDK="${JAVA_HOME:-/d/Program Files/Java/JDK/jdk-21.0.8+9}"
MVN="${MVN:-/d/Program Files/Maven/apache-maven-3.9.11/bin/mvn}"
VERSION=$(python -I -c "import re;print(re.search(r'<version>([0-9][^<]*)</version>', open('pom.xml').read()).group(1))")
ZIP="Notes-Plus-Windows-$VERSION.zip"
# Each version builds into its own folder, so a copy still running from an older one never blocks it.
OUT="dist/$VERSION"

if [ ! -f src/main/resources/org/flexstudios/notes/plus/oauth-client.json ]; then
  echo "WARNING: no oauth-client.json in resources: Google sign-in and Drive won't work in this build." >&2
fi

echo "== Tests and jar ($VERSION)"
JAVA_HOME="$JDK" "$MVN" -q clean package

rm -rf target/pkg "$OUT"
mkdir -p target/pkg "$OUT"
cp "target/Notes-Plus-Desktop-$VERSION-all.jar" target/pkg/

echo "== App image"
# Modules the app uses: UI and images (desktop), SQLite (sql), Firebase and Drive (net.http,
# TLS), JavaFX's needs (unsupported, jsobject-free), logging and locales.
"$JDK/bin/jpackage" --type app-image \
  --name "Notes+" \
  --app-version "$VERSION" \
  --vendor "Flex Studios" \
  --description "Notes+ for Windows" \
  --copyright "Shashwat Gupta" \
  --icon release/notes.ico \
  --input target/pkg \
  --main-jar "Notes-Plus-Desktop-$VERSION-all.jar" \
  --main-class org.flexstudios.notes.plus.Main \
  --add-modules java.base,java.desktop,java.sql,java.net.http,java.logging,java.naming,java.xml,java.management,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6" \
  --java-options "-Xmx1g -Dfile.encoding=UTF-8" \
  --add-launcher "Notes+ Setup=release/setup.properties" \
  --add-launcher "unins=release/unins.properties" \
  --dest "$OUT"

echo "== Zip"
python -I - "$ZIP" "$OUT" <<'PY'
import os, sys, zipfile
out = os.path.join(sys.argv[2], sys.argv[1])
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, _, files in os.walk(os.path.join(sys.argv[2], 'Notes+')):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, sys.argv[2]))
PY

echo "== Installer"
EXE="Notes-Plus-Setup-$VERSION.exe"
STAGE="$OUT/installer"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$OUT/$ZIP" "$STAGE/payload.zip"
cp release/boot.ps1 "$STAGE/boot.ps1"
WSTAGE=$(cygpath -w "$(cd "$STAGE" && pwd)")
WEXE=$(cygpath -w "$(cd "$OUT" && pwd)")\\$EXE
cat > "$STAGE/setup.sed" <<SED
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=1
HideExtractAnimation=0
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=%InstallPrompt%
DisplayLicense=%DisplayLicense%
FinishMessage=%FinishMessage%
TargetName=%TargetName%
FriendlyName=%FriendlyName%
AppLaunched=%AppLaunched%
PostInstallCmd=%PostInstallCmd%
AdminQuietInstCmd=%AdminQuietInstCmd%
UserQuietInstCmd=%UserQuietInstCmd%
SourceFiles=SourceFiles
[Strings]
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$WEXE
FriendlyName=Notes+ Setup
AppLaunched=powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File boot.ps1
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
FILE0="payload.zip"
FILE1="boot.ps1"
[SourceFiles]
SourceFiles0=$WSTAGE\\
[SourceFiles0]
%FILE0%=
%FILE1%=
SED
rm -f "$OUT/$EXE"
iexpress.exe //N //Q "$(cygpath -w "$STAGE/setup.sed")"
for i in $(seq 1 120); do [ -f "$OUT/$EXE" ] && break; sleep 1; done
[ -f "$OUT/$EXE" ] || { echo "IExpress didn't produce $EXE" >&2; exit 1; }
rm -rf "$STAGE"

ZSHA=$(sha256sum "$OUT/$ZIP" | cut -d' ' -f1)
SHA=$(sha256sum "$OUT/$EXE" | cut -d' ' -f1)
SIZE=$(python -I -c "import os;print(f'{os.path.getsize(\"$OUT/$EXE\")/1048576:.0f} MB')")
echo
echo "Built $OUT/$EXE ($SIZE) and $OUT/$ZIP"
echo "Installer SHA-256 $SHA"
echo "Zip SHA-256       $ZSHA"
echo
echo "version.json \"desktop\" entry, for the release (RELEASING.md):"
cat <<JSON
  "desktop": {
    "versionName": "$VERSION",
    "url": "https://github.com/Shashwat-Gupta57/Notes-Plus/releases/download/desktop-v$VERSION/$EXE",
    "sha256": "$SHA",
    "size": "$SIZE",
    "zip": "https://github.com/Shashwat-Gupta57/Notes-Plus/releases/download/desktop-v$VERSION/$ZIP",
    "zipSha256": "$ZSHA",
    "changelog": "Notes+ for Windows: every kind of note, synced with your phone."
  }
JSON
