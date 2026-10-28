# Notes+ Privacy Policy
**Version 15.5 | Effective: October 2026**

Flex Studios ("we", "our", or "us") operates Notes+ across the Web (notes.talken.in), Android, and Windows Desktop. We design our applications around a fundamental principle of zero-knowledge privacy.

---

### 1. Information We Collect
1. **Account Identification**: When signing in via Google OAuth or Email/Password, we receive standard identity tokens: Firebase User ID (UID), Email Address, Display Name, Profile Picture URL, and claimed `@username` handle.
2. **Platform & Diagnostics**: Operating system, platform architecture (Android, Windows, Web), client application version, and active theme preference.
3. **Heartbeat & Activity Signals**: Periodic 600-second update checks that record an incremented daily counter per UTC calendar date (`checks: { [date]: count }`). **No individual check timestamps are retained.**
4. **Google Drive Integration**: When connected, your Google Drive token is used strictly to read and write your own encrypted notes media, sketches, and audio attachments in your personal Google Drive storage. We never access, read, or modify files outside of the dedicated application folder.

---

### 2. Zero-Knowledge Content Security
* All notes, lists, mind maps, journals, sketches, audio notes, and vaults are protected with client-side AES-256-GCM encryption with PBKDF2 key derivation.
* Decryption keys, passwords, patterns, and PINs are computed locally and are never transmitted to our servers.
* Server-side systems only store encrypted blobs and synchronization metadata. No administrator or third party can read or decrypt your note contents.

---

### 3. Usage of Telemetry Data & Failure Semantics
* Telemetry increments **only when an update check is successfully completed**. Failed requests due to offline status or network errors are discarded silently without retry loops or penalty counters.
* The calendar date is authenticated via the server's Network Time System (HTTP `Date` response header in UTC), preventing local clock tampering.
* Aggregate usage hours are approximated purely as $\text{checks} \times 10\text{ minutes}$.

---

### 4. User Rights, Opt-Out & Account Deletion
* **Telemetry Opt-Out**: Users may disable heartbeat telemetry at any time in application settings.
* **Account Deletion**: Users may inspect their profile metadata or permanently delete their account and associated cloud sync records via Account Settings.
* **No Sale of Data**: We do not sell, rent, or monetize personal information, metadata, or telemetry.
