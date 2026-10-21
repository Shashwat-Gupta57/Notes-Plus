# Cloud backup & sync — audit and rebuild plan

Status: **implemented, untested on device.** Parts 1-7 landed, including the Part 2 screens:
`DriveSetupActivity` (2.1), the status/progress card (2.2), the restore-point list with
pinning (2.3), the restore flow (2.4) and `SyncChoiceActivity` (2.5). `GoogleDriveManager`
and `MediaMapManager` are deleted.

Everything is compile-verified only. No backup/restore round trip, re-key pass, or
`ObjectIndex.rebuild` adoption has been exercised against a real Drive account.

---

# Part 1 — Audit of the current implementation

Scope: `GoogleDriveManager`, `SyncWorker`, `MediaMapManager`, and the Drive call sites in
`GallerySettingsActivity` and `SetupLockActivity`.

## 1.1 Data loss

**D1 — A fresh install wipes the cloud backup.** `MediaMapManager.getFilesToDeleteFromCloud`
deletes every cloud file absent from the local map, and `syncUnsyncedFilesDirect` runs that
pass unconditionally. On a new device the local map is empty, so the first sync deletes
everything. `syncBackgroundsWithMap` does the same for backgrounds. The backup destroys
itself precisely when it is needed.

**D2 — Sync only ever pushes.** Despite the name, `syncUnsyncedFilesDirect` is upload +
delete with no download leg, so the device is implicitly authoritative — which is what makes
D1 fatal rather than merely wrong.

**D3 — Restore dies on any item with no `originalPath`.** `JSONObject.put(key, null)`
*removes* the key, so a null `originalPath` or `albumId` never reaches the JSON. Restore then
calls `so.getString("originalPath")`, throws, and the whole `commitMapToDatabase` transaction
rolls back. `getActualPath()` reads `MediaStore.MediaColumns.DATA`, which is null for most
non-MediaStore providers on Android 10+, so this is common rather than exotic.

**D4 — Vault remapping matches on display name.** `createIdMapping` pairs cloud vaults to
local vaults by `name`. Duplicates map wrongly; a renamed vault maps to nothing and its media
is committed under a `vaultId` that does not exist locally — downloaded but invisible.

**D5 — Video backgrounds are silently dropped.** `generateBackgroundMap` filters
`!f.getName().endsWith(".mp4")`. Never uploaded, never restored, no message.

**D6 — A read path deletes files.** `findFileId` sorts same-named results and *deletes* all
but the newest. It is called from `getOrCreateFolder`, the delete pass and the download loop.
Two concurrent syncs delete each other's in-flight uploads.

**D7 — `findFileGlobally` ignores its `rootId`** and searches the whole Drive by bare name,
returning the first hit. It drives the cloud delete pass.

## 1.2 Correctness

**C1 — Drive queries built by concatenation.** `"name = '" + name + "'"` breaks on an
apostrophe; `dirName` comes from a cloud-supplied map, so it is not fully trusted.

**C2 — Same-size files never re-upload.** `updateOrUploadFile(..., force=false)` compares
remote size to local length.

**C3 — Backups sit in the user's Drive root** under a five-level visible folder tree.
*Accepted as a trade-off — see decision A.*

**C4 — Folder lookups repeated per item.** `getOrCreateFolder("vault_" + id, …)` inside the
download loop and again in `performVerificationPass`. Thousands of avoidable round trips.

**C5 — No retry or backoff.** One 429 or 5xx aborts the run; `SyncWorker` returns `retry()`
and the whole reconcile restarts from scratch.

**C6 — `syncUnsyncedFilesDirect` posts `onError` *and* rethrows.** Callers see it twice.

## 1.3 Crypto and keys

**K1 — The lock value is the encryption key.** Changing the main lock makes every uploaded
file and every `.bak` permanently undecryptable, with no warning and no version byte to even
detect the mismatch. *(Item 10.)*

**K2 — Whole-file `byte[]` in both directions.** Three copies of a video in heap;
`(int)(cloudFile.length() - 28)` is negative for a truncated download and overflows past 2 GB.

**K3 — No format marker.** A wrong key surfaces as a bare `AEADBadTagException`.

**K4 — `settings.json` is uploaded in the clear** with every vault's `lockValue` in it.

## 1.4 Broken flows

**F1 — First-run Drive restore has never worked.** `SetupLockActivity.fetchBackupFromDrive`
downloads plain-JSON `settings.json` and feeds it to `BackupManager.restoreBackup`, which
expects `salt‖iv‖AES-GCM`. Fails 100% of the time. *(Item 11.)*

**F2 — Settings' Drive restore downloads a file and discards it.**

