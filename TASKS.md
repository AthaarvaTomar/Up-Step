# TASKS.md: FocusMeme

Legend: **[AI]** = the agent does it. **[ME]** = I do it. **[BOTH]** = we do it together.
Rule: AI builds the logic and a basic UI, I read and test it on the phone, then we tick the box. Exception: I write all Firestore rules and Firestore functions (marked [ME]); the AI gives the interface and reviews.
Source of truth: `PRD.md`. Agent rules: `AGENTS.md`.

## UI policy (for now)

- Stock Google Material 3 only: `useMaterial3: true`, `ColorScheme.fromSeed`, follow the system light/dark mode.
- Standard widgets only: `Scaffold`, `AppBar`, `ListView`, `ListTile`, `Card`, `Switch`, `TextField`, `FilledButton`, `NavigationBar`, `SegmentedButton`, `LinearProgressIndicator`.
- No custom theme, fonts, colors, animations, or `CustomPainter`.
- One plain file per screen. Logic stays in providers, never inside widgets.
- Real UI polish is deferred to M9.
- Exceptions that stay as designed because they are logic, not looks: the pure-black clock screen (battery) and the native meme overlay (black background, video or image).

---

## M0: Scaffold and setup

Goal: a blank app runs on my phone with Firebase connected.

- [ ] [ME] Install Flutter and Android Studio SDK; enable USB debugging on my phone
- [ ] [AI] Create the Flutter project (`--platforms android`) and add the PRD packages
- [ ] [AI] Create folders `lib/{app,features/{limits,focus,memes,onboarding},data,native}`
- [ ] [ME] Create the Firebase project; enable Anonymous Auth, Firestore, and Storage
- [ ] [ME] Run `flutterfire configure` and add `google-services.json` as instructed
- [ ] [AI] Wire `Firebase.initializeApp` and `ProviderScope` in `main()`; set the stock Material 3 theme
- [ ] [AI] Add a basic `go_router` shell (`StatefulShellRoute`) with a `NavigationBar` and empty tabs: Home, Focus, Limits, Settings
- [ ] [AI] Explain `ProviderScope` and each Firebase console step
- [ ] [ME] Run the blank app on my phone

**Done when:** the app launches with no Firebase errors and the four tabs switch.

---

## M1: Auth and limits data

Goal: I can add, edit, and delete app limits, and they save to Firestore.

- [ ] [AI] Anonymous sign-in provider (first-time anonymous sign-in requires network once; Firestore offline persistence works afterward)
- [ ] [AI] Limits repository interface (add, update, delete, stream) with the data shape from PRD section 7, plus a stub
- [ ] [AI] Riverpod providers for the limits actions and state, each with a doc comment (except the limits `StreamProvider`, which I write)
- [ ] [ME] Write the Firestore limits repository (implement the interface) and the Firestore security rules (`users/{uid}/**` owner-only); publish the rules in the console
- [ ] [ME] Write the limits `StreamProvider` over the repository (about 10 lines)
- [ ] [AI] Installed-apps provider (name, package) + minimal `MainActivity` `MethodChannel` stub for `getInstalledApps` using `<queries>` (`ACTION_MAIN`/`CATEGORY_LAUNCHER`, no `QUERY_ALL_PACKAGES`)
- [ ] [AI] Basic Limits screen: `ListView` of `ListTile`s, a plain dialog with a number `TextField`, a `FilledButton` to save
- [ ] [AI] Explain StreamProvider and `ref.watch` vs `ref.read`, with the 3-question check
- [ ] [ME] Read the providers and trace how the Limits screen uses them
- [ ] [AI] Review my rules, repository and `StreamProvider`; explain what is wrong and why
- [ ] [ME] Edit a limit in the Firebase console and confirm the screen updates live
- [ ] [ME] Test the rules in the Rules Playground: another user's data is rejected

**Done when:** changes in the app and in the console both show up live, and the rules reject another user's data.

---

## M2: Native blocker core

Goal: a limited app is closed on my phone, and repeat attempts play a meme.

