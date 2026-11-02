# Social Watch Space API — local showcase

This directory is a self-contained, static presentation layer for the backend
project in the parent directory. It is designed for a 16:9 screen recording and
does not represent, or connect to, a production frontend. It makes no API calls;
the scene copy is loaded from [`scenes.json`](./scenes.json), whose claims are
based on the repository [`README.md`](../README.md).

## Preview locally

From `/mnt/d/Netflix AI`, run:

```bash
python3 -m http.server 4173 --directory showcase
```

Open <http://localhost:4173> in a browser. Serving the directory is required so
the browser can load `scenes.json`; opening `index.html` directly with `file://`
will not load the scene source in browsers that block local fetches.

## Presentation controls

- Autoplay advances every 7.6 seconds. Use **Space** to pause or resume.
- Use **Left / Right arrows** for the previous or next scene.
- Use **Home / End** to jump to the first or last scene.
- The transport buttons and scene progress labels are keyboard and screen-reader
  accessible.
- The motion automatically reduces when the operating system requests reduced
  motion.

## Capture guide

1. Open the local URL in a clean browser window at a 16:9 size, such as
   `1920×1080` or `1600×900`.
2. Hide browser chrome or use a browser/app-window capture in OBS, QuickTime,
   or another local recorder.
3. Start on scene 01, confirm the **STATIC SHOWCASE** indicator is visible, and
   press Space if you want to hold a scene while narrating.
4. For an unattended pass, leave autoplay running. A full loop takes about
   76 seconds; use the progress rail or arrow keys to re-record individual
   scenes.
5. Capture system audio only if narration or music is added separately; this
   showcase ships without audio and without remote assets.

## Rendered artifact

The generated silent H.264 showcase is available at:

```text
showcase/social-watch-space-showcase.mp4
```

It is 1920×1080, 30 fps, and approximately 77 seconds long. It was rendered
from the local page with headless Chromium and converted with FFmpeg. It has no
narration or music; add those as a separate, reviewed media track if needed.

## Files

- `index.html` — accessible presentation shell and controls.
- `styles.css` — local cinematic styling, 3D perspective cards, ambient grid,
  particles/glow, responsive fallback, and reduced-motion behavior.
- `app.js` — scene loading, rendering, autoplay, progress state, and keyboard
  controls.
- `scenes.json` — structured, auditable scene copy and backend claims.
