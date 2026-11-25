# FocusMeme: Product Requirements Document (v1, Android first)

## 1. Summary

FocusMeme is a digital-wellbeing app that combines a Pomodoro study timer, per-app daily usage limits, and a "meme blocker". When a limit is hit (or a restricted app is opened during a focus phase), the app is closed. Repeat attempts play a random meme.

**Stack:** Flutter (Material 3), Riverpod, go_router, Firebase (Auth, Firestore, Storage), native Kotlin for blocking.
**Platform:** Android first. iOS later (static shield only; needs Apple Family Controls approval).

## 2. Goals

1. Reduce time on distracting apps through limits and focus sessions.
2. Make blocking memorable and funny, not just restrictive.
3. Clean dark/AMOLED-first UI in M9; stock Google Material 3 during M0-M8 while building core logic.
4. Work offline and survive the Flutter app being killed.
5. Learning project: AI builds core logic and basic Material 3 screens in M0-M8 while the developer learns the logic and tests on-device; the developer writes all Firestore rules and Firestore functions, and rebuilds and polishes the UI in M9.

**Non-goals (v1):** iOS, parental control, category limits, social features, web.

## 3. Users

Students and young professionals who want focus sessions and screen-time limits with a lighter, humorous tone.

## 4. Core rules (locked)

### 4.1 Block ladder

- Attempts are counted per app, per day across both daily limits and strict focus sessions.
- 1st block: app closes (Home), toast reflecting the reason (`"<App> limit reached"` for daily limits, `"<App> blocked during focus"` for strict sessions). No meme.
- 2nd block onward: app closes and a random meme plays.
- A limit reached while the user is inside the app blocks at that exact moment and counts as attempt #1; implemented via an in-service timer for remaining time that pauses/cancels on app switch or screen off (`ACTION_SCREEN_OFF`), and the foreground app is re-evaluated on `ACTION_USER_PRESENT`/`ACTION_SCREEN_ON` (no window event may fire when the screen wakes).
- Debounce duplicate events (~1.5 s).

### 4.2 Daily limits

- Per-app limit in minutes; resets at local midnight; usage is per device.
- Lowering a limit applies immediately. Raising or removing applies from tomorrow (anti-loophole).
- Each app can have a daily limit, a "focus-only" toggle, or both. Turning off `focusOnly` during an active focus session applies after the session (or next midnight) so it cannot be used as a mid-session loophole.

### 4.3 Pomodoro study sessions

- Session = subject, focus length (default 25), break (5), cycles (4), long break (15 after every 4th).
- Strict blocking applies only in focus phases; breaks unblock.
- The full schedule (absolute timestamps) is stored on the phone and owned by native, so killing the app does not stop it.
- UI timers derive from `now` vs the schedule, never from a ticking counter.
- Quit early: allowed after a 10 s wait plus confirm; logged as `broken`, keeping focus minutes done.
- Sessions crossing midnight are logged under `startedAt`'s date.

### 4.4 Memes

- Stored in Firebase Storage in sections by trigger: `limit_hit`, `strict_session`, `late_night`, `uninstall_attempt`, `repeat_offender`, `general`.
- Section priority (first match wins): `uninstall_attempt` > `repeat_offender` (5+ attempts today for that same app) > `strict_session` > `late_night` (00:00-05:00) > `limit_hit` > `general`. Empty section falls back to `general`, then bundled assets.
- Each section has its own shuffle bag (no repeats until exhausted).
- Meme is unskippable until it ends (cap ~15 s).
- Manifest sync (list + version), Wi-Fi only by default, download only new files, delete removed ones, ~100 MB cache cap.
- Ship 2-3 bundled memes per section.

### 4.5 Uninstall farewell

- Non-blocking: when the system uninstall dialog for FocusMeme appears, play a 5-8 s `uninstall_attempt` meme overlay only while the dialog is open (Android terminates the app immediately upon confirmation). Never press Home, close the dialog, or intercept confirmation/cancel taps. The overlay is partial (a small card or top strip, not touchable) so the dialog buttons stay visible and tappable; tune placement on a real phone.
- Settings toggle to disable. Window content is read only while a package installer is in the foreground; never stored or sent. Note: `canRetrieveWindowContent="true"` requires Play Console Accessibility disclosure.