**F3 — `SyncWorker` ignores `KEY_FILE_NAME` / `KEY_VAULT_ID`.** Every enqueue runs a full
reconcile; 50 imports enqueue 50 full syncs, with no unique-work name and no network
constraint, so they run concurrently and race (see D6). *(Item 14.)*

**F4 — The sync spinner is decorative.** `SyncWorker` publishes no progress.

---

# Part 2 — What the user experiences

Design target: someone non-technical, with a 5 GB vault, on mobile data, who has never heard
the word "sync".

## 2.1 Connecting

One screen, once, right after they tap **Connect Google Drive**:

```
┌──────────────────────────────────────────────┐
│  Keep a copy on Google Drive                 │
│                                              │
│  Your photos stay locked. Google can't open  │
│  them — only your PIN can.                   │
│                                              │
│  When should we back up?                     │
│   ◉  On Wi-Fi, automatically      (default)  │
│   ○  On any connection                       │
│   ○  Only when I tap "Back up now"           │
│                                              │
│                        [ Not now ] [ Start ] │
└──────────────────────────────────────────────┘
```

No other question is ever asked unless the two sides genuinely disagree.

## 2.2 Backing up

The settings card is one line of status and one button:

```
   Google Drive · alld.neeraj3@gmail.com

   Last backup   Today, 14:02
   Backed up     4,812 items · 5.0 GB
   To upload     143 items · 312 MB

              [  Back up now  ]
```

During a run it becomes a real progress bar with counts and bytes, driven by
`WorkManager.setProgressAsync`, not the current spin-then-tick animation:

```
   Uploading 37 of 143 · 81 MB of 312 MB
   ▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░  26%
```

**The 312 MB is the whole upload.** The 5.0 GB already in Drive is not touched, re-read or
re-encrypted. That is the point of Part 3.

## 2.3 Restore points

Because a backup is a tiny manifest plus whatever objects were new, keeping several costs
almost nothing. Settings lists them:

```
   Restore points

   ● Today, 14:02      4,955 items · Pixel 7        [ Restore ]
   ○ Yesterday, 21:10  4,812 items · Pixel 7
   ○ 27 Aug, 08:44     4,390 items · Pixel 7
   ○ 12 Aug, 19:02     3,980 items · old phone   📌 kept

   Keeping the last 5, plus anything you pin.
```

Pinning a restore point protects it from cleanup. This is what "multiple backups" buys:
recovery from a mistaken delete, not just from a lost phone.

## 2.4 Restoring

Pick a restore point, then **one** question instead of the current two-dialog chain:

- **Add to what's here** — nothing local is removed. *(default)*
- **Replace everything** — states the exact count it will delete before proceeding.

Only files actually missing locally are downloaded. Restoring onto the same phone after a
crash pulls almost nothing.

## 2.5 When the two sides disagree

The only unavoidable question, shown when the local baseline is empty or contradicts Drive:

```
┌──────────────────────────────────────────────┐
│  This phone and Google Drive don't match     │
│                                              │
│    ┌────────────┐      ┌────────────┐        │
│    │ This phone │      │   Drive    │        │
│    │     0      │      │   4,812    │        │
│    │   items    │      │   items    │        │
│    └────────────┘      └────────────┘        │
│                                              │
│  ◉ Keep everything from both      (safest)   │
│  ○ Download from Drive                       │
│      Replaces the 0 items on this phone.     │
│  ○ Upload this phone                         │
│      Deletes 4,812 items from Drive.         │
│                                              │
│                      [ Cancel ] [ Continue ] │
└──────────────────────────────────────────────┘
```

"Upload this phone" is the only destructive option, names its exact cost, and needs a second
confirm. A fresh install therefore can no longer silently wipe Drive (D1).

## 2.6 Errors in plain words

`"Google Drive is full — free up space and we'll finish."`
`"You're offline. We'll pick up where we left off."`
`"That PIN doesn't open this backup."`

Never an exception message. `SyncWorker` distinguishes offline/quota/auth/transient and only
the last one silently retries.

---

# Part 3 — Object model: why the upload is 312 MB and not 5.3 GB

## 3.1 The problem with the current shape

Today a "backup" is a single mutable mirror of the device, and a local `.bak` is a monolithic
zip. There is no notion of "already stored", so anything that isn't a bare per-file existence
check re-reads and re-writes everything.

## 3.2 Content objects + snapshot manifests

Split immutable data from the tiny description of a backup point.

