#!/usr/bin/env bash
# Builds the Windows release: a self-contained folder (its own trimmed Java runtime, no
# installer) zipped as dist/Notes-Plus-Windows-<version>.zip, and prints the "desktop" entry
# for version.json. Builds only: publishing is a separate, deliberate step (RELEASING.md).
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

SHA=$(sha256sum "$OUT/$ZIP" | cut -d' ' -f1)
SIZE=$(python -I -c "import os;print(f'{os.path.getsize(\"$OUT/$ZIP\")/1048576:.0f} MB')")
echo
echo "Built $OUT/$ZIP ($SIZE)"
echo "SHA-256 $SHA"
echo
echo "version.json \"desktop\" entry, for the release (RELEASING.md):"
cat <<JSON
  "desktop": {
    "versionName": "$VERSION",
    "url": "https://github.com/Shashwat-Gupta57/Notes-Plus/releases/download/desktop-v$VERSION/$ZIP",
    "sha256": "$SHA",
    "size": "$SIZE",
    "changelog": "Notes+ for Windows: every kind of note, synced with your phone."
  }
JSON