- [ ] [AI] Kotlin `AccessibilityService` (ignore keyboard, system UI, own app, launcher)
- [ ] [AI] Block ladder: attempt 1 = close + toast (`"<App> limit reached"` vs `"<App> blocked during focus"`), attempt 2+ = close + meme (per app, per day)
- [ ] [AI] Incremental usage tracking and exact-second timer for mid-session limit hits (pauses/cancels on app switch and `ACTION_SCREEN_OFF`; re-evaluates the foreground app on `ACTION_USER_PRESENT`/`ACTION_SCREEN_ON`)
- [ ] [AI] Meme overlay (`TYPE_ACCESSIBILITY_OVERLAY`) using native media playback (`Media3`/`ExoPlayer` or `VideoView` + native image/GIF decoding), unskippable, ~15 s cap
- [ ] [AI] Bundled memes (2-3 per section, in `res/raw` or `assets`) and per-section shuffle bags owned by native and persisted in native storage
- [ ] [AI] MethodChannel `focusmeme/blocker`: `sync`, `status`, `getInstalledApps`, `getUsageStats`, `openAccessibility`, `openUsageAccess`, `openBattery`, `openAutostart` (`sync` payload: limits, strictSchedule, settings; native keeps its own SharedPreferences)
- [ ] [AI] Dart bridge that pushes limits to native (debounced), local-first: native updates even if Firestore fails
- [ ] [AI] Phone test checklist and a list of assumptions
- [ ] [ME] Install on my phone, enable Accessibility and Usage Access manually
- [ ] [ME] Run the test checklist and report failures
- [ ] [ME] Test: lock the screen inside a limited app near its limit, unlock, and confirm the block still fires
- [ ] [AI] Fix reported bugs

**Done when:** attempt 1 closes the app with a toast, attempt 2 plays a meme, and a mid-session limit hit closes the app at that second.

---

## M3: Onboarding and permissions

Goal: a simple screen shows which permissions are on and re-checks when I return from Settings.

- [ ] [AI] Permission status provider (accessibility, usage, battery) that refreshes on app resume
- [ ] [AI] Basic onboarding screen: one `ListTile` per permission with a check icon and an Enable button
- [ ] [AI] "Blocking is off" detection and a plain `MaterialBanner` on Home
- [ ] [AI] OEM autostart/battery tile (Xiaomi, Oppo, Vivo, Realme)
- [ ] [ME] Toggle each permission in Settings and confirm the tile updates when I return

**Done when:** toggling a permission in Settings updates the tile as soon as I return.

---

## M4: Pomodoro study sessions

Goal: a focus session blocks apps only during focus phases and survives the app being killed.