```
Notes-plus/Saved-Notes/Cloud/Remote/Backup/
  keyring.json                 wrapped data keys — plaintext by design, ~200 bytes
  objects/
     index.json                name → {driveId, size, sha256}   (encrypted)
     <fileName>                immutable encrypted media blob
  backgrounds/<dir>/<file>     encrypted, includes .mp4 now  (fixes D5)
  snapshots/
     index.json                the restore-point list          (encrypted)
     <snapshotId>.json         one manifest per restore point  (encrypted)
  settings.json                vault/album config              (encrypted — fixes K4)
```

Vault media is **write-once**: `SecretEntity.fileName` is `IMG_<millis>_<index>`, assigned at
import and never reused, and the bytes behind it never change. So the file name is already a
sound immutable object key — no content hashing is needed to get incrementality.

A backup then becomes:

```
objectsToUpload = localFileNames − objectsAlreadyInStore
```

5 GB stored, 300 MB new → 300 MB uploaded, plus a manifest in the low hundreds of KB. Nothing
already present is read, encrypted or transmitted again.

## 3.3 The store index — one API call instead of five thousand

The naive version of the set difference above costs one `files.list` per object. `objects/index.json`
holds the whole inventory, so a run does:

1. download `objects/index.json` — **1 call**
2. diff against the local DB in memory — **0 calls**
3. upload only the difference — *N* calls, *N* = genuinely new files
4. rewrite `index.json` — **1 call**

This also removes C4 (per-item folder lookups) and makes C2 moot, since the index carries
size and hash rather than inferring "unchanged" from a size comparison.

If a run dies mid-way the index can disagree with reality. Recovery: a `generation` counter,
and a rebuild path that pages `objects/` once (`listAllFiles` already pages correctly) and
regenerates the index. Rebuild is also what a brand-new device does before its first sync.

## 3.4 Snapshots are cheap

`snapshots/<id>.json`:

```json
{
  "formatVersion": 2,
  "id": "20260830T140200Z-a1b2",
  "createdAt": 1756562520000,
  "device": "Pixel 7",
  "vaults":  [ { "uuid": "…", "name": "Main Vault", "sortOrder": 0, … } ],
  "albums":  [ { "uuid": "…", "name": "Trip", "vaultUuid": "…" } ],
  "media":   [ { "fileName": "IMG_1756…_0", "vaultUuid": "…", "albumUuid": null,
                 "isVideo": false, "dateTaken": …, "dateAdded": …,
                 "originalPath": null, "isFavourite": false, "sha256": "…" } ]
}
```

Every entry references an object by name; none of them contains bytes. 5,000 items is roughly
1 MB of JSON, compressed and encrypted. Ten restore points therefore cost ~10 MB on top of the
media, all sharing the same object store.

Note `vaultUuid` / `albumUuid`. A new `uuid` column on vaults and albums replaces name-matching
across devices, which is D4.

`sha256` is recorded **while the object is being streamed up**, so it costs no extra read. Older
items simply carry `null` and skip integrity verification rather than forcing a 5 GB re-read.

## 3.5 Deletion and cleanup

Retention: keep the newest 5 snapshots plus every pinned one.

Garbage collection, after a successful backup:

```
live      = union of media[].fileName over all retained snapshots
orphans   = index.keys − live
delete orphans, then rewrite index.json
```

An object is only deleted when **no retained restore point references it**. A local delete
therefore stops removing anything from Drive immediately — it drops out of the next snapshot,
and disappears for good once the last snapshot naming it ages out. That is a second,
independent guard against D1, and it means "I deleted the wrong album" is recoverable for as
long as a restore point survives.

## 3.6 Reconciliation for two devices

Snapshots alone cover backup/restore. Continuous two-device sync additionally needs to know
whether a local absence means "deleted here" or "never seen here". Local baseline
`filesDir/last_synced_manifest.json` — the manifest as of the last successful run:

| local | baseline | store | action |
|---|---|---|---|
| yes | no  | no  | upload — new here |
| no  | yes | yes | record deletion — genuinely deleted here |
| no  | no  | yes | download — new on another device |
| yes | yes | no  | re-upload — object missing remotely |
| no  | yes | no  | prune from baseline |

A deletion is only ever propagated when the baseline proves this device once had the file. A
fresh install has an empty baseline, so no path leads to a delete. Combined with §3.5, D1 is
structurally impossible rather than merely unlikely.

## 3.7 Local `.bak` stays monolithic — and why

A `.bak` is a single portable file you can put on a pen drive; that is its whole value, and a
single file cannot be incremental. It keeps the full-copy cost, but gains streaming crypto so
it stops OOM-ing (item 7). Incrementality is a property of the Drive store, and the UI should
say so plainly: *"Google Drive — only new items are uploaded"* versus *"Backup file — a
complete copy, every time"*.

---

# Part 4 — Wire formats

## 4.1 Encryption