### 4.6 Clock mode

- Pure black, dim text, remaining focus time primary.
- Updates once per minute at minute boundaries; no animations.
- Layout shifts a few pixels every few minutes (burn-in).
- Screen stays awake; tap shows controls for ~5 s.
- Blocker keeps running in the background.
- Battery target: under ~8% per hour on an OLED phone (measure over 30 minutes and extrapolate).

### 4.7 Stats

- Streak: a day counts if at least one session is completed and none is broken that day.

## 5. Features and "done when"

| ID  | Feature                    | Done when                                                                                                 |
| --- | -------------------------- | --------------------------------------------------------------------------------------------------------- |
| F1  | Onboarding and permissions | Accessibility, Usage Access, battery, and OEM autostart steps show live status and re-check on app resume |
| F2  | Limits screen              | User can add/edit/remove limits; rules in 4.2 hold                                                        |
| F3  | Blocker                    | Ladder in 4.1 works on a real phone, including mid-session block and after app kill                       |
| F4  | Pomodoro                   | Schedule survives app kill; breaks unblock; quit friction works                                           |
| F5  | Clock mode                 | Minute-level updates, no animation, burn-in shift                                                         |
| F6  | Meme sections and sync     | Manifest sync, per-section shuffle bags, fallbacks                                                        |
| F7  | Stats                      | Focus minutes per subject, streak, completed vs broken                                                    |
| F8  | Uninstall farewell         | Meme plays over the dialog; uninstall still works                                                         |
| F9  | "Blocking is off" banner   | Shown when Accessibility is disabled                                                                      |

## 6. Architecture

```
Flutter UI (Riverpod) --> local store + MethodChannel "focusmeme/blocker" `sync` --> Kotlin AccessibilityService
        |                    (native keeps its own store)                            (reads LOCAL data only, never Firestore)
        +--> Firestore (backup/sync, written after local; never gates blocking)
```

- Firestore offline persistence works after initial sign-in; first-time anonymous sign-in requires network once.
- Native owns: window detection, usage tracking, schedule checks, overlay, uninstall detection.
- Dart logic layer (providers, sync, auth, meme cache, Pomodoro state, stats): written by the AI, except Firestore security rules and Firestore read/write functions (repositories), which the developer writes. The AI supplies the repository interfaces and stubs.
- Flutter UI layer: plain stock Material 3 screens built by the AI in M0-M8; full theme/UI redesign built by the developer in M9.
- MethodChannel methods: `sync`, `status`, `getInstalledApps`, `getUsageStats`, `openAccessibility`, `openUsageAccess`, `openBattery`, `openAutostart`.
- `sync` is the only way Dart writes native data; payload: limits, strictSchedule, settings (`farewellMemeOn`, `wifiOnlySync`). Native keeps its own SharedPreferences. Flutter's `shared_preferences` writes prefixed keys to its own file; do not read it from Kotlin, and use the plugin only for UI-only state.
- Native picks the meme and owns the shuffle bags (block time reads local data only). Dart downloads files and the manifest into `getApplicationSupportDirectory()/memes/<section>/` (matches native `filesDir`). Bundled memes live in Android `res/raw` or `assets`, not Flutter assets.
- `getInstalledApps` uses Android `<queries>` (`ACTION_MAIN` + `CATEGORY_LAUNCHER`) to avoid `QUERY_ALL_PACKAGES`. Native overlay playback uses Android `VideoView` (`Media3`/`ExoPlayer` only if needed) + native image/GIF decoding rather than Flutter `video_player`.

## 7. Data model

```
users/{uid}
  settings: { farewellMemeOn, wifiOnlySync }
  limits/{pkg}: { limitMinutes, focusOnly, pendingMinutes?, pendingRemove?, pendingFrom? }
  sessions/{id}: { subject, focusMin, breakMin, cycles, startedAt,
                   status: completed|broken, focusMinutesDone }
  stats/daily_{yyyy-MM-dd}: { perApp: {pkg: ms}, streakOk }
memes/manifest: { version, sections: { <section>: [{id, file, size}] } }
```

