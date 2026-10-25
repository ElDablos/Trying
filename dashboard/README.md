# Crimson Widget

One dark red home-screen widget that replaces separate clock, calendar, weather and step widgets.
It matches the [Gothic Crimson theme](../theme/gothic-crimson/README.md).

![Preview (approximate)](preview.png)

| Part | Shows | Tap it to |
| --- | --- | --- |
| Clock | Time, your next alarm and the date | Open the alarm list in Clock, to switch alarms on or off |
| Weather | Temperature, conditions, today's high and low | Open your Weather app |
| Galaxy Buds (only while connected) | Left and right earbud battery, and an **ANC / Ambient** switch | A mode switches to it · elsewhere on the row reconnects |
| Music | The song playing (album art, title, artist), a queue button, and shuffle, previous, play/pause, next and repeat (off → all → one) | Art or title opens the music app · queue pops up what's next (tap a song to jump to it) · play with nothing playing resumes Spotify |
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
   - **Music**: tap **Allow music access** and switch Crimson Widget on. If the switch is greyed out, tap
     **Switch greyed out? Unlock it**, then **⋮ → Allow restricted settings**, and try again.
   - **Galaxy Buds**: tap **Allow Bluetooth**. If you have more than one pair, tap **Earbuds** and pick yours.
   - **Widget background**: drag the slider to make the background more see-through or more solid.
4. Tap **Add widget to home screen**. It's 6×5 by default. Long-press it to resize.

## Crimson Glance (lock screen)

A second, slim widget: one row with the weather, steps, your Buds' battery and mode (only while they're
connected; tap to switch between ANC and Ambient) and your next alarm. Add it to the lock screen from
**Settings → Lock screen and AOD → Widgets** (if your phone lists it there), or to the home screen like any
widget. It uses the same settings as the main widget, including the background slider.

## Crimson App Strip

A 6x1 row of apps that works like a folder. Long-press the home screen → Widgets → Crimson Widget →
**Crimson App Strip**, then tap apps in the order you want them (up to 30). Six show at a time; swipe the
strip up or down to flip to the next six, and it loops back round. Icons come from Crimson Icons when it's
installed. To change the apps later, long-press the strip → **Settings**. Each strip keeps its own apps, and
the background follows the same slider as the main widget.

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
