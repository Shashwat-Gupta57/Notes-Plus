# Notes+ for desktop

The Notes+ notes app (text, lists, journal, mind maps, sketches, audio) on Windows, in JavaFX,
looking and behaving like the Android app and syncing with it through the same account.

## Run

```bash
mvn javafx:run                       # from source (JDK 21)
mvn package                          # target/Notes-Plus-Desktop-1.0-SNAPSHOT-all.jar
java -jar target/Notes-Plus-Desktop-1.0-SNAPSHOT-all.jar
```

Data lives in `%APPDATA%\Notes+` (`notes.db`, each note's files in `notes\<id>\`, settings in
`prefs.json`, the signed-in session in `account.json`).

## Google sign-in and Drive (one-time setup)

Email + password accounts work as they are. Google sign-in (and Google Drive for photos and
recordings) needs an OAuth client for desktop apps in the Firebase project's Google Cloud project:

1. Google Cloud Console → project **notes-plus-88985** → APIs & Services → Credentials →
   Create credentials → OAuth client ID → Application type **Desktop app**.
2. Download its JSON and save it as `oauth-client.json` in `%APPDATA%\Notes+\` (or in
   `src/main/resources/org/flexstudios/notes/plus/` to build it into the jar; it is git-ignored).

The browser opens Google's own page; the answer comes back to the app on 127.0.0.1.

## Desktop extras

- Paste (Ctrl+V) or drop a picture into a note or journal: it appears where the caret was.
  In the text it is a line `![[IMG_….png]]`, the file sits in the note's folder.
- In a mind map, paste a picture into the selected idea (or right-click → Add a picture…).
- Hold Ctrl: links and IP addresses are underlined. Ctrl+click opens a link, or copies an IP.
- Ctrl+N new note, Ctrl+F search, Ctrl+S save, Esc back; in a mind map Tab adds an idea,
  Enter a sibling, F2 renames, Delete removes; in a sketch Ctrl+Z / Ctrl+Y, P / M / E.

## Tests

`mvn test` runs the unit tests. Sync was checked end to end against the Firebase emulators
(Auth + Database, with the phone repo's `database.rules.json`).

## Contributing and license

This is the `desktop` branch of [Notes+](https://github.com/Shashwat-Gupta57/Notes-Plus): read
[CONTRIBUTING.md](https://github.com/Shashwat-Gupta57/Notes-Plus/blob/master/CONTRIBUTING.md) before
opening a pull request (against `desktop`). Notes+ is free software under the
[GNU General Public License v3.0](LICENSE).
