# Notes+ — Claude working notes

Android app (Java, no Kotlin, no Compose, no DI framework). A plain notes app on the
surface; a hidden encrypted media vault underneath, reachable only via a secret gesture.

## Build & run

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
./gradlew installDebug
./gradlew lint
```

- Gradle Kotlin DSL + version catalog. **Add every dependency to `gradle/libs.versions.toml`
  first**, then reference it as `libs.x.y` in `app/build.gradle.kts`. Never hardcode a
  coordinate string in the build file.
- `namespace` / `applicationId` = `org.flexstudios.notes.plus`; minSdk 24, target/compile 36.
- Java 11 source/target. Annotation processors: Room, Glide. No kapt/ksp.
- Release build has `isMinifyEnabled = false` and **no signing config**.

## Architecture

No MVVM, no repository layer. Activities talk to Room DAOs directly and hop threads with
`AppDatabase.IO` (the one shared 4-thread pool) + `runOnUiThread`. Never create a new
executor per click. Media operations go through `VaultMedia`, never re-implemented in a
screen.

**Persistence** — `AppDatabase` (Room, singleton, version 18, `notes_database`)

| Entity | Table | Notes |
|---|---|---|
| `NoteEntity` | `notes` | The cover notes app. `type` picks the editor, `content` is searchable text, `data` is the type's JSON (see `NoteData`), media in `filesDir/notes/<id>/` (plain, not vault-encrypted). `uuid`/`account`/`rev`/`dirty`/`deleted`/`media` belong to `NoteSync` |
| `VaultEntity` | `vaults` | id 1 = "Main Vault". `lockValue` is plaintext; `uuid` is the cross-device identity |
| `SecretEntity` | `secrets` | One row per hidden media file; `fileName` is the key everywhere |
| `AlbumEntity` | `albums` | Scoped to a vault; has a `uuid` |
| `ChatStore.Vault` / `.Conversation` / `.Message` | `chat_vaults` / `chat_threads` / `chat_messages` | The chat space. Separate tables on purpose: no gallery query can ever return a chat vault |

Migrations 2→13 are hand-written in `AppDatabase`; 9→10 adds the `secrets` indexes (which
must match the `@Index`es on `SecretEntity` exactly), 10→11 adds `durationMs`, 11→12 adds note
`type`/`data`/`color`/`pinned`, 12→13 the note sync columns (backfilling `uuid`), 13→14 the
chat tables, 14→15 chat vault sync (`updatedAt`/`dirty`/`deleted`) and conversation `moved`,
15→16 chat media, nicknames and shared backgrounds, 16→17 chat photos, ID aliases and list
backgrounds, 17→18 group notes, group chats and message senders. Only `fallbackToDestructiveMigrationFrom(1)`
is on, so a missing migration now crashes instead of wiping the vault — **always add it**.
`onOpen` seeds vault 1 asynchronously; setup calls `AppDatabase.ensureMainVault` itself so it
never races the unlock screen.

**Media storage** — `filesDir/vault/<fileName>`, Tink streaming AEAD (AES256_GCM_HKDF_4KB)
under an Android Keystore master key, via `VaultCrypto.openRead/openSeekable/write`. This is
byte-compatible with androidx `EncryptedFile` (same keyset prefs, AAD = file name), which is
how files from older versions still open — **never change the keyset names or the AAD**.
Writes go to `<name>.part`, are fsynced, then renamed. `fileName` format:
`IMG_<epochMillis>_<8 hex>` / `VID_...` (older: `_<index>`). **All vaults share this one
directory**; separation is DB-level only, and `SecretDao.getSecretByFileName` is global.

**Plaintext never touches disk** except in `VaultCrypto.shareDir` (wiped on vault entry).
Photos are viewed from memory with Glide caches off; videos play through
`MediaPagerAdapter.VaultDataSource` (seekable decryption); thumbnails and video frames go
through `ThumbnailStore` / `VaultCrypto.mediaDataSource`.

**Notes app** — `NoteData` defines every note type (Text, List, Journal, Mind map, Sketch,
Audio), its JSON and its folder. Editors extend `NoteActivity`, which loads, saves on pause
(only when changed), discards abandoned new notes, and owns pin / colour / delete:
`NoteEditorActivity` (text, list, journal), `MindMapActivity` (+ `MindMapView`, also the card
preview), `SketchActivity` (+ `DrawingView`; strokes in `sketch.json`, card in `sketch.png`),
`AudioNoteActivity` (+ `WaveformView`). `NoteAdapter` draws one card layout for all types.
Long swipes on the notes grid are the secret gestures (left: gallery, right: chat), so there
is no swipe-to-delete; notes are deleted from their options or their editor.
Notes are not in the vault's `.bak` or Drive backups; they sync through the notes account.

**Notes accounts** — optional; signed out, the app is exactly as before.

| Class | Role |
|---|---|
| `Account` | The only Firebase Auth code. Google (Credential Manager) or email + password; email accounts sync only once verified. `@username` is a public handle, never a login. Drive for attachments is authorised separately (Authorization API, `drive.file`). Sign out, delete account. |
| `NoteSync` | Room ↔ Realtime Database (`users/{uid}/notes/{uuid}`), attachments ↔ the user's Drive (`Notes-plus/Attachments/{uuid}/`; the database holds only the manifest). Worker (`NoteSync.Job`, unique `np-notes-sync`, plus a 6 h periodic pull) and a live listener while the grid is visible. |
| `AccountActivity` | Sign-in, verify-your-email, then profile / sync status / Drive / username / sign out / delete. |

Sync rules, all in `NoteSync`: every write bumps `rev` by one (the database rules enforce it);
push is a transaction that only lands on the `rev` it was based on, otherwise the server's
version takes the note and ours becomes a "(conflict, <device>)" copy. Pull only overwrites
clean notes. A note in `NoteSync.OPEN` (open in an editor, or deleted with Undo still on
screen) is neither pushed nor overwritten; sync is queued when an editor closes or the app goes
to the background, never while typing. Deletes are tombstones, swept after 30 days.
Mind-map links travel as `linkUuid`, because row ids differ per phone.

**Chat** — `ChatSync` (transport), `ChatStore` (Room). Each chat vault has a 4-character ID;
an address is `@username` + ID, and an empty ID means the main vault, published at
`chatHome/{uid}`. A conversation is `chats/{chatId}` with `chatId` = both `uid_ID` endpoints
sorted (the rules check each member is in the key). Messages are written locally first with a
push key made offline (so a retry can't duplicate), then sent by `ChatSync.Job` (WorkManager,
survives the app closing). Status: waiting → sent → received (recipient's phone fetched it)
→ seen (conversation on screen); "not sent" when refused. `chatIndex/{uid}/{chatId}` (its `at`
changes per message) tells phones which conversations have news; a 15-minute periodic job and
a live listener while a chat screen is open do the fetching. Online only the newest 100
messages per conversation are kept: the sender counts (`count`, a transaction) and deletes the
oldest. No notifications, ever. Messages are not end-to-end encrypted.

`ChatMedia` handles photos, videos and chat backgrounds: each file is encrypted with its own
random key (portable v3 stream), uploaded to the sender's Drive (`Notes-plus/Shared`, via the
notes account's Drive connection) and shared "anyone with the link"; the message carries the
Drive id and the key, so the public link alone is noise. Phones keep only the encrypted copy
(`filesDir/chat_media`); pictures decrypt into memory, videos into `cache/chat_plain` (swept on
entry, deleted when the viewer closes). The sender's trim also deletes its own dropped files.
Nicknames are private (`chatNick/{uid}/{chatId}`); the background is shared
(`chats/{chatId}/theme`: a colour or an image). Chat sends use `ExistingWorkPolicy.REPLACE`:
appending once parked new messages behind a failed run's retry delay.

Photos: `profiles/{uid}/photo` is the account's (Google `{url}` until a custom one is set,
from `AccountActivity`); `vaultPhotos/{uid}/{ID}` a vault's own (readable by ID only, never
listable). A peer's photo resolves vault photo → account photo (`ChatSync.applyPhotos`).
Changing a vault's ID retires the old one with `renamedTo`, so messages to it still arrive
(`ChatVaults.reached`), and writes `chats/{chatId}/alias/{uid}` so the other side shows the new
ID (`peerShown`). The lock screen background set from chat settings is the same setting as the
gallery's on purpose: one lock screen look, or swiping both ways would reveal two spaces. The
chat list background is per vault, this phone only, encrypted with the device key.

**Group chats** (`ChatSync.createGroup` / `addToGroup` / `leaveGroup`): `groupChats/{gid}` with
`meta` (name, owner), `members/{uid}` = {code, name}, `size` (≤ 20, the rules cap it), `messages`
with per-member receipts `rcv/{uid}` and `seen/{uid}`. Locally `chatId` = "g_" + gid and
`ChatSync.path()` maps any conversation to its node. Group index entries carry `gid`, because the
rules can't cut it out of the key. A message shows "received"/"seen" once every other member has.
The group's owner can remove members (`ChatSync.removeFromGroup`); removed members keep what
their phone has but are refused from then on. **View once**: photos and videos sent with
`media.once`; the recipient sees no preview, opens it in `ChatMediaActivity` (always
`FLAG_SECURE`, closed on leaving), and `ChatSync.markOpened` drops their copy and writes `opened`
(per member in groups); once everyone has opened it, the sender's phone deletes the file from
its Drive. The sender keeps no viewable copy either.
**Voice notes** record AAC in `cache/chat_plain`, travel like any chat media (encrypted, by link)
with their waveform in `media.wave`, and play from a temporary decrypted file.

**Group notes** (`GroupNotes`): any note but audio, shared with up to 20 people, at
`groupNotes/{id}` (owner, members {uid: username}, size, `note` in the same shape as my own
notes). `NoteSync` pushes and pulls them like my own, just at that path; `groupIndex/{uid}/{id}`
(`at`) announces changes and drives the live listener. Sharing turns a note into a group note
under its own uuid and retires its personal copy online. Deleting one here means leaving it;
being removed keeps it here as my own note. Group note files are encrypted and shared by link
(manifest entries carry `key`), so members without Drive still get them.

`ChatVaults` owns the vaults across the account's phones:
- Chat locks are stored as `h1:<salt>:<PBKDF2 hash>` (here and online), never plainly; unlock
  derives once per salt, off the main thread (`ChatVaults.find`).
- Definitions sync through `chatVaults/{uid}/{ID}`: newest `updatedAt` wins, deletions and
  retired IDs are tombstones (an ID is never reused). Exactly one main vault: if two phones made
  one each, the older wins and the other's conversations move into it (`settleMain`).
- A conversation's address never changes (`chatId` embeds the IDs), but it can be filed under
  another vault: `chatPlace/{uid}/{chatId}` = ID. Every phone files it there; the other person
  sees nothing. Changing a vault's ID records its conversations as moved to it.
- A phone's chat vaults are bound to the first account they synced with (`chat_account`); with
  another account signed in, chat stays local.
- One writer per vault: `ChatVaults.Session` holds a lease at `chatSessions/{uid}/{ID}` while a
  chat screen of that vault is visible (renewed every 15 s, stale after 45 s, released 2 s after
  the last screen leaves). A second phone reads only: banner, no compose / new chat / changes /
  "seen", and polls instead of listening (list every 5 s, conversation every 2 s). Offline, a
  phone stays writable; its messages just queue.

`database.rules.json` (deploy with `firebase deploy --only database`) is the security model:
per-uid read/write, verified email or Google only, typed and size-capped fields, atomic
username claims. Change it together with `NoteSync.toRemote`, or writes start failing.

**Screen flow**

```
MainActivity (notes)
  ├─ long LEFT swipe, held 2s   →  UnlockActivity                (gallery realm)
  │     matches a gallery vault →  SecretGalleryActivity (vaultId extra)
  │                                  ├─ MediaPagerActivity  (view/share/fav/album)
  │                                  └─ GallerySettingsActivity (vaults, backup, Drive)
  └─ long RIGHT swipe, held 2s  →  UnlockActivity + EXTRA_REALM = REALM_CHAT
        matches a chat vault    →  ChatHomeActivity (vaultId extra)
                                     ├─ ChatActivity          (one conversation)
                                     └─ ChatSettingsActivity  (ID, lock; main: vaults, auto-lock)
