# Handoff — Session 11 (2026-09-09, continuation of session 10)

## 1. Goals

Session 10 ended blocked on its own next-step #1: confirm its 6 code-reviewed-only fixes (2 DB
tests + 4 scanner cleanups) actually build and pass, since the sandbox's Gradle loopback wall had
blocked every task including plain compilation. This session's job was to check whether that wall
was still up and, if not, get real green evidence.

## 2. Current State

- **The Gradle loopback wall from session 10 is gone.** `testDebugUnitTest --rerun-tasks` ran
  clean on the first try this session — not a sandbox-networking issue after all, or it cleared on
  its own between sessions.
- **All 6 of session 10's fixes are now build-confirmed, not just code-reviewed**, plus 2 more real
  bugs found and fixed via `debugging` while verifying:
  - `testDebugUnitTest --rerun-tasks`: **286/286 pass, 0 failures** (fresh XML count, not the
    runner's summary).
  - `connectedDebugAndroidTest --rerun-tasks` on the real `pokedex_test` AVD: **20/20 pass, 0
    failures** (fresh `test-result.textproto`, not Gradle's own task exit code — see below).
- **Real bug #1 (JVM)**: `ScannerScreenTest`'s new degenerate-aspect-ratio test (added session 10,
  never actually run until this session) asserted `rect.bottom == 49` for a `rawHeight=50`
  degenerate crop. Root cause: the test wrongly assumed `bottom` clamps the same way `top` does.
  It doesn't, by design — `ImageRect`'s own doc comment says `right`/`bottom` are **exclusive**
  bounds matching `Bitmap.createBitmap`'s `(left, top, width, height)` contract, so `bottom == 50`
  (== `rawHeight`) is correct and maximal, not off-by-one. The implementation was right; the test's
  expectation was wrong. Fixed the assertions
  ([ScannerScreenTest.kt:216-221](app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreenTest.kt:216)),
  not the production code.
- **Real bug #2 (androidTest)**: `Migration6to7Test`'s raw
  `INSERT INTO connecting_art_slot (id, groupId, slotIndex, owned) VALUES (...)` predates the
  `language` column (2026-07-15 language/lock/remarks feature) and was never updated — this exact
  test method had never executed live before (its own header comment said so). Since
  `ConnectingArtSlot.kt:28`'s `val language: String = "EN"` is a Kotlin-side default only (no
  `@ColumnInfo(defaultValue=...)`), Room's real DDL is `language TEXT NOT NULL` with no SQL
  default — the raw insert threw `SQLITE_CONSTRAINT_NOTNULL`. Fixed by adding `language` to the
  insert's column list and value
  ([Migration6to7Test.kt:169](app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration6to7Test.kt:169)).
- **Real environment bug, not a code bug**: the `pokedex_test` AVD hung on launch (near-zero CPU,
  no adb/console port ever bound, ~17 min with no progress). Root cause found in the emulator's own
  stdout log: GPU init tried host OpenGL, `opengl32sw` failed to load, fell back to software GL,
  which triggered a hung QEMU main-loop and a blocking crash-consent dialog that can never be
  clicked in a headless launch. Fixed by launching with `-gpu swiftshader_indirect -no-window`
  instead of the bare `-avd pokedex_test -no-snapshot-load` used in prior sessions — boots clean in
  ~15-20s. **Worth carrying into any future AVD launch in this sandbox** — the plain launch command
  is no longer reliable here.
- **`connectedDebugAndroidTest`'s own Gradle task result is still unreliable** — reproduced the
  documented `"Failed to receive the UTP test results"` UTP↔Gradle IPC glitch a 3rd time (task
  reports FAILED, real textproto shows 0 failures). Confirmed this project's own gaps.md guidance
  (read the textproto directly) is still the correct workaround, not stale advice.
- `/card` (Discord slash command) — still broken. Skyler said it's fine, deprioritized, not
  touched this session.

## 3. Active Files

- `app/src/test/java/com/skyler/pokedexbinder/ui/scanner/ScannerScreenTest.kt` — corrected the
  degenerate-aspect-ratio test's `bottom` assertions (49 → 50) and added an explanatory comment on
  the exclusive-bound convention.
- `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/Migration6to7Test.kt` — added
  `language` to the raw FK-cascade-test `INSERT` statement.

## 4. Changes Made (commits, chronological)

- **Nothing committed yet this session** — both fixes above are in the working tree, uncommitted.
  Session 10's own commit (`ea2b6f3`) was already pushed before this session started.

## 5. Failed Attempts

- Two emulator relaunches hung identically (host-GPU-init deadlock behind an unclickable crash
  dialog) before the root cause was found in the emulator's own stdout log and fixed with
  `-gpu swiftshader_indirect -no-window`. Not a wasted attempt in the "3 fixes, question the
  architecture" sense — same root cause diagnosed once, applied correctly on the first informed
  retry.

## 6. Next Steps

1. **Commit the two test fixes** (`ScannerScreenTest.kt`, `Migration6to7Test.kt`) — small, correct,
   verified green on real hardware/emulator. Not yet done this session; just verification work, no
   commit made.
2. **Carried from session 9/10, unchanged, still blocked on Skyler**: confirm `/card` (deprioritized
   per Skyler, not blocking); Scanner attempt #6 (physical ultrawide lens + guide-frame crop,
   `4715adf`, already pushed) still needs Skyler's real S26 Ultra +
   `adb logcat -s ScannerFocus:*` — **this is now the only outstanding item that genuinely requires
   real hardware**, per this session's own audit (DB tests and scanner code-cleanups are fully
   emulator/unit-test verifiable and now are verified).
3. **Crop-rect-vs-guide-box size mismatch** (gaps.md, still open) — needs real-device calibration
   numbers, unchanged.
4. **Card-grading spec** — still parked, untouched.
5. **Republish from the app** — `binder.json` was 16 days stale as of session 9's check; still
   unconfirmed whether a publish has run since.