- [ ] [AI] Schedule builder: absolute-timestamp focus/break windows, long break after every 4th cycle (midnight-crossing sessions log under `startedAt`'s date)
- [ ] [AI] Native focus-window storage and checks (breaks unblock; turning off `focusOnly` during an active focus session does not unblock mid-session)
- [ ] [AI] Pomodoro notifier (start, quit-with-friction, complete) and session logging through the session repository interface (+ stub)
- [ ] [ME] Write the Firestore session functions (implement the interface); [AI] reviews them
- [ ] [AI] Quit rule: 10 s wait, then confirm, then mark `broken` and keep focus minutes done
- [ ] [AI] Basic Focus screen: subject `TextField`, focus/break/cycles inputs, remaining time as plain text with a `LinearProgressIndicator`, Start and Quit buttons
- [ ] [AI] Explain NotifierProvider and why timers derive from timestamps, with the 3-question check
- [ ] [ME] Test: start a session, kill the app, confirm blocking continues
- [ ] [ME] Test: blocking stops during breaks

**Done when:** blocking applies in focus, stops in breaks, and survives a force-kill.

---

## M5: Clock mode

Goal: a low-battery full-screen clock that keeps the blocker running.

- [ ] [AI] Minute-boundary tick logic, wake lock (`wakelock_plus`), burn-in position shift
- [ ] [AI] Basic clock screen: pure black background, dim text, remaining focus time primary, tap shows a plain button for ~5 s
- [ ] [AI] Confirm the blocker is unaffected in clock mode
- [ ] [ME] Run it for 30 minutes and note the battery drop

**Done when:** no animations, updates once a minute, the layout shifts every few minutes, and the battery drop is under ~8% per hour on an OLED phone (measure 30 minutes and extrapolate).

---

## M6: Meme sections and sync

Goal: memes come from Firebase Storage by section and sync efficiently.

- [ ] [ME] Choose memes and check licensing (private testing only unless licensed)
- [ ] [ME] Upload memes to Storage in section folders: `limit_hit`, `strict_session`, `late_night`, `uninstall_attempt`, `repeat_offender`, `general`
- [ ] [AI] Manifest format (`version` + file list per section) and its Firestore doc
- [ ] [ME] Write the Storage security rules and the Firestore manifest read function (implement the interface); [AI] reviews them
- [ ] [AI] Sync: Wi-Fi only by default (`connectivity_plus`), download only new files into `getApplicationSupportDirectory()/memes/<section>/`, delete removed ones, ~100 MB cap
- [ ] [AI] Section choice logic in native (PRD priority order) with fallback to `general`, then bundled assets
- [ ] [AI] Extend the native shuffle bags to include synced files; bags persist across restarts
- [ ] [AI] Basic Settings screen: Wi-Fi-only `Switch`, cache size text, "Sync now" button, farewell meme `Switch`
- [ ] [ME] Add a meme to the manifest and confirm it reaches the phone

**Done when:** a new meme appears on the phone, and an empty section falls back correctly.

---

## M7: Limit rules and stats

Goal: no limit-edit or reinstall loophole, and I can see my progress.

- [ ] [AI] Raise-tomorrow rule: lower applies now; raise or remove applies at next midnight (`pendingMinutes` / `pendingRemove`)
- [ ] [ME] Write the Firestore functions for `pendingMinutes`/`pendingRemove`/`pendingFrom` and the daily stats docs; [AI] reviews them
- [ ] [AI] Link the anonymous account to Google sign-in (`google_sign_in`) so data survives a reinstall
- [ ] [ME] Add my SHA-1 fingerprint in the Firebase console and enable the Google sign-in provider
- [ ] [AI] Daily stats: pull today's per-app usage & attempt counts from native via `getUsageStats`, focus minutes per subject, completed vs broken, streak
- [ ] [AI] Streak rule: a day counts if at least one session is completed and none is broken that day
- [ ] [AI] Basic Home screen: per-app usage as text plus `LinearProgressIndicator`, streak as a number, recent sessions as a list
- [ ] [AI] Show "pending change, applies tomorrow" as subtitle text on the Limits screen
- [ ] [ME] Test: raise a limit and confirm I stay blocked today
- [ ] [ME] Test: the streak across midnight
- [ ] [ME] Test: reinstall the app, sign in with Google, and confirm limits and pending changes come back

**Done when:** raising a limit does not unblock me today, the streak counts correctly across midnight, and a reinstall restores my limits.

---

## M8: Uninstall farewell

Goal: a farewell meme plays over the uninstall dialog.

- [ ] [AI] Detect the system uninstall dialog for FocusMeme (known installer packages list + launcher uninstall sheets)
- [ ] [AI] Play a 5-8 s `uninstall_attempt` meme while the dialog is visible (partial overlay, small card or top strip, not touchable so Confirm/Cancel stay visible; tune placement on my phone; never press Home, close the dialog, or intercept confirm/cancel taps)
- [ ] [AI] Read window content only while an installer is foreground; never store or send it
- [ ] [AI] Accessibility disclosure text for the in-app screen and Play Console (covering `canRetrieveWindowContent="true"`)
- [ ] [ME] Test uninstalling from the launcher and from Play Store
- [ ] [ME] Final test pass on the full checklist
- [ ] [BOTH] Fix remaining bugs

**Done when:** uninstalling still works, the meme plays during the dialog, and the core checklist passes.

---

## M9: UI enhancement (later)

Start only after M0-M8 are done and stable.

- [ ] [ME] Learn: Material 3 theming, `StatefulWidget` vs `StatelessWidget`, layouts, `go_router`
- [ ] [ME] Design the "Obsidian"-style dark theme and AMOLED-friendly colors
- [ ] [ME] Rebuild screens one at a time: Limits, Focus, Home, Onboarding, Settings, Clock
- [ ] [ME] Usage rings with `CustomPainter`, animations, custom fonts
- [ ] [AI] Review each screen I paste and explain what is wrong and why
- [ ] [AI] Polish the native meme overlay if I ask

**Done when:** I am happy with how every screen looks.

---

## Summary by owner

**AI:** all Kotlin, MethodChannel, providers (except the M1 limits `StreamProvider`), Firebase Auth and Storage code, Pomodoro/limit/stats/meme logic, the basic Material screens, Firestore repository interfaces and stubs, explanations, checklists.
**Me:** Firebase console setup, all Firestore security rules and Firestore functions, the M1 limits `StreamProvider`, meme selection and upload, phone testing, reading and understanding the logic, and all UI polish in M9.

## Standing rules

- Test on a real Android phone. Emulators do not handle accessibility services well.
- Every Firestore rule or function I write gets reviewed by the AI before we tick the task.
- Every AI task ends with: what was built, data flow, key concepts, which providers the screen uses, a 3-question check, assumptions, and a test checklist.
- Do not add packages, permissions, or MethodChannel methods without asking (pre-approved items are listed in AGENTS.md).
- Do not spend time on UI polish before M9.
- Memes from the internet are fine for private testing, but risky for a Play Store release.