```

**Two hidden spaces that never mention each other.** The gallery and the chat space share the
lock screen (identical on purpose) and one persisted lockout, but nothing else: separate vault
tables, separate auto-lock and last-active prefs (`SecurityHelper` keys suffixed with the
realm), and no screen, string, setting or backup of one refers to the other. The first chat
vault is created on the first right swipe with the gallery main vault's lock *at that moment*
(`ChatSync.ensureMainVault`); after that the locks are independent, and the chat space suggests
a lock of its own once. Keep it that way: never add a link, count, hint or shared setting
between the two.

`UnlockActivity.EXTRA_VAULT_ID` is the extra that carries the unlocked vault through
every downstream screen. **Every vault screen extends `VaultActivity`**, which sets
`FLAG_SECURE` (unless that space's "Allow screenshots" switch is on: `SecurityHelper.applyScreenProtection`, per realm), reads `vaultId`, and enforces auto-lock on resume (relocking via
`MainActivity.EXTRA_RELOCK`). Call `expectExternalActivity()` before launching a picker,
share sheet or sign-in, or returning from it will lock the vault. Unlock failures and
lockouts are persisted (exponential, 30 s → 1 h).

**Backup / sync** — long jobs (Drive backup, local backup, local and Drive restore) run in
`SyncWorker` as foreground work with a progress notification and Cancel; screens only watch
them with `SyncWorker.watch`. Passwords go to the worker in memory (`SECRETS`), never in
WorkManager input data, which is persisted. Two formats with different properties:

- `BackupManager` — local `.bak`: `NPBK|03|keyring|v3 payload` of a zip (snapshot.json +
  media/ + backgrounds/), streamed both ways. Carries its own keyring, so it opens on a new
  phone. Still reads v1 (old main-lock) and v2 (migration screen) files. Not incremental.
  Restore goes through `Snapshot.apply`, the same code as a Drive restore.
- Drive — a content-addressed object store under
  `Notes-plus/Saved-Notes/Cloud/Remote/Backup`. Vault media is write-once, so `fileName` is a
  sound immutable object key and a backup uploads `local − alreadyStored` only. 5 GB stored
  plus 300 MB new means 300 MB moves.

  | Class | Role |
  |---|---|
  | `DriveStore` | Drive API: escaped queries, folder-id cache, retry/backoff, typed auth/quota errors, plus encrypted JSON document I/O. Reads never mutate. |
  | `ObjectIndex` | `objects_index.json` — the stored inventory. One API call replaces a per-object existence check. |
  | `Snapshot` | One restore point: a manifest referencing objects by name, containing no bytes. Lists only objects actually stored. `apply()` writes one back. |
  | `CloudSync` | plan / backup / restore / retention / GC / legacy re-key. Every mutating op holds one process-wide lock; the index is checkpointed every 20 files / 60 s. |
  | `SyncWorker` | Debounced unique work (`np-drive-sync`), network constraints, real progress, foreground promotion (deliberately generic notification). |
  | `DriveSetupActivity` | The one setup question, shown once after sign-in. |
  | `SyncChoiceActivity` | Device-vs-store conflict, shown only when the baseline is empty. |
  | `BackupMigrationActivity` | Three routes off the pre-7.50 payload format. |

  Settings owns the status card ("To upload: N items - X MB"), the progress bar fed by
  `WorkManager` progress, and the restore-point list with pinning.

**Crypto** — `VaultCrypto` is the only crypto code. Payloads are encrypted with a random
**Vault Data Key**; a lock value (or the optional **backup password**) only *wraps* that key in
`keyring.json`. With a backup password set it is the *only* wrap, because a 4-digit PIN falls
to an offline guesser in minutes (PBKDF2 at 210k: ~180 ms/guess on one desktop core). Changing
a lock rewrites ~200 bytes and touches no media. Envelopes: `NP|03|keyId` + Tink segmented
stream with the header as AAD (written now); `NP|02` single-shot GCM and v1 (lock-derived, no
magic) are read only. PBKDF2 falls back to a direct implementation on API 24/25.

**Self-update** — `Updater` polls `version.json` on GitHub `master` (at most every 12 h, from
`MainActivity.onResume`), downloads only after the user agrees, and `Updater.DownloadReceiver`
accepts only its own download id. The APK must match package, advertised `versionCode`, the
installed signing certificate, and `sha256` from `version.json` when present (add it to every
release). Install is offered from the foreground; it is never launched from the background.

## Conventions

- Package is flat: every class sits in `org.flexstudios.notes.plus`. Keep it that way.
- Views via `findViewById`; no view binding, no data binding.
- Background work: `AppDatabase.IO`. `NoteEditorActivity` keeps its own single thread on
  purpose (an insert must land before the update that follows it).
- Adapters call `notifyDataSetChanged()` (except `NoteAdapter`, which is a `ListAdapter`).
- `SecurityHelper` is the single accessor for both preference stores:
  `getAppPrefs()` (plain `app_prefs`) and `getEncryptedPrefs()` (`secret_prefs`).
- Decrypted media only ever goes to `VaultCrypto.shareDir`, swept on vault entry.
- Dialogs: always `MaterialAlertDialogBuilder` (themed by `ThemeOverlay.App.Dialog`); text
  input through `Prompts.text` / `Prompts.field`, progress through `Prompts.progress`. Never a
  bare `EditText` in a dialog, never `ProgressDialog`.
- **The vault never touches Firebase**: no vault class imports `Account`/`NoteSync`, and
  neither of them reads `secrets`, `vaults` or `albums`. No Firebase Analytics, Crashlytics
  or Remote Config: automatic screen reporting would name the vault's screens.
- The vault's Drive backup keeps its own legacy `GoogleSignIn` account; notes attachments use
  `Account.driveEmail` through the Authorization API. Never merge the two.
- Every local backup is read back and verified (`BackupManager.verify`) before it is reported
  as saved; Settings → Restore → "Check a backup file" runs the same check on any `.bak`.
- The grid and the viewer load the whole filter as light `SecretItem` rows (`SecretDao.galleryRows`
  etc., one indexed query) and only thumbnails are lazy. The grid diffs every change through
  `AsyncListDiffer`: call `loadGallery(false)` after a mutation, never `notifyDataSetChanged`.
  Selection is by file name. Grid touch behaviour (pinch columns, drag-select, fast scroller)
  lives in `GalleryGestures`. Deletes are deferred behind an undo bar and committed in `onStop`.
- Thumbnails live in `filesDir/thumbs2` (512 px, EXIF-upright). Bump the directory name to
  regenerate them all; never write them anywhere else.

## Version bookkeeping

`app/build.gradle.kts` (`versionCode`/`versionName`), `version.json`, and the git tag must
be bumped **together** — the in-app updater compares `version.json`'s `versionCode` against
the installed one, so a stale build file makes every user appear up to date.
The build file is at 11 / "10.0"; `version.json` still advertises 10 / "9.2" (released as v9.2.0,
with `sha256`) until a 10.0 APK is published.
Bump `version.json` only when the APK for a new version is actually published, or every user
is prompted to download a release that does not exist.

## Landmines

- `lockValue` is stored and compared **in plaintext** in the `vaults` table — never assume
  it is hashed. (It is no longer the payload key, and no longer uploaded in the clear.)
- Changing a lock is **gated** while `VaultCrypto.hasUnmigratedLegacyPayloads()` holds,
  because pre-7.50 payloads were encrypted with the lock value itself.
  `BackupMigrationActivity` offers the three ways out.
- Nothing in the cloud path may delete an object a retained restore point still names. A
  fresh install has no baseline, which is what makes `Plan.needsChoice` true and sends the
  user to `SyncChoiceActivity` instead of mirroring.
- Room DAO `LiveData` getters return a **new** `LiveData` on every call, so
  `removeObservers(this)` on a fresh one detaches nothing. The gallery grid no longer uses
  LiveData for this reason; the albums observer is guarded by a one-shot flag. Hold the
  `LiveData` in a field if you ever need to detach one.
- Never use single-shot `Cipher.doFinal` on media. Android's AES-GCM buffers the whole input,
  so a 64 MB file already runs a 256 MB heap out of memory. Use `VaultCrypto` streams;
  `VaultCryptoTest` pushes 256 MB through a 128 MB test heap to keep this honest.
- `SecurityHelper.getEncryptedPrefs` throws rather than falling back. A plain-prefs fallback
  once made the app mint a new data key and store it in the clear.
- `allowBackup` is off and `data_extraction_rules.xml` excludes everything: Keystore-bound vault
  files are useless on another device, and the database holds lock values.
- `CloudSync` holds a process-wide lock for every mutating call. A restore started while a long
  backup runs waits ("Waiting for the backup in progress"); that is intended.
- Release builds still have **no signing config**. If the release key ever changes, updates
  fail and users must uninstall, which deletes the Keystore key and with it every local vault
  file. Keep one release keystore, backed up, forever.
- Google sign-in needs the signing key's SHA-1 registered in the Firebase project
  (`notes-plus-88985`), and `app/google-services.json` re-downloaded after adding it. A new
  release key means adding its SHA-1 too, or sign-in fails for that build only.
- Gradle needs a full JDK: `JAVA_HOME="D:\Program Files\Java\JDK\jdk-21.0.8+9" ./gradlew ...`
  The IDE-bundled JRE has no `jlink` and the build fails on `androidJdkImage`.
- Two `file_paths.xml` exist; only `app/src/main/res/xml/file_paths.xml` is real.
- `app/src/main/xml/`, `drive_report.txt`, and `app/release/Notes+.apk` are stale artifacts.

## UI

Material 3 `DayNight` with an iOS-flavoured palette. Colours live in `values/colors.xml`
with a `values-night` counterpart — **use the semantic names** (`@color/app_background`,
`@color/text_primary`, `@color/text_secondary`, `@color/divider`, `@color/brand_yellow`),
never a raw hex, or the screen breaks in dark mode. There is no `values-night/themes.xml`
on purpose: it used to redefine the base theme without the button style.

Known and still open: white on `@color/brand_yellow` is ~1.5:1 and fails contrast —
`@color/on_brand_yellow` is the single value to change. Strings are still hardcoded in
layouts (`strings.xml` has only `app_name`), there is no edge-to-edge inset handling despite
targetSdk 36, and almost nothing has a `contentDescription`.
