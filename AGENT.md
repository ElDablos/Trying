# AGENT.md — working notes for whoever builds Crimson / Jarvis next

This file is kept up to date **with every build**: what changed, what's being worked on, what's known to be
broken, and how the owner likes to work. Read it first; add to the release log before every push.

## The owner (Vee) and how to work
- Phone: Samsung Galaxy S26 Ultra (Android 16 / One UI). Speaks to Jarvis by voice and types; makes typos and
  speech recognition mishears ("page slip" for payslip, "wprk", "habbits") — Jarvis must cope.
- **Never push without "build it".** A push to the branch starts the GitHub Actions build (`Build APK`,
  release tag `latest-build`). Commit locally, explain, wait for "build it". Then push, wait for the APK
  (check the version string in the manifest), and send it with SendUserFile.
- Branch: `ccr-e9fe99a2-a1bsw9` (repo `eldablos/trying`). No pull requests.
- Bump `versionCode` / `versionName` in `dashboard/build.gradle.kts` for each build the owner will install.
- Wants it "expert level", polished, conversational, and to *learn*; prefers waiting a few seconds over mistakes.
- Don't break the light (Tapo "Vee Room") or the heater (Philips Air+ "Bedroom") — test their paths carefully.
- When a hands job goes wrong the owner sends the **Hands log** (accessibility tree + steps): read it closely.

## Building and testing here (no Gradle: the sandbox can't reach Maven/Google)
Scratchpad `S=/tmp/claude-0/-home-user-Trying/<session>/scratchpad` holds `kc/kotlinc`, `android-all.jar`,
`vosk/*.jar`, `Rstub.kt`, `LTstub.kt` (LiteRT stub), `junit.jar`, `hamcrest.jar`, `json.jar`.
```
cd dashboard/src/main/java && $S/kc/kotlinc/bin/kotlinc -J-Xmx3g -jvm-target 17 \
  -Xnullability-annotations=@android.annotation:warn -Xnullability-annotations=@androidx.annotation:warn \
  -cp $S/android-all.jar:$S/vosk/vosk-classes.jar:$S/vosk/jna.jar $(find . -name '*.kt') $S/Rstub.kt $S/LTstub.kt -d $S/out.jar
cd ../../test/java && kotlinc -cp $CP $(find . -name '*.kt') -d $S/t4.jar
java -cp $S/t4.jar:$CP:kotlin-stdlib.jar org.junit.runner.JUnitCore com.eldablos.crimson.launcher.{BatchTest,DocumentsTest,DeckTest,LauncherTest,HiveLayoutTest,JarvisTest,RealPhrasesTest} com.eldablos.crimson.{WeatherTest,ArcadeTest,FormatTest,CalendarMonthTest}
```
A cold compile can take several minutes: run it in the background with a long timeout.
Real phrases the owner said go into `RealPhrasesTest` / `BatchTest` so they never regress.

## Map of Jarvis (dashboard/src/main/java/com/eldablos/crimson/launcher)
- `JarvisParser.kt` — rules: text → `Command`. Order matters (early rules win).
- `Jarvis.kt` — the brain stem: `answerNow` (pending answers, corrections, writing, habits, smart understanding,
  parse, follow-ons) → `finish` → `run(cmd)`. Replies are `Reply` (say, card, actions, `more` = an answer is
  coming later via `host.later`).
- `JarvisBrain.kt` — on-phone LLMs via LiteRT-LM: Gemma 4 E4B (talk + eyes), Qwen3 4B Thinking (plans),
  Qwen3 1.7B (quick interpret). `interpret`, `answer`, `reason`, `chat`, `create`, `write`, `see`, `pickControl`.
- **Smart understanding** (`translate`, on by default): unless `obvious()`, the brain maps the request to a
  command first, Jarvis does it with his own code and learns the wording (`worthLearning`: ≤12 words,
  `sensible()` checks habit names etc.). Light/heater commands the rules know go straight to the hands.
- `JarvisHands.kt` + `JarvisReaders.kt` — accessibility control. Tapo (home cards via `tapoCards`, light page),
  Air+ (`air*`), Samsung Clock (alarm wheels `clockWheels`). `JarvisRepair.kt` = brain-picked controls.
