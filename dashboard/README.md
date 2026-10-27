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

A folder of apps in rows. Long-press the home screen → Widgets → Crimson Widget → **Crimson App Strip**.
In its setup screen, give it a short name if you like (it shows as a tag on the top-left edge), tap apps
to add them, and hold and drag them in the preview to arrange them. Spots you leave empty stay empty.
The strip shows one app per cell of its width and one row per cell of its height: resize it to anything
from 6×1 down to 1×1, or taller to show more rows, and scroll it up or down for the rows that don't fit.
The preview shows the strip at its home screen size, with those rows faded underneath. Icons come from
Crimson Icons when it's installed. To change the apps or name later, long-press the strip → **Settings**.
Each strip keeps its own apps, and the background follows the same slider as the main widget.

## Crimson Arcade

A 6×5 games widget. Long-press the home screen → Widgets → Crimson Widget → **Crimson Arcade**.

- **Panel**: the selected game, console style: its cover in target brackets, playtime over the last 14
  days, its most-played rank and when you last played it, and **▶ Play**. Under it, a row of recommended
  games (your most played). **⚄** picks a random game.
- **All games**: every game A–Z, with letter tags and NEW badges for games installed this week. Scroll
  it, or tap a letter on the rail to jump.
- Tapping a game, in the grid or the recommended row, only **selects** it; nothing launches until you
  press **▶ Play**. Taps on empty parts of the widget do nothing.
- Playtime needs usage access (Settings → Usage access → Crimson Widget, or **◈ Enable play stats** in
  the panel); without it, the recommendations are your newest games.
- Games are found automatically from the category apps declare. Long-press → **Settings** to add any it
  missed or hide any you don't want.

## Crimson Launcher

A whole home screen in the same style, built into the Crimson Widget app. Turn it on with **Use Crimson
Launcher as home** in the app (or Settings → Apps → Default apps → Home app → Crimson Launcher). Switch
back to One UI Home the same way at any time.

- **Status bar**: the launcher hides Android's and draws its own: time, the apps with notifications,
  the battery as a ring round the camera (gold while charging, red under 15%), signal, Buds and battery.
  Inside other apps you still see Samsung's own status bar.
- **The ring**: your folders as nodes. Tap one and its apps fly out in orbit round the core (two orbits
  for more than eight). Swipe sideways or tap the chips to hop between nodes, tap the core to close.
  The first time, the nodes are built from the old home-screen folders (MAIN, AI, WORK, APPS, GOOGLE,
  SAMSUNG) plus GAMES, which fills itself with your Arcade games, most played first. Drag round the
  ring to spin it: it coasts, ticks as each node passes the top and settles on the nearest slot.
- **The core**: the old dock (WhatsApp, Messenger, Messages, Phone, Camera, Play Store, Chrome) orbits
  the centre with unread counts. The centre button searches every app. Drag round the core to spin
  the dock the same way.
- **Modes**: Morning (05:00–07:00) and Night Ops (from 17:00) put MAIN at the top of the ring; Work
  (07:00–17:00) puts WORK there and dims GAMES. Pick a different top node for each mode in Launcher
  settings. Tap the mode on the status line (◆ NIGHT OPS ▾) to switch by hand; it holds until
  the next change on the clock.