Local: limits, strictSchedule, attempts, attemptsDay, farewellMemeOn, `filesDir/memes/<section>/`.

## 8. UI

- M0-M8 policy: stock Google Material 3 (`useMaterial3: true`, `ColorScheme.fromSeed`, system light/dark mode, standard widgets only, no custom theme, fonts, animations, or `CustomPainter`). Functional exceptions: pure-black Clock mode and black-background native meme overlay.
- M9 goal: dark-first, AMOLED-friendly Material 3 redesign by the developer.
- Navigation: `go_router` from M0 (`StatefulShellRoute` for the four tabs; Onboarding and Clock are full-screen routes).
- Screens: Home (usage list + progress bars in M0-M8; usage rings in M9), Focus (timer, subjects), Limits, Settings/Protection, Onboarding, Clock mode.
- Signature moments: meme overlay and clock mode get the most design effort.

## 9. Task division (AI vs developer)

### 9.1 Overall split

| Area                    | AI                                                                                                                               | Developer                                                                                                  |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Native Android (Kotlin) | Accessibility service, overlay, usage tracking, uninstall detection, MethodChannel                                               | Test on a real phone using the checklists                                                                  |
| Logic layer (Dart)      | Riverpod providers, Firebase Auth (incl. Google linking in M7), Storage download logic, sync, Pomodoro state, limit rules, meme caching, stats; repository interfaces and stubs for Firestore | Read it, trace the data flow, answer the check questions; write all Firestore rules and Firestore read/write functions, plus the limits `StreamProvider` in M1 |
| UI (Flutter)            | Plain stock Material 3 screens in M0-M8; reviews developer's M9 screens                                                          | Full dark/AMOLED UI redesign, theme, navigation polish, animations in M9                                   |
| Setup and accounts      | Project scaffold, packages, config files                                                                                         | Create the Firebase project, run `flutterfire configure`, enable Auth, Firestore, and Storage in the console |
| Content                 | Meme pipeline (manifest, folder structure)                                                                                       | Pick memes, upload them to Firebase Storage, check licensing                                               |

### 9.2 Working loop

**M0-M8 (core logic + basic UI):**
1. AI builds the logic and basic stock Material 3 UI, then explains what it does, the data flow, and which providers the screen uses.
2. Developer reads the code, traces the providers, and answers the check questions.
3. Developer tests on a real phone using the checklist and reports bugs.
4. AI fixes bugs, then completed tasks are ticked in `TASKS.md`.

**Firestore pieces (any milestone):**
1. AI gives the repository interface, the data shape (section 7), and a stub.
2. Developer writes the Firestore code and rules.
3. AI reviews, explains what is wrong and why, then the task is ticked.

**M9 (UI enhancement):**
1. Developer rebuilds/polishes screens one at a time.
2. Developer pastes the code; AI reviews it, explains what is wrong and why, and shows fixes.

### 9.3 Developer learning path

1. M0-M8: Riverpod providers (`StreamProvider`, `NotifierProvider`), `ref.watch` vs `ref.read`, MethodChannel bridge, native service lifecycle, and app architecture.
2. M9: Flutter widgets (`StatelessWidget` vs `StatefulWidget`, `Column`/`Row`/`ListView`, `TextField`, `Scaffold`), `go_router` navigation, Material 3 theming, `CustomPainter` (usage rings), and animations.

### 9.4 Developer-owned Firestore work (required)

- Firestore security rules (Firestore in M1, Storage in M6) and all Firestore read/write functions: limits (M1), sessions (M4), meme manifest read (M6), pending changes and stats (M7).
- The limits `StreamProvider` in M1 (about 10 lines).
- For each piece the AI supplies the interface, data shape, stub and test checklist; the developer writes the Firestore code; the AI reviews it.

### 9.5 Optional starter exercises (move to developer if wanted)

- Pomodoro schedule math (M4).
- Shuffle bag for memes (M6).

## 10. Milestones

