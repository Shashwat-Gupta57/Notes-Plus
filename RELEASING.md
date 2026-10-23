# Releasing Notes+ for Windows

Nothing here runs until the release is approved after full testing. Steps 1–3 build and
check locally; steps 4–6 publish, and each one is public.

## Before the first release
- **Google sign-in and Drive**: create a "Desktop app" OAuth client in the Google Cloud project
  of `notes-plus-88985` and save its JSON as
  `src/main/resources/org/flexstudios/notes/plus/oauth-client.json` (git-ignored). Without it,
  the build can sign in only with email, and can't use Drive (note pictures, chat media).
  `package.sh` warns when it's missing.
- **Database rules**: deploy the phone repo's `database.rules.json`
  (`firebase deploy --only database`); it carries the `vaultPhotos` fix that account deletion needs.

## 1. Version
Set `<version>` in `pom.xml` (e.g. `1.0.0`).

## 2. Build
```bash
release/package.sh
```
Runs the tests and builds, in `dist/<version>/`: the app image `Notes+/` (Notes+.exe, Notes+ Setup.exe,
unins.exe, one Java runtime), `Notes-Plus-Windows-<version>.zip` (that folder: what Setup's Update
downloads) and `Notes-Plus-Setup-<version>.exe` (the installer, made with Windows' own IExpress).
It prints both SHA-256s and the `version.json` entry (`url` = the installer, `zip` = the zip).

## 3. Check the zip
Extract it to a new folder, run `Notes+.exe`, sign in, open a note of each kind, open both hidden
spaces. Put the SHA-256 into `release/ANNOUNCEMENT.md`.

## 4. Publish the download (GitHub release on the app's repo)
```bash
gh release create desktop-v<version> dist/<version>/Notes-Plus-Setup-<version>.exe dist/<version>/Notes-Plus-Windows-<version>.zip \
  -R Shashwat-Gupta57/Notes-Plus --title "Notes+ for Windows <version>" \
  --notes-file release/ANNOUNCEMENT.md --latest=false
```
`--latest=false` keeps the Android release as the repo's "Latest".

## 5. Switch the website on
Add the printed `"desktop": {...}` entry to `version.json` on the phone repo's `master` and push.
The phone's updater ignores it; the website reads it and, by itself, shows the "Notes+ for
Windows is here" banner, turns "Coming soon" into "Out now", and enables the Windows download
card with its checksum.

## 6. Website
The site lives on the `web` branch (GitHub Pages, `notes.talken.in`). It must be pushed before
or with step 5.
