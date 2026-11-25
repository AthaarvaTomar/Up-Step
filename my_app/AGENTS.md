# AGENTS.md: FocusMeme

Flutter (Material 3) + Riverpod + go_router + Firebase, with a native Kotlin blocker. Android first.
Source of truth: `PRD.md`. Milestone checklist: `TASKS.md`. If code and the PRD disagree, ask me before choosing.

## Who I am and how we work

I am a CSE student learning Flutter, Riverpod, Firebase and the app's logic while building this.

- During M0-M8, the focus is on core logic: the agent builds the native Kotlin, Dart logic, and plain stock Google Material 3 screens so the app works end-to-end. I write all Firestore rules and Firestore functions myself. I read and trace the logic and test on my phone.
- During M9, I rebuild and polish all Flutter UI myself. Default to guiding and reviewing my UI code rather than writing M9 UI for me, unless I explicitly ask.

## Task division

### Agent owns

- Kotlin: AccessibilityService, overlay, usage tracking, uninstall detection.
- MethodChannel contract, Gradle and manifest setup.
- Project scaffold, packages, config files.
- Riverpod providers and notifiers.
- Firebase Auth (including anonymous-to-Google linking in M7) and Storage download code. For Firestore: only the repository interfaces (method signatures and data shapes from PRD section 7) and stubs, never the Firestore calls or rules.
- Pomodoro schedule logic, limit rules, stats and streak logic, meme sync/cache/shuffle bags.
- Plain stock Google Material 3 screens in M0-M8 (per UI rules).
- Test checklists and code review of my M9 UI.
- Meme pipeline structure (manifest format, folders).

### I own

- Reading the logic you write, tracing data flow, and answering the check questions.
- Creating the Firebase project, running `flutterfire configure`, enabling Auth, Firestore, and Storage in the console.
- All Firestore security rules and Firestore read/write functions (repositories): limits (M1), sessions (M4), meme manifest read and Storage rules (M6), pending changes and stats (M7). You give me the interface, data shape and stub, and review my code.
- The limits `StreamProvider` in M1 (about 10 lines).
- Adding my SHA-1 fingerprint and enabling Google sign-in in the Firebase console (M7).
- Choosing memes, uploading them to Firebase Storage, checking licensing.
- Testing on my real Android phone and reporting results.
- All UI redesign, custom dark/AMOLED theming, navigation polish, `CustomPainter`, and animations in M9.

## Working loop

### M0-M8 (core logic + basic UI)

1. You build the logic and a plain stock Material 3 screen, then explain it (what it does, data flow, which providers the screen uses).
2. I read and trace the logic and answer the check questions.
3. I test on my phone against the checklist and report what breaks.
4. You fix reported bugs, and we tick the task in `TASKS.md`.

**Firestore pieces (any milestone):** you give me the repository interface, data shape and a stub; I write the Firestore code and rules; you review it and explain what is wrong and why; then we tick the task.

### M9 (UI enhancement)

1. I rebuild each screen with custom dark/AMOLED Material 3 UI.
2. I paste my code; you review it and explain what is wrong and why, then show the fix.
3. I test on my phone and report results.

## Explanation rules

After each task, give:

1. **What you built**, in plain language, in 5 lines or fewer.
2. **How data flows**, as a short diagram or numbered list.
3. **Key concepts**, each with a small unrelated example (Riverpod providers, streams, MethodChannel, accessibility events, etc.).
4. **Where I connect my UI**, naming the exact providers/methods my screens watch or call.
5. **A 3-question check** so I can test my understanding. Wait for my answers only if I ask.
6. **Assumptions** you made, and a manual test checklist for my phone.

Comment non-obvious logic in code (the "why", not the "what"). Keep files small and named by feature.

## My learning path (suggest concepts just before they are needed)

