# Crimson

A dark red, HUD-style set of apps for the Samsung Galaxy S26 Ultra, in the Gothic Crimson look: a whole
home screen with its own assistant, home-screen widgets, an animated live wallpaper and a matching icon pack.

![Crimson Widget](dashboard/preview.png)

## What's in it

| App | What it is | Download |
| --- | --- | --- |
| [Crimson Widget and Launcher](dashboard/README.md) | The home screen (Crimson Launcher), **Jarvis**, the Command Deck, and the home-screen widgets (clock, weather, music, steps, calendar, app strip, lock-screen glance, Arcade) | `CrimsonWidget.apk` |
| [Crimson Live](live/README.md) | An animated 3D live wallpaper: a turning rose window, rippling glossy bands, neon pulses and embers, with real depth as you tilt the phone | `CrimsonLive.apk` |
| [Crimson Icons](icons/README.md) | An icon pack to match: every app's logo on a crimson tile, applied through Good Lock's Theme Park | `CrimsonIcons.apk` |
| [Gothic Crimson theme](theme/gothic-crimson/README.md) | Wallpapers and colours for the whole phone | — |

## Crimson Launcher

The home screen, built into the Crimson Widget app (turn it on with **Use Crimson Launcher as home**):

- **The ring**: your folders as nodes round a core; tap one and its apps fly out in orbit. The core holds
  your dock and searches every app.
- **Its own status bar**, modes for the time of day (Morning, Work, Night Ops), themes that recolour
  everything, and Crimson Weather (a full forecast).
- **Swipes**: down for the Comms Centre (messages and calls), up for the Hive Map (every app), right
  for the Game Hub, left for the **Command Deck**: a live briefing for your day, shifts, notes,
  habits, countdowns, the tube, markets and news, read aloud to you on request.

## Jarvis

An assistant that lives on the home screen's arc-reactor orb. Most of what he does is worked out on the
phone itself.

- **Talk or type**, and he answers out loud with a HUD card and buttons. He keeps the thread ("and
  Friday?", "make it 6:30", "delete it") and Undo takes back what he did.
- **"Hey Jarvis" from any app**, or with the phone locked: a wake word heard on the phone, then a strip
  over whatever you're in that listens and answers. The conversation is the same one you see in his
  panel.
- **Your day**: briefings, shifts, alarms (including runs of alarms before an early shift), notes,
  habits, countdowns, calendar, weather, email, messages, and the tube for your commute.
- **Hands**: with Jarvis Hands switched on in Accessibility he works other apps for you: your Tapo
  lights (on/off, colours, brightness), the Air+ heater (on/off, temperature), Samsung Clock's alarms,
  and phone controls such as locking it or taking a screenshot.
- **He learns**: your routine ("I need to wake up at 5 to start work at 7"), rules ("the 7pm alarm
  always stays on"), your phrasing, your habits and, if you allow it, which apps you use when (never
  while a VPN is on). It's all kept on the phone. His voice gets more familiar the longer you use him.

All of it is described in the [Crimson Widget and Launcher README](dashboard/README.md).

## Install

1. On your phone, open this repo's **Releases** page and, under **Latest builds**, download the APKs you
   want: `CrimsonWidget.apk` (launcher, Jarvis and widgets), `CrimsonLive.apk`, `CrimsonIcons.apk`.
2. Open each one and install it. Android asks you to allow installs from your browser the first time.
3. Follow the setup in each app's README (linked above).

New builds install over the old ones and keep your settings.

## Build it yourself

```
./gradlew assembleDebug
```

Every push to GitHub runs the tests, builds the APKs (`.github/workflows/build.yml`) and puts them on the
**Latest builds** release. The builds are signed with the fixed debug key in `keys/debug.keystore`, so
updates install over the existing apps.