- `JarvisEar.kt` — "Hey Jarvis" (Vosk wake word) + overlay strip. `Speech.kt` — TTS (engine/voice choice).
- `Documents.kt` (docx/xlsx/pdf writers), `DocText.kt` (reading files), `ShareActivity.kt` (share to Jarvis).
- `Pay.kt`, `Shifts.kt`, `Uni.kt`, `Study.kt`, `Spending.kt`, `JarvisBackup.kt`, `JarvisProactive.kt`.

## Pay facts (match the owner's payslip)
£18/h = £12.71 basic + £5.29 TRONC (estimated). Fortnightly, anchored Mon 5 Oct 2026, paid the Friday after
(period end + 5 days). Tax code 918L, Week 1 basis, taxable pay floored to whole pounds. NI on basic only.
NEST pension 5% above £240/fortnight, relief at source (80% deducted). Thursday office 9–5 counts as paid.
Clock-in/out times are kept separately; the calendar only ever shows the planned rota.

## Release log (newest first)
- **1.71** — "How much will I get paid for next week?" went to Payday (whole period + tomorrow, from the last pay
  answer): the Payday rule now steps aside when a week is named, so it's `Hours("nextweek")`. "Only / just for
  next week" after a pay answer is that week alone (`payFollowUp`), not stretched. A week after the current pay
  period says which payslip it counts towards ("pay period 2 Nov to 15 Nov, paid Friday 20 November").
  Why Gemma missed it: `describe(guess)` gave it a bare "Payday", so it said KEEP. Guesses are now explained in
  words ("the whole current pay period's payslip … not one particular week"; "hours and pay for next week
  only"), KEEP is only for a reading that answers exactly what was asked including the day/week/month, and the
  prompt says to keep Vee's own time words. `keepWhen()`: a single DO line that dropped the day/week/month said
  gets it put back before the rules read it. Catalogue has "how much will I earn next week (that week only)".
  **Gemma first, properly** (`brainFirst` in `answerNow`): with the brain ready and Smart understanding on,
  only answers to his own questions (pending), corrections, routines, taught meanings, document edits and the
  heater's follow-up talk run before Gemma. The rules' pay/file follow-ups, `followOn`, chain `split`,
  `creative` and `habitSaid` now run *after* Gemma, in `understood()`'s fallback, when it can't place a request
  (KEEP with nothing parsed, or no valid DO). `obvious()` (instant, no Gemma) is down to yes/no/stop-type words,
  time/date/battery, maths, timers, your own words for notes/messages/spending, short light & heater commands
  and exact catalogue phrasings; anything with a writing cue always goes to Gemma. Gemma's world includes the
  last file search. Without the brain, the old rules-first order still applies (tests run that way).
- **1.70** — Found files open by tapping their name: `Row.tap` makes a card line a button (taller, › chevron,
  press highlight in `JarvisView.CardPanel`); the FILES card lists every match (up to 8), newest first among
  equals, "Your latest is …" for last/latest. Backups ("Jarvis memory…json") left out unless asked for.
  "Latest" no longer limits to 14 days (only "recent" does). "Find my last payslip (I downloaded) on my phone"
  is a file search. "It should be a pdf" / "the photo one" / "from last week" narrow the last search
  (`fileFollowUp`, 5 min). The brain's DO lines are checked with `asked()`: no MakeDoc unless you asked to
  make/convert/save as, no OpenApp unless you said open and the app exists (it had chained find + make a PDF +
  "open payslip" app). Lesson: never `open(p,'w').write(open(p).read())` in patch scripts — it empties the file.
- **1.69** — Command Deck visual redesign ("Stark HUD"): hero ring clock (seconds arc, gold day arc, tap → alarms)
  with date, mode and status chips (battery, network, VPN, DND, unread, next alarm); four quick tiles (next shift,
  sky, next payslip net + payday, habits) that open their card and scroll to it (`reveal`, `jumpTo`, `sectionTop`);
  Ask Claude always right under the tiles, never folded; "TODAY" (briefing + open cards) and "MODULES" (folded
  cards as a 2-column grid of tiles with live `summary`); glass `panel` (gradient edge), hex `glyph` badges in
  `title`, honeycomb backdrop from one cached tile (BitmapShader). Strips removed.
  Chat keeps up with the strip: `JarvisChat.watcher` hands every
  line to the open panel as it's said, so a "Hey Jarvis" exchange over the panel appears in the chat at once
  (before, it only showed on reopening). `JarvisChat.heard()` shows your words the moment they're heard, before
  the brain answers, and the stored line keeps that time so reopening never duplicates it. Command Deck visual
  redesign mocked up first (scratchpad mock/deck.html) and approved.
  Tube: tomorrow/future days always said "can't reach TfL" because `/Line/Mode/{modes}/Status/{from}/{to}` isn't
  a TfL route; future days now use `/Line/{all ids}/Status/{from}/to/{to}` (`Travel.url`), with each status's
  validity period shown ("Sat 7 – Sun 8 Nov"). "This week", "next week", "this weekend" = a span (corrected
  mid-sentence, the last one counts). "What are the six more" / "which other lines" lists every disrupted line;
  ALL N button. Tube commands are `obvious()` (the brain's rewording had dropped "tomorrow"). "Not today,
  tomorrow" / "no I meant next week" = follow-up. DO lines lose a trailing "(note)" before parsing.
  Repeat guard (`fresh()`): the same answer to a *different* question (word overlap < 50%) becomes "I'd only be
  repeating myself… say it another way and I'll learn it" (Pending.Teach), never a parrot.
- **1.68** — AI-first Jarvis. `translate()` → `JarvisBrain.route()`: every request that isn't instant goes to Gemma
  with the conversation (last 6 lines), `worldNow()` (time, today's/tomorrow's shift, clocked-in state, habits,
  notes, devices, last pay sum, pay period) and the rules' own reading; it answers DO: <command> lines (chains),
  KEEP, CHAT, ANSWER or WRITE. Each DO line is re-parsed by the rules and checked (`sensible`) before running;
  one line is learnt as a meaning. `CATALOGUE` lists every canonical command. `obvious()` = instant path: plain
  answers, your-own-words requests (notes, messages, spending), docs/files/deck, short light & heating commands,
  and exact canonical phrasings. New: file search (`Files.kt`, MediaStore, All files access, names/kinds/dates and
  text inside docx/xlsx/txt; FIND card with OPEN 1-3/SHARE), "read my deck" (`DeckView.deckText`), "my pay
  including <day>", "train my voice" (3 tries → learnt wake names + personal bar). Wake word: base bar 0.62,
  sound-alikes accepted after "hey" once trained, pace 0.25–2.2 s, "jarvis" alone if very clear, old bar reset.
  Hey Jarvis strip redesigned: STOP/TALK, REPEAT, OPEN, CLOSE keys, scan line. Command Deck: cards fold to
  one-line strips with live summaries (tap to open, hold the title to fold; quieter cards start folded).
  Air+ works from either screen: home card's own power toggle (`airHomePower`) or the heater's page; the home
  screen is told apart by Air+'s `dashboard_*` ids. Includes 1.67's clocking fixes.
- **1.67** (built into 1.68) — Clocking: "clocked out 18.15" takes the time (was only read after "at", so it
  logged 18:20); "clocked up/off" is clocking out; if you've already clocked in today, or the shift's within 45
  min of ending, "clocked in" is taken as clocking out (it had clocked in at 18:19 after an 18:00 finish). A
  clock-in under 30 min old doesn't count as the start, and a shift under 15 min or over 16 h is asked about,
  never logged (the mistaken 18:19 in had made 18:19–18:19 = "24 hours, £432").
- **1.66** — Pay follow-ups build on the last pay answer: "and with tomorrow's shift",
  "plus Sunday", "including my next shift", "so altogether" stretch the range and add it up as one (this month
  can't hold tomorrow when tomorrow is the 1st; payday answer also counts). Short follow-ups that lean on the
  conversation ("what about…", "so how much then", "with that") go to the brain with context instead of rules.
  Chained commands carry a day said once ("the weather tomorrow and am I working" → tomorrow's work too).
  Paid fortnightly, so pay asked without a week/month ("what's my pay", "how much have I made") is the pay
  period (19 Oct–1 Nov now; resets Mon 2 Nov), and week/month answers add a NEXT PAYSLIP line.
  "Hey Jarvis" stopped answering at 15% battery without saying so: now rests only at ≤8% (not charging), and
  the ongoing notification says "Jarvis is resting — battery 14%…" / "listening" as it changes. The wake
  word's adaptive strictness is capped at 0.86 so false starts can't make him deaf.
  Air+: the "My Devices" home screen (Bedroom card with its own Power, Auto and 34°C buttons and "Temperature
  around device") was taken for the heater's page (its card's Power button), so he never tapped the card when
  setting the temperature. `airHome()` now tells them apart; on home he taps the card once (again only after a
  few looks, so a second tap can't land on the page's history box).
- **1.65** — Phrase sweep (90 everyday phrases run through the parser, misses fixed): text-speak and typos
  ("tmrw", "tmr", "wat", "nxt", "lite", "switch of the light", "heating of", "ive"); "put the heater on 25" sets
  25°; "whats my take home this month" is pay; "whats my day like" is the morning brief; "countdown to my
  holiday on 3 March" creates it; "I had a long day", "work was busy", "I'm tired" are chat, not habit/shift.
  Pay questions as said: "how much am I supposed to get paid on my page slip",
  "from what I've earned already", "add tomorrow's shift to this" → next payslip estimate. "Page slip" etc.
  heard as payslip. Brain sees the last lines of the conversation (so "this"/"it" mean something) and may not
  borrow dates/places from examples (it had set a "Holiday 3 March" countdown). No brain reply may claim an
  action ("I have removed…"). "ok", "removed", "cool" are acknowledgements. AGENT.md started.
- **1.64** — Tapo: ignore off-screen copies of the card (Favorites tab at x −372 was being pressed), wait for the
  home screen to settle, never press the toggle twice (it switched the light on then off). Long sentences
  aren't a light's name; "use AI for this" can run a command.
- **1.63** — Samsung Clock: read the chosen time from the wheel's own box ("06, Hour"), fresh per alarm (had
  saved 04:59 for 05:00 and left 06:05 alone).
- **1.62** — Writing requests caught however long; "undo"/"you write it" while he waits for a note's words.
- **1.61** — Writes things for you (COPY / SEND / PDF / SAVE AS NOTE / ANOTHER ONE); birthdays said in passing
  remembered; "use AI for this"; long one-offs never learnt; "Noo" is no.
- **1.60** — Smart understanding; habits ticked by name ("tick off place orders for wprk"); no invented habit
  lessons; chat never claims actions; Clock "06, Hour" first fix.
- **1.59** — Self-repair, systems check, memory backup (weekly), proactive moments, morning brief, dated facts,
  payslip check, study mode, spending, document editing, reading files, shorter briefing; review fixes.
- **1.58** — Documents: Word, Excel, PDF.  **1.57** — Tapo home vs light page, Air+ wait fix.
- **1.56** — Talks back to remarks; voice text cleaned ("dot").  **1.55** — Voice engine picker, double-check.
- Earlier: 1.54 brains, 1.48 deck/uni, 1.32 UI, etc. (see `git log`).

## Phrase sweep
Before a build, run a batch of everyday phrases through `JarvisParser.parse` (a throwaway `Sweep.kt` in the
scratchpad) and fix anything that lands on the wrong command; put the fixed ones in `BatchTest`.

## Known issues / watch list
- "Hey Jarvis" not answering: check battery (rests at ≤8% unless charging), "only while charging" setting,
  the notification text, and "wake log" (shows heard/ignored and the confidence bar).
- Tapo and Air+ change their screens with updates: if a hands job fails, get the Hands log first.
- Samsung Clock wheel reading is new (1.63) — confirm alarms move correctly at 5:00–5:25.
- The brain's answers are only as good as the small on-phone models; keep rules for anything exact
  (pay, shifts, alarms, lights, heater) and let the brain interpret / write / chat.
- PDF reading uses Gemma's eyes on the first 3 pages only.

## Ideas not done yet
- Voice pitch/speed sliders. Brain-written edits for spreadsheets. Payslip photo → auto-update pay settings.
