# Crimson Widget

One dark red home-screen widget that replaces separate clock, calendar, weather and step widgets.
It matches the [Gothic Crimson theme](../theme/gothic-crimson/README.md).

| Part | Shows | Tap it to |
| --- | --- | --- |
| Clock | Time, plus your next alarm underneath | Open your alarms |
| Date | Day and date | Open your calendar on today |
| Weather | Temperature, conditions, today's high and low | Refresh everything now |
| Steps | Today's steps with a red progress bar toward your goal | Open Samsung Health |
| Next event | Your next calendar event, like "Dentist · Tue 10:00" | Open it in your calendar |

## Install

1. On your phone, open this repo's **Releases** page and download `CrimsonWidget.apk` from **Latest builds**.
2. Open the APK and install it. Android asks you to allow installs from your browser the first time.
3. Open **Crimson Widget** and set up:
   - **Weather**: search for your town, or tap **Use my location**.
   - **Steps**: tap **Connect steps**, and allow both **Steps** and **Access data in the background**.
     Samsung Health also has to share steps: in Samsung Health, open the menu → **Settings → Health Connect**
     and allow it to write steps.
   - **Calendar**: tap **Allow calendar**.
4. Tap **Add widget to home screen**. It's 5×3 by default. Long-press it to resize.

## Good to know

- The clock and date update live. Weather and steps refresh every 15 minutes, the most often Android allows.
  Tap the weather to refresh straight away.
- Samsung Health sends steps to Health Connect in batches, so the count can be a few minutes behind the Samsung Health app.
- The clock follows your phone's 12- or 24-hour setting.
- Weather comes from [Open-Meteo](https://open-meteo.com), which is free and needs no account.