| #   | Milestone          | AI does                                                                                             | Developer does                                                                                             |
| --- | ------------------ | --------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| M0  | Scaffold           | Create project, add packages, set up folders, wire `ProviderScope`, stock `NavigationBar` shell     | Create Firebase project, run setup commands, get blank app running on phone                                |
| M1  | Limits data        | Anonymous auth, limits repository interface + stub, providers over it, `getInstalledApps` stub, basic Limits screen | Write the Firestore rules, the limits repository, and the limits `StreamProvider`; trace providers; test live updates between app and Firebase console |
| M2  | Blocker core       | Kotlin service, native meme overlay, bundled memes, usage tracking, `MethodChannel` bridge          | Install the build, run the test checklist, report what breaks                                              |
| M3  | Onboarding         | Permission-status providers (refresh on resume), `MethodChannel` calls, basic Onboarding + banner   | Toggle permissions in Settings and confirm live status updates on resume                                   |
| M4  | Pomodoro           | Schedule logic, native focus windows, notifier, session repository interface + stub, basic Focus screen | Write the Firestore session functions; start/kill tests, confirm blocking in focus and unblocking in breaks |
| M5  | Clock mode         | Minute-tick logic, wake lock (`wakelock_plus`), burn-in shift, basic pure-black Clock screen        | Run for 30 minutes on phone and note battery drop                                                          |
| M6  | Meme sync          | Manifest format, download, cache, native shuffle bags, basic Settings screen | Write Storage rules and the manifest read function; choose/upload memes to Storage, add meme to manifest and confirm sync |
| M7  | Rules and stats    | Raise-tomorrow + `pendingRemove` rules, `getUsageStats`, stats & streak logic, anonymous-to-Google linking, basic Home screen | Write the pending/stats Firestore functions; add SHA-1 and enable Google sign-in in the console; test raising/removing limits across midnight, the streak, and reinstall restore |
| M8  | Uninstall farewell | Uninstall farewell (`uninstall_attempt`), non-blocking overlay, disclosure text, bug fixes          | Test uninstall from launcher & Play Store, full checklist final pass                                       |
| M9  | UI enhancement     | Review pasted screens and polish native overlay if asked                                            | Redesign dark/AMOLED Material 3 theme, rebuild screens, `CustomPainter` usage rings, animations            |

## 11. Risks

| Risk                                              | Mitigation                                                                 |
| ------------------------------------------------- | -------------------------------------------------------------------------- |
| Play policy on AccessibilityService               | Clear in-app disclosure, Play Console declaration, minimal content reading |
| OEM battery killers                               | Onboarding for autostart/battery; detect and show banner                   |
| Copyrighted memes                                 | Fine for private testing; use licensed/original memes before release       |
| Users disable accessibility or uninstall          | Detect and warn; cannot be prevented                                       |
| Timing bugs (duplicate events, midnight rollover) | Debounce, date-keyed counters, manual test checklist                       |
| Reinstall resets everything (new anonymous UID) | Link the anonymous account to Google sign-in (M7) so limits and pending changes restore |

## 12. Setup basics

1. `flutter create focusmeme --org com.yourname --platforms android`
2. Packages: `flutter_riverpod`, `go_router`, `firebase_core`, `firebase_auth`, `cloud_firestore`, `firebase_storage`, `shared_preferences`, `path_provider`, `wakelock_plus`, `connectivity_plus`, plus `google_sign_in` in M7. Native Kotlin uses `VideoView` + native image/GIF decoding for the meme overlay by default (`Media3`/`ExoPlayer` only if needed; ask first). Pre-approved native additions: `PACKAGE_USAGE_STATS`, accessibility service binding, launcher `<queries>`; INTERNET, wake-lock and network-state permissions come with the listed plugins.
3. Create a Firebase project, run `flutterfire configure`.
4. `main()`: `WidgetsFlutterBinding.ensureInitialized()`, `Firebase.initializeApp(...)`, `runApp(ProviderScope(child: App()))`.

## 13. Open questions

- Meme sourcing/licensing for release.
- Google sign-in linking (anonymous to Google) is planned for M7 so data survives reinstall; confirm before release.
