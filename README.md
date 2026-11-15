# Buds Battery

An Android home-screen widget for Samsung Galaxy Buds that shows:

- **Left, right and case battery**, kept up to date while the buds are connected
- **Listening-mode buttons**: Off / ANC / Ambient (only the modes your model supports appear)

It talks to the buds directly over Bluetooth, using the protocol documented by the
open-source [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) project.
You don't need the Galaxy Wearable app open.

## Install

1. On your phone, open this repo's **Releases** page and download `BudsBattery.apk` from
   **Buds Battery (latest build)**. You can also grab it from the latest run on the **Actions** tab.
2. Open the APK. Android will ask you to allow installs from your browser or file manager the first time.
3. Open **Buds Battery**, allow Bluetooth access, pick your buds and tap **Connect**.
4. Tap **Add widget to home screen**, or long-press the home screen → Widgets → Buds Battery.

## How it works

- When your buds connect, the app opens a Bluetooth link to them in a small background service.
  The service shows a quiet notification with the battery levels.
- The buds push battery updates on their own, so the widget doesn't poll.
- Tapping a mode button sends the change straight to the buds.
- Tapping anywhere else on the widget reconnects if the widget says it's disconnected.

## Supported models

| Model | Modes |
| --- | --- |
| Buds2, Buds2 Pro, Buds Pro, Buds FE, Buds3, Buds3 Pro | Off, ANC, Ambient |
| Buds Live | Off, ANC |
| Buds+, Buds (2019) | Off, Ambient (the 2019 Buds don't report case battery) |

## Troubleshooting

- **Stuck on "Connecting…"**: the Galaxy Wearable app or its Buds plugin may be holding the
  Bluetooth link. Force-stop it in Settings → Apps, then tap the widget.
- **Doesn't connect on its own after the buds connect**: newer Android versions sometimes block
  apps from starting in the background. Tap the widget once to connect.
- **Battery shows `--`**: the buds haven't reported yet. Take one out of the case or tap the widget.

## Build it yourself

```
./gradlew assembleDebug
```

Every push to GitHub builds the APK automatically (`.github/workflows/build.yml`).

---

# Home Controls

A second app in this repo: a home-screen widget for a **Tapo colour bulb**, styled to match Crimson.

- A big reactor-style power dial that glows in the bulb's current colour, with a brightness gauge round it
- A 10-step brightness bar (tap a step to jump to it) and your colour presets ("scenes") read from the
  bulb itself, with target brackets round the one that's on
- A small **TAPO ↗** button that opens the Tapo app

Like the Crimson Money widget, it's drawn as one picture (`LightArt`) with see-through tap areas laid
over it in the same proportions.

It talks to the bulb directly over your home Wi-Fi. There's no cloud and no hub, so it only works
while your phone is on that Wi-Fi.

## Install

1. Download `HomeControls.apk` from the **Latest builds** release on this repo's Releases page and open it.
2. Open **Home Controls**, enter your Tapo email and password, tap **Find** and pick your light, then **Test**.
3. Tap **Add widget to home screen**.

## How it works

TP-Link's local "KLAP" protocol, as documented by the open-source
[python-kasa](https://github.com/python-kasa/python-kasa) project. Widget taps run as a short
foreground task, because Android blocks network access for apps in the background.

A Philips Air+ heater (CX5120) was tried too, but its firmware answers the local handshake and
then ignores status requests and commands, so it was dropped. Use Google Home for it instead.

## Troubleshooting

- **Light says the email/password weren't accepted:** in the Tapo app go to
  Me → Third-Party Services and turn on **Third-Party Compatibility**.
- **"Can't reach…":** check your phone is on home Wi-Fi. If your router restarted, the bulb's
  IP may have changed: open the app and tap **Find** again. Reserving the IP in your router stops this.