- M0-M8: Riverpod providers (`StreamProvider`, `NotifierProvider`), `ref.watch` vs `ref.read`, Firestore security rules and queries (I write them), MethodChannel bridge, native service lifecycle, and app architecture.
- M9: Flutter widgets (`StatelessWidget` vs `StatefulWidget`, `Column`/`Row`/`ListView`, `TextField`, `Scaffold`), `go_router` navigation, Material 3 theming, `WidgetsBindingObserver`, `CustomPainter` (usage rings), and animations.

If I ask to take on more logic, the self-contained options are the Pomodoro schedule math (M4) and the meme shuffle bag (M6).

## UI rules

- **M0-M8 policy:** stock Google Material 3 only (`useMaterial3: true`, `ColorScheme.fromSeed`, follow system light/dark mode). Standard widgets only (`Scaffold`, `AppBar`, `ListView`, `ListTile`, `Card`, `Switch`, `TextField`, `FilledButton`, `NavigationBar`, `SegmentedButton`, `LinearProgressIndicator`). No custom theme, fonts, colors, animations, or `CustomPainter`. One plain file per screen; logic stays in providers, never inside widgets.
- **Functional exceptions in M0-M8:** pure-black Clock mode (battery) and the native meme overlay (black background, video or image).
- **M9 target:** dark-first, AMOLED-friendly, Material 3. Meme overlay and clock mode matter most.
- Review my M9 widgets when I paste them. Explain what is wrong and why.

## Architecture rules

- The native blocker reads ONLY local storage and files. Never Firestore, never network, at block time.
- Data flow is local-first: UI -> local store + MethodChannel `sync` -> native, then Firestore as backup/sync (written after local; a Firestore failure must never stop native from updating). First-time anonymous sign-in requires network once; Firestore offline persistence works afterward.
- MethodChannel: `focusmeme/blocker`. Methods: `sync`, `status`, `getInstalledApps`, `getUsageStats`, `openAccessibility`, `openUsageAccess`, `openBattery`, `openAutostart`. Do not add methods without asking.
- `sync` is the only way Dart writes native data; payload: limits, strictSchedule, settings (`farewellMemeOn`, `wifiOnlySync`). Native keeps its own SharedPreferences. Do not read Flutter's `shared_preferences` file from Kotlin (prefixed keys); use the plugin only for UI-only state.
- Native picks memes and owns the per-section shuffle bags (block time reads local data only). Dart only downloads files and the manifest into `getApplicationSupportDirectory()/memes/<section>/` (matches native `filesDir`). Bundled memes live in Android `res/raw` or `assets`, not Flutter assets.
- `getInstalledApps` uses Android `<queries>` (`ACTION_MAIN` + `CATEGORY_LAUNCHER`), not `QUERY_ALL_PACKAGES`.
- Native owns the Pomodoro schedule (absolute timestamps). Dart derives timer UI from `now` vs schedule, never a ticking counter.
- Overlay uses `TYPE_ACCESSIBILITY_OVERLAY` (no "draw over apps" permission) with Android native media playback (`VideoView` by default, `Media3`/`ExoPlayer` only if needed, plus native image/GIF decoding). Do not add `flutter_overlay_window` or use Flutter `video_player` inside the native overlay.
- Expose logic to UI through clearly named Riverpod providers with a short doc comment each.

## Locked product rules (see PRD section 4)

