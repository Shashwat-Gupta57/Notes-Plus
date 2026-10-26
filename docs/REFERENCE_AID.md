# Notes+ Ecosystem Reference & Memory Aid

## 1. Repositories & Environments
- **Desktop**: `D:\User\Data\projects\Notes-Plus-Desktop` (JavaFX 21, Maven)
- **Android**: `D:\User\Data\projects\Notes-Plus` (Java/Android SDK, Gradle)
- **Web**: `D:\User\Data\projects\Website Temp\Notes-Plus` (Vanilla JS, HTML/CSS, Firebase v9 modular)
- **Firebase DB Rules**: `D:\User\Data\projects\Notes-Plus\database.rules.json`

## 2. Opt-In Shared Folders & Archive
- **Sync Field**: `/users/{uid}/profile/syncFoldersArchive` (Boolean).
- **Cross-Device Rule**: If turned ON or OFF on one device, it updates `/users/{uid}/profile/syncFoldersArchive` in Firebase and syncs to all other devices.
- **When OFF (Default)**:
  - Notes sync content/data, but local `folder` and `archived` values are never sent to Firebase or overwritten by incoming remote notes. Organization stays strictly local to each device.
- **When ON (Opt-in)**:
  - `folder` and `archived` are included in the note sync payload to Firebase.
  - Incoming changes apply `folder` and `archived` to local notes across Desktop, Android, and Web.
- **UI Settings**: Located in the Sync card under Account settings on Desktop (`AccountView`), Android (`AccountActivity`), and Web (`account-ui.js`).

## 3. UI & Dialog System
- `Ui.java`: Material 3 design system. `DialogBuilder` renders modal dialogs with title, message, optional content, and action buttons (`positive`, `negative`, `neutral`).
- **Dialog Button Truncation Fix**:
  - Buttons now have `minWidth(USE_PREF_SIZE)` to prevent ellipsis truncation.
  - When combined button text length > 36 characters or multiple long buttons exist, dialog buttons stack vertically according to Material Design 3 guidelines (Confirming on top, dismissive in the middle, neutral at the bottom).

## 4. Mind Map Canvas & Geometry
- `MindMapCanvas.java` & Android `MindMapActivity.java`:
  - **Text Boundary Fix**: For nodes with large or multiline text, corner radius is capped at `18-24px` instead of `h / 2`.
  - Horizontal padding `padX` is dynamically calculated: `padX = Math.max(PAD_X, r + 8)`, ensuring text rectangles `(b.w, b.h)` are 100% contained within the flat area of the rounded rectangle, eliminating any text spilling over the outer boundary.

## 5. Keyboard Shortcuts & Undo/Redo
- **Close Note / Editor**: `Ctrl+W` in `NotesApp.java`:
  - Closes an active editor / note and returns to the home grid, saving all changes.
  - Closes pop-out windows.
  - Dismisses active dialogs / modals.
- **Mind Map Shortcuts**:
  - `Ctrl+Z` / `Ctrl+Y` (or `Ctrl+Shift+Z`): Undo / Redo for all MindMap actions (node additions, deletions, moves, renames, subtext edits, color changes, picture attachments/removals, connections, tidy).
  - `Shift+Enter` / `Alt+Enter` / `Ctrl+Shift+S`: Add / Edit subtext for selected node.
  - `Ctrl+C`: Copies attached picture (file + image) to clipboard, or copies node text/subtext.
  - `Ctrl+V`: Pastes picture into selected node (or creates new node off root).
  - `Ctrl+K`: Start connecting to another idea.
  - `Space`: Toggle fold / unfold branch.
  - `Tab`: Add child idea.
  - `Enter`: Add sibling idea.
  - `F2`: Rename idea.
  - `Delete` / `Backspace`: Delete branch.
- **Note Checklist Undo/Redo**:
  - `NoteEditorView`: `Ctrl+Z` / `Ctrl+Y` undoes and redoes checklist item additions, deletions, and checkbox toggles.

## 6. MindMap Node Pictures & Central Idea
- **Node Pictures**:
  - Desktop (`MindMapEditor.java`): "Add a picture…", "Change picture…", "View picture", "Remove picture".
  - Android (`MindMapActivity.java`): `choosePicture(node)` via `ActivityResultContracts.OpenDocument`, "View picture" via full-screen `PhotoView` dialog with pinch-to-zoom/pan, and "Remove picture".
  - Web (`mindmap-draw.js`): Geometry and render with capped corner radius `maxRadius = (root ? 24 : 18) * s` and `padX = Math.max(PAD_X, r + 8)` to prevent multiline text from overflowing rounded node boundaries.
- **Add Central Idea**:
  - Desktop: Bottom tools button & node menu `addCentralIdea()`.
  - Android: Dedicated `btnAddCentral` mini FAB in the bottom tools row + node menu option `name(map.addRoot(), true)`.

## 7. MediaViewer (Desktop)
- **Controls & Gestures**:
  - Mouse wheel zooms smoothly (1.0x to 8.0x) centered at mouse cursor.
  - Mouse drag pans the zoomed image.
  - Double-click toggles zoom (1.0x <-> 2.5x).
  - Dedicated 'X' button or `Escape` key closes the viewer (does NOT vanish on arbitrary image clicks).
  - Clicking empty letterbox area outside the image closes the viewer.
