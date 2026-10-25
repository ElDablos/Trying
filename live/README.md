# Crimson Live

An animated 3D live wallpaper in the Gothic Crimson style: a stained-glass rose window glowing and slowly
turning on a carbon-fibre wall, glossy black bands rippling in front of it, pulses of light racing along a
crimson neon line, and embers drifting up through the scene.

![Preview](src/main/res/drawable-nodpi/preview.jpg)

## Install

1. Download `CrimsonLive.apk` from this repo's **Releases → Latest builds** and install it.
2. Open **Crimson Live** and tap **Set as wallpaper**.
3. Choose **Home screen** or **Home and lock screens**.

Tilt your phone and the scene moves with real depth: the glossy bands slide across the rose window behind
them. The **Tilt effect** slider in the app sets how strong that is, or turns it off. Swiping between home
screen pages pans it slightly too.

It's a 12-second seamless loop played by the phone's video hardware. Video, drawing and the motion sensor
all stop whenever the wallpaper is hidden (apps open, screen off), so it only uses power while you can see it.

## How it's made

The animation is rendered offline in 3D with three.js, so it can use effects a phone couldn't run live all
day: glossy reflections, bloom and hundreds of particles. The scene is
[`theme/gothic-crimson/src/3d/live.html`](../theme/gothic-crimson/src/3d/live.html). Every motion repeats
exactly every 12 seconds, so the last frame joins the first with no jump.

Each frame is rendered as three layers, packed side by side into one 3240×2340 video: the background (wall
and rose window), the foreground (bands, neon and embers over black) and a mask of the bands. The scene is
rendered 12% larger than the screen. On the phone, `LayerRenderer` splits the layers apart with OpenGL and
stacks them, shifting the foreground about three times as far as the background as the phone tilts.

To re-render the video (needs Node, Playwright with Chromium, and ffmpeg):

```
cd theme/gothic-crimson/src/3d
npm install
node render-live.js            # writes live/src/main/res/raw/crimson_live.mp4
node render-live.js --preview  # just four stills, for checking changes quickly
```
