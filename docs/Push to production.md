# Push to production

Every Notes+ release ships in four parts: the Android APK, the Windows app, the website (with
Notes+ on the web), and `version.json`, which tells the other three that a release exists.
Do them **in this order**. Users only hear about a release when `version.json` changes, so
everything it points to must already be online by then.

| Part | Built in | Goes to | Users get it through |
|---|---|---|---|
| Android APK | this repo (`master`) | Cloudflare R2, bucket `releases`, `android/Notes-Plus-<ver>.apk` | the in-app updater, the site's download page |
| Windows app | `Notes-Plus-Desktop` (local repo) | Cloudflare R2, bucket `releases`, `desktop/Notes-Plus-Setup-<ver>.exe` & `zip` | Setup's Update, the in-app update check, the site |
| Website + web app | website repo, `web` branch | GitHub Pages, `notes.talken.in` | the browser (instantly on push) |
| `version.json` | this repo, `master` | GitHub raw | everything above reads it |

The examples use version **13.0**, Android build **14**. Replace them with yours.

---

## 0. Before anything

1. **Everything passed testing** on a phone, on Windows, and on the web (signed in and signed out).
2. **Database rules.** If `database.rules.json` changed, deploy it first, because new app versions
   may write fields the old rules refuse:
   ```bash
   cd D:/User/Data/projects/Notes-Plus
   firebase deploy --only database
   ```
3. **Note formats.** If the shape of a note changed (new fields, new JSON in `data`), the web app
   (`app/js/model.js`, `sync.js`) has to read and write it too, or the web drops it on its
   next push.
4. **Versions are bumped together:**
   - `app/build.gradle.kts`: `versionCode = 14`, `versionName = "13.0"`
   - `Notes-Plus-Desktop/pom.xml`: `<version>13.0</version>`
   - website `app/js/account-ui.js`: `export const VERSION = '13.0';`
   - **Not `version.json` yet**: that is the last step.
5. **Release notes.** Update the website's "New in …" section (`index.html`, `#new`) and
   `Notes-Plus-Desktop/release/ANNOUNCEMENT.md`.
6. **Never commit** `dist/`, `app/release/`, logcat files, `oauth-client.json`,
   `client_secret_*.json`, or any key or password.

---

## 1. Build the Android APK

```bash
cd D:/User/Data/projects/Notes-Plus
JAVA_HOME="D:/Program Files/Java/JDK/jdk-21.0.8+9" ./gradlew assembleRelease
sha256sum app/build/outputs/apk/release/app-release.apk
```

- Gradle needs the full JDK above; the IDE's JRE fails on `androidJdkImage`.
- The release is signed with this machine's debug keystore (`~/.android/debug.keystore`), as
  every earlier release was. **Keep that file backed up.** An APK signed with another key won't
  install over the old one; users would have to uninstall, and that deletes their vaults.
- Keep the SHA-256: it goes into `version.json`.

## 2. Build the Windows app

```bash
cd D:/User/Data/projects/Notes-Plus-Desktop
MAVEN_ARGS="-DskipTests -o" bash release/package.sh
```

Output in `dist/13.0/`:
- `Notes-Plus-Setup-13.0.exe`: the installer (what people download)
- `Notes-Plus-Windows-13.0.zip`: what Setup's Update downloads
- the printed SHA-256s and a ready `"desktop": {...}` block for `version.json`

`oauth-client.json` must be in `src/main/resources/org/flexstudios/notes/plus/` (git-ignored),
or the build can't sign in with Google or use Drive. `package.sh` warns when it's missing.

Quick check: unzip the zip into an empty folder, run `Notes+.exe`, open a note.

## 3. Upload the APK to R2

R2 holds **one APK at a time**: upload the new one now, and delete the old one only after
`version.json` points at the new one (step 6).

`tools/r2put.py` signs S3 requests with the standard library only. Its credentials come from
the environment, never from a file:

```bash
cd D:/User/Data/projects/Notes-Plus
export R2_KEY_ID=<R2 access key id>
export R2_SECRET=<R2 secret access key>
python tools/r2put.py app/build/outputs/apk/release/app-release.apk \
    android/Notes-Plus-13.0.apk application/vnd.android.package-archive
```

Check that the public copy is identical (the two hashes must match):

