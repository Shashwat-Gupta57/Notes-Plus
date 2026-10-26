# Notes+ Codebase Master Index & Quick Reference

This document provides an exhaustive index of the Notes+ ecosystem across Desktop, Android, and Web platforms.

---

## 1. System Architecture & Tech Stacks

| Platform | Technology Stack | Workspace Path | Key Build File |
| :--- | :--- | :--- | :--- |
| **Desktop** | Java 21, JavaFX 21, SQLite (via pure Java/custom store), Material Design 3 CSS | `D:\User\Data\projects\Notes-Plus-Desktop` | `pom.xml` |
| **Android** | Android SDK (Java 17/21), Room Database, Material Components, Glide, PhotoView | `D:\User\Data\projects\Notes-Plus` | `app/build.gradle.kts` |
| **Web** | Vanilla ES6 Modules, Custom Canvas Renderers, IndexedDB, Firebase SDK v9 | `D:\User\Data\projects\Website Temp\Notes-Plus` | `package.json`, `index.html` |
| **Backend** | Firebase Realtime Database, Firebase Authentication, Google Drive REST API | Multi-repo / Firebase Console | `Notes-Plus/database.rules.json` |

---

## 2. Desktop Codebase Index (`Notes-Plus-Desktop`)

Package: `org.flexstudios.notes.plus` (`src/main/java/org/flexstudios/notes/plus/`)

### Application Core & Navigation
- `Main.java`: Launcher entry point.
- `NotesApp.java`: Main JavaFX application lifecycle, top-level window manager, global hotkeys (`Ctrl+W` to close notes/modals, `Ctrl+N` for new note, `Ctrl+F` find).
- `Screen.java`: Base class for full-window screens.
- `Ui.java`: Material 3 design system engine: dialogs (`DialogBuilder` with adaptive button stacking and `minWidth(USE_PREF_SIZE)`), modals (`Ui.present`), toasts, snackbars, and icon helpers.
- `Icons.java`: Vector SVG path icons matching Android's drawables pixel-for-pixel.
- `Theme.java`: Dynamic color scheme engine (Light, Dark, High Contrast, custom themes).

### Storage & Local Data
- `NoteEntity.java`: Core note entity model (`id`, `uuid`, `title`, `content`, `data`, `type`, `color`, `pinned`, `archived`, `folder`, `timestamp`, `account`, `rev`, `groupId`, `groupMembers`, `sharedFrom`, `sentTo`, `sentMedia`, `media`).
- `NoteStore.java`: Local SQLite database wrapper, thread-safe asynchronous operations via executor.
- `NoteData.java`: Parsing and serialization helpers for note types, JSON payloads, dates, durations.
- `Prefs.java`: Local application settings and preferences (JSON-backed).
- `Storage.java`: File system helpers, path resolution, atomic writes, recursive directory cleanups.

### UI Screens & Views
- `MainView.java`: Primary notes grid screen, folder chips, search bar, "Shared with me" filter, kind chips, context menus.
- `MasonryPane.java`: Dynamic multi-column masonry grid layout for note cards.
- `NoteCard.java`: Individual note tile rendering in the grid (handles previews of sketches, text, lists, audio waveforms, mind maps).
- `FolderTile.java`: Folder representation in grid.
- `AccountView.java`: Account screen (Google sign-in, username, sync toggles, cloud backup, Drive storage).
- `MediaViewer.java`: Full-screen photo/video viewer with smooth mouse-wheel zoom, pan, right-click context menu (copy, save as), double-click toggle, and high-res decoding.
- `GalleryView.java` & `ViewerView.java`: Secret vault media grid and media pager.
- `ChatHomeView.java` & `ChatView.java`: Encrypted peer-to-peer and group messaging.

### Note Editors
- `NoteEditor.java`: Base note editing container, title bar, back button, lock state, sharing triggers.
- `NoteEditorView.java`: Checklists / list note editor with drag-and-drop item reordering and `Ctrl+Z` / `Ctrl+Y` undo/redo.
- `TextEditorView.java` & `RichBody.java`: Markdown and rich text editor with inline embedded pictures (`![[IMG_...]]`).
- `MindMapEditor.java` & `MindMapCanvas.java`: Infinite canvas mind map editor:
  - Text boundary containment calculation (`padX = Math.max(PAD_X, r + 8)`).
  - Undo/Redo history stack (`pushUndo()`, `undo()`, `redo()`).
  - Hotkeys (`Ctrl+Z`, `Ctrl+Y`, `Shift+Enter`, `Ctrl+C`, `Ctrl+V`, `Ctrl+K`, `Space`, `Tab`, `Enter`).
- `SketchEditor.java`: Vector sketch drawing canvas with stroke smoothing, color palette, pen/eraser.
- `AudioNoteEditor.java`, `AudioRecorder.java`, `WaveformView.java`: Voice notes, playback, and waveform rendering.

### Sync, Networking & Security
- `NoteSync.java`: Firebase Realtime Database sync engine (conflict resolution, tombstone tracking, revision control, opt-in folder/archive sync).
- `GoogleAuth.java`: OAuth 2.0 PKCE desktop browser loopback flow for Google Sign-In and Drive access.
- `Firebase.java`: REST client for Firebase Database and Auth.
- `Drive.java`: Google Drive API client for media attachments and large file synchronization.
- `GroupNotes.java`: Collaborative group note sharing and member roster management.
- `SentNotes.java`: One-to-many direct note transmission.
- `ShareLinks.java`: Public web view sharing link generation.
- `Vault.java`, `VaultCrypto.java`, `VaultStore.java`: AES-GCM encrypted vaults, media encryption, PBKDF2 key derivation.
- `NoteLock.java`, `LockInput.java`, `UnlockView.java`: PIN/password note protection.
- `Updates.java`: Desktop update checker reading `version.json`.

