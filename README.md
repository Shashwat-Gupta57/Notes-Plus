# Notes+ website and web app

This is the `web` branch of [Notes+](https://github.com/Shashwat-Gupta57/Notes-Plus), served by
GitHub Pages at **[notes.talken.in](https://notes.talken.in)**: the website, the docs, and
Notes+ on the web under [`/app/`](https://notes.talken.in/app/).

Everything is static: plain HTML, CSS and ES modules, no build step and no framework.
Serve the folder and open it:

```bash
python -m http.server 8000      # then http://localhost:8000
```

| Path | What |
|---|---|
| `index.html`, `download.html`, `docs.html`, `privacy.html` | The website |
| `css/masterpiece.css`, `js/` | Site styles and scripts (`paths.js`, `particles.js`, `telemetry.js`, `version.js`) |
| `app/` | Notes+ on the web (PWA): `app/js/` holds the app's modules, `app/sw.js` the offline cache |

Pushing to `web` publishes the site immediately. Read
[CONTRIBUTING.md](https://github.com/Shashwat-Gupta57/Notes-Plus/blob/master/CONTRIBUTING.md) before
opening a pull request (against `web`).

Notes+ is free software under the [GNU General Public License v3.0](LICENSE).