```bash
curl -sI https://releases.talken.in/android/Notes-Plus-13.0.apk | grep -iE "^HTTP|content-length"
curl -s https://releases.talken.in/android/Notes-Plus-13.0.apk | sha256sum
```

## 4. Upload Windows release to R2

Like Android, R2 holds **only one version of Desktop at a time** (the previous version is purged upon new release upload):

```bash
cd D:/User/Data/projects/Notes-Plus
export R2_KEY_ID=<R2 access key id>
export R2_SECRET=<R2 secret access key>

# Upload installer EXE and portable ZIP
python tools/r2put.py ../Notes-Plus-Desktop/dist/15.2/Notes-Plus-Setup-15.2.exe \
    desktop/Notes-Plus-Setup-15.2.exe application/x-msdownload

python tools/r2put.py ../Notes-Plus-Desktop/dist/15.2/Notes-Plus-Windows-15.2.zip \
    desktop/Notes-Plus-Windows-15.2.zip application/zip
```

Verify public URLs:
- `https://releases.talken.in/desktop/Notes-Plus-Setup-15.2.exe`
- `https://releases.talken.in/desktop/Notes-Plus-Windows-15.2.zip`

Tag the desktop repo:
```bash
cd D:/User/Data/projects/Notes-Plus-Desktop
git add -A && git commit -m "v15.2" && git tag v15.2
git push origin desktop --tags
```

## 5. Website and Notes+ on the web

The site is the `web` branch of this repo (GitHub Pages serves it at `notes.talken.in`, with the
web app under `/app/`):

```bash
cd "D:/User/Data/projects/Website Temp/Notes-Plus"
git checkout web
git add -A && git commit -m "13.0"
git push origin web
```

Pages rebuilds in a minute or two. To check it:

```bash
gh api repos/Shashwat-Gupta57/Notes-Plus/pages --jq .status      # "built"
curl -s https://notes.talken.in/ | grep -c "New in 13"            # 1
```

The download page reads versions, links and checksums from `version.json`, so it doesn't need
editing for a release.

## 6. Switch the release on: `version.json`

On this repo's `master`. Fill in the hashes from steps 1 and 2:

```json
{
  "versionCode": 14,
  "versionName": "13.0",
  "forced": false,
  "changelog": "What's new, one or two sentences. Install over this version; don't uninstall first.",
  "apkUrl": "https://releases.talken.in/android/Notes-Plus-13.0.apk",
  "sha256": "<APK sha256>",
  "desktop": {
    "versionName": "13.0",
    "url": "https://github.com/Shashwat-Gupta57/Notes-Plus/releases/download/desktop-v13.0/Notes-Plus-Setup-13.0.exe",
    "sha256": "<installer sha256>",
    "size": "<installer size, e.g. 76 MB>",
    "zip": "https://github.com/Shashwat-Gupta57/Notes-Plus/releases/download/desktop-v13.0/Notes-Plus-Windows-13.0.zip",
    "zipSha256": "<zip sha256>",
    "changelog": "What's new on Windows."
  }
}
```

```bash
cd D:/User/Data/projects/Notes-Plus
git add -A && git commit -m "v13.0" && git tag v13.0.0
git push origin master && git push origin v13.0.0
```

From this moment:
- phones offer the update (at most 12 hours later)
- the PC app and Setup offer theirs
- the site shows the new downloads

Then delete the old APK from R2:

```bash
python tools/r2put.py - android/Notes-Plus-12.0.apk "" DELETE
curl -sI https://releases.talken.in/android/Notes-Plus-12.0.apk | head -1   # 404
```

## 7. After

- Install the APK over the old version on a phone (never uninstall first), and use the updater
  on Windows.
- Update `CLAUDE.md` (version bookkeeping) and commit.
- Rotate the R2 key if it was ever pasted anywhere outside your own shell.

## Checklist

- [ ] Tests passed: phone, Windows, web
- [ ] Database rules deployed (if changed)
- [ ] Versions bumped: gradle, pom, web `VERSION`
- [ ] "New in …" on the site, `ANNOUNCEMENT.md`
- [ ] APK built and its SHA-256 noted
- [ ] Windows built and its SHA-256s noted
- [ ] APK on R2, public hash matches
- [ ] GitHub release `desktop-v…` created
- [ ] Website pushed to `web`, Pages built
- [ ] `version.json` pushed (last), tag pushed
- [ ] Old APK deleted from R2