---

## 3. Android Codebase Index (`Notes-Plus`)

Package: `org.flexstudios.notes.plus` (`app/src/main/java/org/flexstudios/notes/plus/`)

### Application & Main UI
- `NotesApplication.java`: Application startup, channel setup, theme initialization.
- `MainActivity.java`: Home screen, note recycler view, folder chips, "Shared with me" filter chip, search, bottom navigation.
- `NoteAdapter.java`: RecyclerView adapter rendering cards with previews for sketches, todos, mindmaps, audio.
- `AppTheme.java`: Dynamic color theming and Material 3 day/night palette resolution.

### Editors & Activities
- `NoteActivity.java`: Abstract base editor activity managing auto-save, lock lifecycle, title, toolbar.
- `NoteEditorActivity.java`: List / checklist editor and rich body display.
- `MindMapActivity.java`: Interactive mind map view:
  - Node picture viewing (`PhotoView` dialog with pinch-zoom) and picture picking (`choosePicture`).
  - Dedicated "Add central idea" mini FAB (`btnAddCentral`).
  - Text containment inside rounded capsules.
- `SketchActivity.java`: Drawing canvas activity.
- `AudioNoteActivity.java`: Audio recording and playback activity.
- `AccountActivity.java`: Account management, Google Sign-In, sync folders/archive opt-in toggle.
- `ChatActivity.java` & `ChatHomeActivity.java`: Secret vault chat.
- `MediaPagerActivity.java` & `MediaPagerAdapter.java`: Full-screen vault media browser.

### Data Layer & Sync
- `AppDatabase.java`: Android Room database container (`NoteDao`, `VaultDao`, `ChatDao`).
- `NoteEntity.java`: Room entity definition with full parity to Desktop schema.
- `NoteDao.java`: DAO SQL queries and operations.
- `NoteSync.java`: Android background sync worker and live listener for Firebase changes.
- `SentNotes.java`: Sending notes and receiving inbox notes.
- `GroupNotes.java`: Shared collaborative group notes.
- `ShareLinks.java`: Link sharing sheets.
- `Updater.java`: In-app updates via DownloadManager, APK verification, self-cleanup of downloaded APKs to prevent endless update loops.

---

## 4. Web Codebase Index (`Website Temp/Notes-Plus`)

Path: `app/js/`

### Core Modules
- `index.html` & `app/app.css`: Web application layout, variables, responsive styling.
- `main.js`: Web routing (`#/new/:type`, `#/note/:id`, `#/account`), service worker registration.
- `ui.js`: DOM generation helpers (`h()`), SVG icon templates, modal dialogs, toasts, bottom sheets.
- `db.js`: IndexedDB wrapper mirroring `NoteStore` / `NoteDao`.
- `home.js`: Notes grid, folder management, "Shared with me" filter (checks `n.sharedFrom || n.groupId`).
- `account.js` & `account-ui.js`: Firebase Web Auth, Google provider, profile, sync options (`VERSION = '14.0'`).
- `sync.js`: Firebase Realtime Database Web sync engine.
- `drive.js`: Google Drive Web API client with Google Identity Services (GIS).
- `lock.js`: Web Crypto API (SubtleCrypto) for note lock and vault decryption.

### Web Editors
- `ed-text.js`: Plain/rich text note editor.
- `ed-list.js`: Todo checklist editor with drag-and-drop.
- `ed-mindmap.js` & `mindmap-draw.js`: HTML5 Canvas mind map renderer with smooth curved branches, dynamic padding `padX`, and capped capsule radii.
- `ed-sketch.js`: HTML5 Canvas sketch drawing editor.
- `ed-audio.js`: Audio playback and recorder with Web Audio API.

---

## 5. Versioning Master Map

| Component | File Path | Current Target | Mechanism |
| :--- | :--- | :--- | :--- |
| **Desktop Version** | `Notes-Plus-Desktop/pom.xml` | `14.0` | `<version>14.0</version>` &rarr; filtered to `app.properties` |
| **Android Version Code** | `Notes-Plus/app/build.gradle.kts` | `16` | `versionCode = 16` |
| **Android Version Name** | `Notes-Plus/app/build.gradle.kts` | `"14.0"` | `versionName = "14.0"` |
| **Global Update Feed** | `Notes-Plus/version.json` | `14.0` (Code 16) | Checked by `Updater.java` and `Updates.java` |
| **Web UI Version** | `Website Temp/Notes-Plus/app/js/account-ui.js` | `"14.0"` | `export const VERSION = '14.0';` |

---

## 6. Build, Run & Test Commands

### Desktop (JavaFX / Maven)
```bash
# Compile code
mvn -f "D:\User\Data\projects\Notes-Plus-Desktop\pom.xml" compile -q

# Run unit tests
mvn -f "D:\User\Data\projects\Notes-Plus-Desktop\pom.xml" test -q

# Run desktop application
mvn -f "D:\User\Data\projects\Notes-Plus-Desktop\pom.xml" javafx:run

# Package executable jar & Inno Setup installer (Run in Git Bash)
& "C:\Program Files\Git\bin\bash.exe" release/package.sh
```

### Android (Gradle)
```bash
cd "D:\User\Data\projects\Notes-Plus"

# Compile Debug Sources
.\gradlew.bat compileDebugSources -q

# Build Debug APK
.\gradlew.bat assembleDebug

# Build Release APK
.\gradlew.bat assembleRelease
```

### Web
```bash
cd "D:\User\Data\projects\Website Temp\Notes-Plus"

# Serve with any static web server (e.g., Python or Live Server)
python -m http.server 8000
```
