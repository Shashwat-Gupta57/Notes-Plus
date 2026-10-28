# Notes+ Data & Telemetry Statistics Disclosure
**Version 15.5 | Effective: October 2026**

This document provides complete, transparent disclosure regarding automated telemetry, software update verification, and active usage statistics collected by Notes+.

---

### 1. The 600-Second Heartbeat & Update Checker
Notes+ client applications (Android, Windows Desktop, and Web) routinely query the release distribution endpoint (`https://releases.talken.in/version.json` and sync servers) every 600 seconds (10 minutes) during active foreground execution.

---

### 2. Increment Rules & Failure Handling
1. **Execution Timing**: The daily check counter increments **only upon successful completion** of the update check (HTTP 200 and valid JSON parse). It does **not** increment when an update is scheduled or merely attempted.
2. **Failures & Offline Handling**: If a network error, DNS resolution failure, or server timeout occurs, the request simply fails silently. The counter does **not** increment, no failure count is stored, and the client does not retry aggressively—it simply waits until the next scheduled 600-second cycle.
3. **Multi-Instance Coordination**:
   * **Windows Desktop**: Enforces a single-instance process lock; secondary launches delegate focus to the running instance, preventing duplicate checks.
   * **Android**: Uses single-task activity mode with lifecycle-aware execution (checks stop when the app is backgrounded or killed).
   * **Web Browser**: Coordinates across tabs via a shared `localStorage` time-barrier lock (`np_last_update_check`). If multiple tabs are open simultaneously, only one instance checks within any 600-second window.

---

### 3. Date Attribution & Network Time System (NTS)
* To prevent clock tampering, device timezone shifts, or calendar rollover anomalies across midnight, the calendar date (`YYYY-MM-DD`) is derived directly from the server's **Network Time System (HTTP `Date` response header in UTC)** rather than the local device clock.
* When the UTC calendar day rolls over while the application remains open, the next successful 600-second check seamlessly attributes to the new UTC calendar date.

---

### 4. Exact Database Schema & Non-Retention of Timestamps
In accordance with zero-knowledge and privacy-first principles:
1. **No Individual Timestamps**: We **never** store per-check timestamps. The system records only the date string and an integer counter:
   ```json
   "checks": {
     "2026-10-27": 14
   }
   ```
2. **No IP Storage in User Profiles**: The user registry document (`stats/users/${uid}`) stores only non-PII operational fields:
   * Identity tokens: `uid`, claimed `@username`, `displayName`, `photoUrl`, `email`.
   * Environment diagnostics: `platform`, `version`, `os`, `theme`.
   * Activity metrics: `lastActiveDate`, `totalChecks`, and daily count dictionary `checks: { [date]: count }`.
3. **Usage Estimation**: Estimated active usage is calculated purely as:
   $$\text{Estimated Active Duration} = \text{checks}_{\text{today}} \times 10\text{ minutes}$$
   This estimates active foreground usage without screen recording, keylogging, or process surveillance.

---

### 5. Zero-Knowledge Content Security
The update verification mechanism strictly transmits client metadata. It **never** reads, inspects, or transmits:
* Note titles, text, lists, or mind maps
* Audio notes or voice recordings
* Photos, media attachments, or drawing canvas paths
* Master encryption keys, PINs, or passphrases

---

### 6. User Control & Opt-Out
Users retain full control and may disable telemetry heartbeats at any time through application settings (`telemetry_opt_out`).

---

### 7. Website & Web App Visit Logs
Separately from the apps' update checks, the website (notes.talken.in) and Notes+ on the web log each page view (once per browser session per page) at `stats/events`, readable only by administrators:
* **Network**: IP address, and the city, region, country and ISP it maps to (looked up through ipapi.co, falling back to ipify.org for the address alone).
* **System**: operating system, browser and version, device type, screen size, language, time zone.
* **Visit**: page, referring site (or `utm_source`), time on page (written when the tab is hidden), and a random visitor ID in `localStorage` (`np_vid`) that tells new visitors from returning ones.
* Download clicks are logged the same way. Sign-ins to the admin panel, allowed or refused, are logged with the same fields at `stats/adminAccess`.
* Old events are pruned by the administrator. Clearing the site's data in your browser resets the visitor ID.
