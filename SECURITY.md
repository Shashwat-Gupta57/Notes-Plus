# Security Policy

## Supported versions

Only the latest release gets security fixes. It is listed on
[GitHub Releases](https://github.com/Shashwat-Gupta57/Notes-Plus/releases) and in
[`version.json`](version.json); the apps offer it as an update.

## Reporting a vulnerability

**Please don't open a public issue.** Report it privately through GitHub:
**Security → Report a vulnerability** on this repository
([direct link](https://github.com/Shashwat-Gupta57/Notes-Plus/security/advisories/new)).

Include which app and version, what an attacker could do, and how to reproduce it. You'll get a
reply within a week; once a fix is released, you'll be credited in the advisory unless you'd rather
not be.

## In scope

- Anything that lets someone read, change or delete another user's notes, profile or shares.
- Ways around note locks, the private spaces' encryption or their lockout.
- The database security rules, the update channel (`version.json`, download verification), and
  the website and web app.

Out of scope: problems needing a rooted or already-compromised device, social engineering, and
denial of service against the free hosting providers.
