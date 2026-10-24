# Buds Battery

An Android home-screen widget for Samsung Galaxy Buds that shows:

- **Left, right and case battery**, kept up to date while the buds are connected
- **Listening-mode buttons**: Off / ANC / Ambient (only the modes your model supports appear)

It talks to the buds directly over Bluetooth, using the protocol documented by the
open-source [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) project.
You don't need the Galaxy Wearable app open.

## Install

1. On your phone, open this repo's **Releases** page and download `BudsBattery.apk` from
   **Latest builds**. You can also grab it from the latest run on the **Actions** tab.
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

## Also in this repo

- [Crimson Widget](dashboard/README.md): a dark red home-screen widget with a clock (tap for alarms), weather, steps and your next event.
- [Gothic Crimson theme](theme/gothic-crimson/README.md): matching wallpapers and colours for the Galaxy S26 Ultra.
