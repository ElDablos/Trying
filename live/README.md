# Crimson Live

An animated 3D live wallpaper in the Gothic Crimson style: a stained-glass rose window glowing and slowly
turning on a carbon-fibre wall, glossy black bands rippling in front of it, pulses of light racing along a
crimson neon line, and embers drifting up through the scene.

![Preview](src/main/res/drawable-nodpi/preview.jpg)

## Install

1. Download `CrimsonLive.apk` from this repo's **Releases → Latest builds** and install it.
2. Open **Crimson Live** and tap **Set as wallpaper**.
3. Choose **Home screen** or **Home and lock screens**.

It's a 12-second seamless loop played by the phone's video hardware, and it pauses whenever the wallpaper
is hidden (apps open, screen off), so it only uses power while you can see it.

## How it's made

The animation is rendered offline in 3D with three.js, so it can use effects a phone couldn't run live all
day: glossy reflections, bloom and hundreds of particles. The scene is
[`theme/gothic-crimson/src/3d/live.html`](../theme/gothic-crimson/src/3d/live.html). Every motion repeats
exactly every 12 seconds, so the last frame joins the first with no jump.

To re-render the video (needs Node, Playwright with Chromium, and ffmpeg):

```
cd theme/gothic-crimson/src/3d
npm install
node render-live.js            # writes live/src/main/res/raw/crimson_live.mp4
node render-live.js --preview  # just four stills, for checking changes quickly
```