- **Crimson Weather**: tap the weather for a full forecast, laid out like Samsung Weather: now with a
  line about the day, 48 hourly cards to swipe through, conditions (feels like, humidity, wind with a
  compass, UV, rain, cloud), the sun's path, and ten days from yesterday with day and night skies.
  Tap an hour for its own page (rain amount, wind, humidity, UV, cloud and that day's graph), or a
  day for its page (day and night, a summary, hour by hour, rain total, wind, UV, sunrise and
  daylight). Swipe sideways or use ‹ › to step through them; Back returns. From Open-Meteo, for the
  city set in the app. **SAMSUNG ↗** in its corner opens Samsung Weather.
- **Around it**: weather, the date and next alarm (tap for the calendar or
  alarms), the steps line (tap for Samsung Health) and the music bar with shuffle, skip, repeat and the
  queue button.
- **Swipes**: down for the Comms Centre, up for the Hive Map, right for the Game Hub, left for the
  Command Deck.
- **Command Deck**: Ask Claude at the top (type or speak; it starts a new chat in the Claude app, and your
  recent questions are a tap away), then the day at a glance: battery, steps and screen time rings, the
  day/week/month/year in progress, music, the agenda, unread messages, weather and a quick note, the
  battery over the last 24 hours with temperature and health, data used today, markets (BTC, ETH, SOL),
  the most used apps, the Buds, quick actions (torch, Do Not Disturb, a 5-minute timer, alarms,
  calculator, phone settings) and the phone's systems. ⚙ opens Launcher settings. Screen time, data and
  most-used need Usage access (tap a card to allow it).
- **Hive Map**: every app in a honeycomb that repeats for ever in every direction (no edge), in patches round tags for the dock and each node, big in the
  middle and shrinking outwards. The middle bulges big with the apps spread apart and named; a sight marks the centre and it settles onto a cell after a fling. Drag to fly round it, tap a tag in the jump bar to glide there, or
  switch to the A–Z list. Typing lights up the matches and centres the best one; Enter opens it.
- **Game Hub**: a 3D shelf of your games (most played and last played first), the selected game's
  playtime, last played and install date, **▶ Play** and **⚄** random, the recommended row and every
  game A–Z with a letter rail. Tapping a game only selects it. The GAMES node opens it too.
- **Messages on the home screen** slide up into the space under the music bar: tap to open, REPLY to
  answer in the Comms Centre, or swipe it down to dismiss it (it stays in the Comms Centre).
- **Comms Centre**: Samsung's quick controls and your notifications in one panel: twelve toggles (Wi-Fi,
  Bluetooth, mobile data, sound, rotate, torch, Do Not Disturb, location, power saving, airplane, Buds,
  and ⋯ for Samsung's own panel), brightness and volume, the music card with its output device, Smart
  View, SmartThings, Quick Share and media output shortcuts, then the notifications to open, reply to
  inline or swipe away. Toggles marked ↗ open Android's own switch, since only the system can flip
  those. A swipe from the very top edge still opens Samsung's panel; that gesture belongs to Android.
- **Crimson Comms in any app**: the app list has a **Crimson Comms** shortcut. Add it to Samsung's
  Edge Panel (Apps panel → edit → add) and the Comms Centre opens over whatever app you're in; swipe
  up or press Back to return. There's also a glowing **Comms handle** in the middle of the right edge
  over every app: swipe it in or tap it for the same thing. Move it, switch sides or turn it off in
  Launcher settings.
- **Edge lighting** (needs "Display over other apps"): twin strips race round the screen edge in the
  app's colour when a message arrives, two comets circle it during an incoming call, gold energy climbs
  it when the charger goes in, and it breathes red on low battery. Works over any app while the phone
  is unlocked; Samsung's own edge lighting still handles the lock screen.
- **Animations**: a boot sequence each unlock (scan line, rings spinning in, nodes popping out, the dock
  flying out of the core), embers, a radar sweep and breathing nodes while idle, a shockwave and
  hologram flicker when a node deploys, target-lock brackets when an app opens, radar pings for new
  messages, a mode-shift sweep, the music visualiser and a spark along the steps line once the goal is
  hit. Turn the boot sequence or idle effects off in Launcher settings (⚙ in the Comms Centre).
- **Themes**: 16 in Launcher settings: Crimson, Blood Moon, Mark 42, Inferno, Rose Gold, Solar, Toxic,
  Venom, Matrix, Arctic, Jarvis, Cobalt, Ultraviolet, Synthwave, Phantom and Stealth. Each recolours the
  launcher, Comms, the Hive, Game Hub, Crimson Weather, edge lighting, the handle and the Crimson icons,
  with its own strength, brightness and second accent; other apps' own colours stay as they are. The
  home-screen widgets (Crimson, strip, glance and Arcade) and the app's own screens follow it too.
- **Crimson Reactor wallpaper** (in the Crimson Live app): matches the theme and reacts to the launcher:
  message shockwaves in the app's colour, calls, taps, opening a node and the time-of-day mode.
- **Battery**: idle effects run at 20 fps and rest after 40 seconds untouched (the next touch wakes
  them), and switch off while Android's power saving is on. The Comms handle only redraws under a
  finger, the phone's readings are taken every 5 seconds (every second only while music plays), the
  weather page's sky rests 20 seconds after the last touch, and the background refresh only fetches
  weather every half hour and when there's a connection.
- **Editing**: hold a node to rename it, drag its apps into order, add or remove apps, move it round the
  ring or delete it. Hold an app in orbit to move it to another node. Hold an empty spot for a new
  node, the dock, or the wallpaper.
- Notifications, unread counts and the music bar need notification access (the same switch the
  widget's music row uses). Brightness and rotation ask for permission to change settings the first
  time.

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
