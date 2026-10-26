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