- **Context Menu & Action Bar**:
  - Right-click Context Menu: "Copy picture" (copies image & file to clipboard), "Save picture as…" (`FileChooser`), and "Reset zoom".
  - Top-Right Action Bar: Quick access buttons for Zoom In, Reset Zoom (1:1), Copy Picture, and Save Picture As.
- **High-Resolution Decoding**:
  - `Images.java` decodes via `Files.newInputStream(file)` to bypass JavaFX's internal URL-based texture cache, ensuring full-resolution rendering without low-res blurry caching.

## 8. In-App Updates & APK Cleanup (Android)
- `Updater.java`:
  - `isReadyToInstall()` verifies if `installedVersion(context) >= expectedCode`.
  - If already on or newer than the target version, downloaded APKs are deleted immediately from cache and storage, and update preferences are wiped, eliminating endless update prompts.

## 9. "Shared with me" Filter (Desktop, Android, Web)
- **Inclusion Criteria**:
  - Direct sent notes: `n.getSharedFrom() != null` (or `n.sharedFrom`).
  - Shared group notes: `n.getGroupId() != null` (or `n.groupId`).
  - Applied across Desktop (`MainView.java`), Android (`MainActivity.java`), and Web (`app/js/home.js`).

## 10. Versioning & Release Feeds Across Platforms
- **Desktop**:
  - Defined in: `D:\User\Data\projects\Notes-Plus-Desktop\pom.xml` under `<version>14.6</version>`.
  - Filtered at build-time into `src/main/resources/org/flexstudios/notes/plus/app.properties` as `version=${project.version}`.
  - Read at runtime by `Updates.java` to display in settings and compare against update feed releases.
- **Android**:
  - Defined in: `D:\User\Data\projects\Notes-Plus\app\build.gradle.kts`:
    - `versionCode = 22` (monotonically increasing integer)
    - `versionName = "14.6"` (user-facing version string)
  - Read at runtime by `Updater.java` via `packageManager.getPackageInfo()` / `PackageInfoCompat.getLongVersionCode()`.
- **Global Release Feed (`version.json`)**:
  - Location: `D:\User\Data\projects\Notes-Plus\version.json` (published on `master` branch on GitHub: `https://raw.githubusercontent.com/Shashwat-Gupta57/Notes-Plus/master/version.json`).
  - Contains:
    - Android: `versionCode`, `versionName`, `apkUrl`, `sha256`, `forced`, `changelog`.
    - Desktop: `desktop.versionName`, `desktop.url` (or `installerUrl`), `desktop.sha256`, `desktop.size`, `desktop.changelog`.
- **Web App**:
  - Defined in: `D:\User\Data\projects\Website Temp\Notes-Plus\app\js\account-ui.js` as `export const VERSION = '14.6';`.


---

## 11. Inactivity Auto-Save, Conflict Resolution & Share Revocation (v14.0)

### 11.1 Auto-Save on 10s Inactivity
- Rather than waiting for editor exit, notes automatically save after 10 seconds of inactivity when dirty (`isModified()`).
- Desktop: `NoteEditor.java` uses a `PauseTransition(Duration.seconds(10))`.
- Android: `NoteActivity.java` uses `Handler(Looper.getMainLooper()).postDelayed(autoSaveRunnable, 10_000)`.
- Web: `editor.js` uses `clearTimeout(timer); timer = setTimeout(..., 10000)`.

### 11.2 Realtime Conflict Detection & Conflicted Copies
- When a remote note update arrives from Firebase RTDB while the note is currently open:
  - **Clean / Unmodified**: Reloads or notifies watcher seamlessly with remote changes in place.
  - **Dirty / Modified**: Detects conflict. Creates and saves a new conflicted copy note titled `"[Original Title] (Conflicted copy [YYYY-MM-dd HH:mm])"` with dirty sync flag and revision 1 to prevent data loss. Surfaces an in-app toast/notification to inform the user.
- Desktop: `NoteSync.java` -> `saveConflictedCopy(...)` + `NotesApp.notify(...)`.
- Android: `NoteSync.java` -> `saveConflictedCopy(...)` + `Toast.makeText(...)`.
- Web: `sync.js` -> `saveConflictedCopy(...)` + `toast(...)`.

### 11.3 Share Access Revocation & Force-Close
- When share access is withdrawn (unshared by owner, removed from group members, or deleted):
  - The local note is removed / purged from the recipient's database.
  - If the note is currently open in an active editor, the editor is immediately force-closed and a notification is displayed (`"Access to this shared note was withdrawn"`).
- Desktop: `NotesApp.forceCloseNote(noteId, reason)` -> closes stage/tab.
- Android: `NoteActivity.forceCloseIfOpen(noteId, reason)` -> finishes activity on main looper.
- Web: `sync.forceClose(uuid, reason)` -> redirects hash to `#/` and shows toast.

### 11.4 Saved / Synced Status in Bottom Nav / Footer
- Located next to the `Edited [date] · [words]` indicator in the editor footer.
- States: `Unsaved changes`, `Saving…`, `Saved`, `Syncing…`, `Synced`.