All payloads use the versioned envelope from `BackupCrypto`:

```
v2:  'N' 'P' 0x02 | keyId(8) | iv(12) | AES-GCM ciphertext
v1:  salt(16) | iv(12) | AES-GCM ciphertext        (legacy, read-only, no magic)
```

Payload key is the **Vault Data Key**, not the lock. `keyring.json` holds one PBKDF2-wrapped
copy of the VDK per distinct vault lock, so any vault's password opens the backup and a
recovery code can later be added as just another wrap.

Changing a lock rewrites `keyring.json` — about 200 bytes — and touches nothing else. That is
item 10.

`objects/index.json`, `snapshots/*`, and `settings.json` are all v2-encrypted, which closes
K4: Drive no longer holds vault lock values in the clear.

## 4.2 Compatibility

Readers accept v1 and v2; writers only emit v2. Existing backups keep working. `keyring.json`
absent ⇒ the store predates this release ⇒ treat every object as v1 and require the legacy
lock value.

---

# Part 5 — Migration and rollout

**Release A — foundations.** *(landed)* `BackupCrypto`, `VaultKeyManager`. VDK generated on
first launch. Readers accept both formats.

**Release B — the store.** Object store, index, snapshots, GC, reconcile, new `SyncWorker`,
the screens in Part 2. On the first run the app rebuilds `objects/index.json` from whatever is
already in `vault_<id>/` folders and adopts those files as objects, so **no re-upload of
existing data** — the 5 GB already in Drive is inherited, not re-sent.

**Legacy re-key.** A lock change is refused while any v1 payload is still reachable, and the
user is offered:

1. **Re-key the Drive backup** — resumable, one object at a time.
2. **Re-key a backup file I choose** — any `.bak` from anywhere on the device, decrypted with
   its old lock and rewritten as v2.
3. **Make a fresh keyed backup instead** — mints a new v2 backup so the old files can simply
   be deleted rather than converted. Recommended for a small vault.

All three end at the same "no v1 payloads remain" state, which unblocks lock changes for good.

**Kill switch.** A device that meets a `formatVersion` newer than it understands refuses to
sync rather than guessing.

---

# Part 6 — Failure handling

- **Resumability.** Objects are independent, so a failed run leaves a consistent store: the
  index simply lists fewer objects. The next run re-diffs and continues. No partial-snapshot
  state is ever published, because `snapshots/<id>.json` is written **last**, after every
  object it references has uploaded.
- **Retry.** Exponential backoff on 429/5xx inside the store; `Result.retry()` only for
  transient failures. Auth failure → `Result.failure()` plus a "reconnect Drive" prompt.
  Quota exhausted → `failure()` plus a plain-language message.
- **Concurrency.** `enqueueUniqueWork("np-drive-sync", APPEND_OR_REPLACE)` so 50 imports
  coalesce into one run and two runs never overlap. This plus the removal of the destructive
  `findFileId` (D6) ends the mutual-deletion race.
- **Untrusted input.** Drive query literals escaped (C1); every manifest field read with
  `isNull`/`optString` (D3); one malformed entry is skipped and counted, never fatal.

---

# Part 7 — Implementation order

| # | Unit | Fixes |
|---|---|---|
| 1 | `BackupCrypto`, `VaultKeyManager` — *landed* | K1 K2 K3 |
| 2 | Migration 8→9: `uuid` on vaults/albums, `contentHash` on secrets | D4 |
| 3 | `DriveStore` — auth, folder cache, escaped queries, retry, object put/get, index | C1 C4 C5 D6 D7 |
| 4 | `SnapshotManager` — build/read/retain/GC manifests | D3 D5 §3.4 §3.5 |
| 5 | `CloudSync` — backup / restore / reconcile, replaces `GoogleDriveManager` internals | D1 D2 C2 C6 |
| 6 | `SyncWorker` — unique work, constraints, progress, honour action | F3 F4 |
| 7 | `SyncChoiceActivity` + `activity_sync_choice.xml` | §2.5 |
| 8 | Restore-point list + restore flow in settings | §2.3 §2.4 F2 |
| 9 | `SetupLockActivity` rewired to the real restore path | F1 |
| 10 | Re-key migration screen, 3 routes | Part 5 |
| 11 | `BackupManager` switched to `BackupCrypto` streaming | item 7, item 8 |

## Decisions taken

**A. Drive location — stay in the visible `Notes-plus/Saved-Notes/Cloud/Remote/Backup` tree.**
No migration, and every backup made by 7.50 or earlier keeps working. C3 is accepted as a
known trade-off: the app keeps the broader `drive.file` scope.

**B. Legacy re-key — blocking, with the three escape routes above.**
