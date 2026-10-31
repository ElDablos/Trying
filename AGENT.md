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
- **1.65** (pending "build it") — Pay questions as said: "how much am I supposed to get paid on my page slip",
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

## Known issues / watch list
- Tapo and Air+ change their screens with updates: if a hands job fails, get the Hands log first.
- Samsung Clock wheel reading is new (1.63) — confirm alarms move correctly at 5:00–5:25.
- The brain's answers are only as good as the small on-phone models; keep rules for anything exact
  (pay, shifts, alarms, lights, heater) and let the brain interpret / write / chat.
- PDF reading uses Gemma's eyes on the first 3 pages only.

## Ideas not done yet
- Voice pitch/speed sliders. Brain-written edits for spreadsheets. Payslip photo → auto-update pay settings.
