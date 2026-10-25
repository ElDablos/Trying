# Crimson Widget

One dark red home-screen widget that replaces separate clock, calendar, weather and step widgets.
It matches the [Gothic Crimson theme](../theme/gothic-crimson/README.md).

![Preview (approximate)](preview.png)

| Part | Shows | Tap it to |
| --- | --- | --- |
| Clock | Time, your next alarm and the date | Open the alarm list in Clock, to switch alarms on or off |
| Weather | Temperature, conditions, today's high and low | Open your Weather app |
| Galaxy Buds (only while connected) | Left and right earbud battery, and an **ANC / Ambient** switch | A mode switches to it · elsewhere on the row reconnects |
| Steps | Today's steps with a red progress bar toward your goal | Open Samsung Health |
| Month calendar | This month, with today circled in red and a red dot under days that have events | **‹ ›** change month · month name goes back to this month · a day opens it in your calendar |

## Install

1. On your phone, open this repo's **Releases** page and download `CrimsonWidget.apk` from **Latest builds**.
2. Open the APK and install it. Android asks you to allow installs from your browser the first time.
3. Open **Crimson Widget** and set up:
   - **Weather**: search for your town, or tap **Use my location**. Then tap **Weather tap opens…** and
     pick your weather app (on Samsung phones, **Weather**).
   - **Steps**: tap **Connect steps**, and allow both **Steps** and **Access data in the background**.
     Samsung Health also has to share steps: in Samsung Health, open the menu → **Settings → Health Connect**
     and allow it to write steps.
   - **Calendar**: tap **Allow calendar** to get the event dots. The calendar works without it.
   - **Galaxy Buds**: tap **Allow Bluetooth**. If you have more than one pair, tap **Earbuds** and pick yours.
   - **Widget background**: drag the slider to make the background more see-through or more solid.
4. Tap **Add widget to home screen**. It's 6×4 by default, the same space as your current calendar, weather, steps and clock widgets. Long-press it to resize.

## Good to know

- The clock and date update live. Weather and steps refresh every 15 minutes, the most often Android allows.
  Opening the Crimson Widget app also refreshes them.
- Samsung Health sends steps to Health Connect in batches, so the count can be a few minutes behind the Samsung Health app.
- The Buds row uses the same direct Bluetooth connection as the Buds Battery app, so don't run both at once.
  If the row says "tap to connect" and tapping doesn't help, the Galaxy Wearable app may be holding the
  connection: close it and tap again.
- Tapping a gap in the widget does nothing, so a near-miss won't open anything.
- The clock follows your phone's 12- or 24-hour setting, and weeks start on the day your phone's region uses (Monday in the UK).
- Home-screen widgets can't be swiped sideways, because the home screen uses that swipe to change pages. That's why the calendar uses arrows.
- Weather comes from [Open-Meteo](https://open-meteo.com), which is free and needs no account.