- Block ladder: 1st block of an app today = close + toast (`"<App> limit reached"` for daily limits, `"<App> blocked during focus"` for strict sessions); 2nd+ = close + meme. Per app, per day across both daily limits and strict sessions. Debounce ~1.5 s.
- Limit hit while inside the app blocks at that exact moment (counts as attempt #1), using an in-service timer for remaining time that pauses/cancels on app switch or `ACTION_SCREEN_OFF`, and re-evaluates the foreground app on `ACTION_USER_PRESENT`/`ACTION_SCREEN_ON` (no window event may fire when the screen wakes).
- Lowering a limit applies now; raising/removing applies next midnight (`pendingMinutes` / `pendingRemove`). Turning off `focusOnly` during an active focus session applies after the session (or next midnight).
- Pomodoro: strict only in focus phases; quitting needs 10 s wait + confirm, logged `broken`. Midnight-crossing sessions log under `startedAt`'s date.
- Meme section priority: `uninstall_attempt` > `repeat_offender` (5+ attempts today for that app) > `strict_session` > `late_night` (00:00-05:00) > `limit_hit` > `general`. Empty section falls back to `general`, then bundled assets. Per-section shuffle bags.
- Meme is unskippable until it ends (cap ~15 s).
- Uninstall farewell is non-blocking: plays only while the dialog is visible (Android kills the app on confirmation); never press Home, close the dialog, or block tapping confirm/cancel. The overlay is partial (small card or top strip, not touchable) so the buttons stay visible and tappable; tune placement on a real phone. Read window content only while an installer package is foreground; never store or send it.

## Code rules

- Dart: null-safe, one feature folder per area: `lib/{app,features/{limits,focus,memes,onboarding},data,native}`.
- Kotlin: query usage incrementally (only new events since last check); keep main-thread work minimal.
- Reset counters by date key, not timers. Handle midnight rollover and same-day service restarts.
- No new packages or permissions without asking, except those pre-approved: PRD section 12 packages (including `google_sign_in` in M7), `PACKAGE_USAGE_STATS`, accessibility service binding, launcher `<queries>`, and the INTERNET/wake-lock/network-state permissions that come with listed plugins. Use `VideoView` for the meme overlay by default; ask before adding Media3.

## Testing

- Test on a real Android phone; emulators do not handle accessibility services well.
- Every task ends with a manual test checklist: duplicate events, app killed mid-session, midnight rollover, keyboard/system UI windows, OEM battery killers.

## Workflow

1. Use Planning mode. Show the plan first and wait for my approval.
2. One milestone at a time (`PRD.md` section 10 and `TASKS.md`).
3. Finish with the explanation block, then tell me what to check/test next (or what UI to build in M9).

## Milestone tasks

| #   | Agent does                                                                                                                                      | I do                                                                                     |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| M0  | Create project (Android only), add PRD packages, folder structure, wire `ProviderScope`, stock `NavigationBar` shell; walk me through Firebase  | Create the Firebase project, run setup commands, get the blank app running on my phone   |
| M1  | Anonymous auth, limits repository interface + stub, providers over it, `getInstalledApps` stub, basic Limits screen | Write Firestore rules, the limits repository and the limits `StreamProvider`; trace providers; test live updates with the Firebase console |
| M2  | Kotlin blocker per PRD 4.1-4.5, MethodChannel, native meme overlay with bundled memes, usage tracking; phone test checklist                     | Install, run the checklist, report what breaks                                           |
| M3  | Permission-status providers (refresh on resume), MethodChannel calls, basic Onboarding screen + Home banner                                     | Toggle permissions in Settings and confirm live status updates on resume                 |
| M4  | Pomodoro schedule logic (Dart + native focus windows), notifier, session repository interface + stub, basic Focus screen | Write the Firestore session functions; start/kill tests, confirm blocking in focus and unblocking in breaks |
| M5  | Clock-mode logic (minute ticks, `wakelock_plus`, burn-in shift), basic pure-black Clock screen                                                  | Run for 30 minutes on phone and note battery drop                                        |
| M6  | Meme manifest format, download, cache, native shuffle bags, basic Settings screen | Write Storage rules and the manifest read function; choose/upload memes to Storage, add a meme to the manifest and confirm sync |
| M7  | Limit-edit rules (`pendingMinutes`/`pendingRemove`), `getUsageStats`, stats & streak logic, anonymous-to-Google linking, basic Home screen | Write the pending/stats Firestore functions; add SHA-1 and enable Google sign-in; test raising/removing limits across midnight, the streak, and reinstall restore |
| M8  | Uninstall farewell (`uninstall_attempt`), non-blocking overlay, disclosure text, bug fixes                                                      | Test uninstall from launcher & Play Store, run full checklist final pass                 |
| M9  | Review pasted UI screens and polish native overlay if asked                                                                                     | Redesign dark/AMOLED Material 3 theme, rebuild screens, `CustomPainter` rings, animations |
