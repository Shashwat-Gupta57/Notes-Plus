# Contributing to Notes+

Thanks for helping. This page is the whole process: how the repository is laid out, how to build
each app, the rules every change has to follow, and how pull requests are reviewed and merged.

## Where things live

| Branch | App | Build and test |
|---|---|---|
| `master` | Android (Java 11 source, Room, Material 3) and `version.json` | `./gradlew assembleDebug testDebugUnitTest` with a full JDK 21 (`JAVA_HOME` must point at a JDK, not the IDE's JRE) |
| `desktop` | Windows (Java 21, JavaFX, SQLite) | `mvn test`, run with `mvn javafx:run` |
| `web` | Website and web app (plain HTML, CSS and ES modules, GitHub Pages) | no build; serve the folder (`python -m http.server`) and open it |

The three share one note format and one database shape. **A change to how a note is stored must be
made on all three branches**, or the platform you skipped will drop the new data on its next sync.

## Before you start

- **Small fix?** Open a pull request directly.
- **New feature or a bigger change?** Open an issue first and describe it, so we agree on the
  approach before you spend time on it.
- Check open issues and pull requests so two people don't build the same thing.

## Making a change

1. Fork the repository and branch off the right base: `master`, `desktop` or `web`.
   Name the branch `fix/<what>`, `feat/<what>` or `docs/<what>`.
2. Keep the change focused: one fix or one feature per pull request.
3. Follow the code around you: same naming, same comment density, same patterns. On Android:
   views with `findViewById`, background work on `AppDatabase.IO`, dialogs with
   `MaterialAlertDialogBuilder`, colours from `@color/…` names (never raw hex), every Room schema
   change with a hand-written migration.
4. Build and run the tests for the branch (table above), and try the change in the app itself.
5. Write commit messages that say what changed and why, in the present tense:
   `Lists: keep a picture item's place when reordering`.

## Rules every change must keep

- **No analytics, ad or crash-reporting SDKs**, and nothing that reports screens automatically.
- **No server code.** Notes+ is Backend Zero: anything shared goes through the database and its
  security rules, files through the user's own Google Drive.
- **The private spaces stay private.** The vault never talks to the notes database, and no
  screen, string, setting or log of one hidden space mentions another or hints that it exists.
- **Plaintext of encrypted content never touches disk**, and media is never decrypted with
  single-shot `Cipher.doFinal` (it runs phones out of memory).
- **Versions move together**: `app/build.gradle.kts`, the desktop `pom.xml`, the web app's
  `VERSION`, and `version.json`, which only changes when a release is actually published.
- **Never commit secrets or build output**: keystores, `oauth-client.json`, `client_secret_*.json`,
  `dist/`, `app/release/`, logcat dumps.

## Pull requests

Open the pull request against the branch you started from and fill in the template. Then:

1. **Checks run automatically** (build and tests for that branch). They must pass.
2. **A maintainer reviews it.** Expect questions; push follow-up commits to the same branch.
3. **Merge** is by *squash and merge*, so each pull request becomes one commit with a clear message.

A pull request is ready when: the checks pass, the change was tried in the app, user-visible changes
are described (with a screenshot for UI), and any change to the note format covers all three apps.

## Reporting bugs and ideas

Use the issue templates. For bugs, say which app and version (Account → About), what you did,
what happened and what you expected; Android's Account → App logs can be saved and attached.
Security problems go through [SECURITY.md](SECURITY.md), never a public issue.

## License

By contributing you agree that your contribution is licensed under the
[GPL-3.0](LICENSE), like the rest of Notes+.
