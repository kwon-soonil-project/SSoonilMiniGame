# Liar game final-review fix report

## Scope and commit

- Branch: `codex/liar-game-implementation`
- Worktree: `C:\Users\Administrator\Desktop\새 폴더 (2)\project\harness\.worktrees\liar-game-implementation`
- Implementation commit: `9ef7cd49b475cd61c90cd386c65c757a0943f555` (`fix: address liar game final review findings`)
- Push, merge, reset, and discard: not performed.

## Important findings resolved

1. **Actor-scoped idempotency.** `GameRuntime` now keys processed actions by actor and request UUID, so two players may legitimately use the same publicly observable request ID while each actor's duplicate remains idempotent.
2. **Unsupported-start side effects.** Start tokens for non-Liar or unregistered game types fail with `GAME_TYPE_UNSUPPORTED` before content selection, deadline registration, or session persistence.
3. **Persisted-start compensation.** If a room changes after a `RUNNING` session is stored but before the runtime attaches, the prepared schedule is cancelled, that exact session is interrupted, the room remains waiting, and the public boundary reports `GAME_START_STATE_CHANGED`.
4. **Departure-safe hint history.** Submitted hints and their original public order survive departure; only unsubmitted future turns are removed, and a departing current hinter advances through an explicit skipped turn without corrupting the hint index.
5. **Correct Liar comeback semantics.** A correct final guess is a Liar win worth two points, distinct from a three-point survival; a timeout or wrong answer is a citizen win.
6. **Authoritative revote and hint status.** Public projections now carry ordered `SUBMITTED`/`SKIPPED` hint statuses and expose exactly the first-vote tie candidates only during `REVOTING`.
7. **Phase-gated result projection.** Liar identity and answer appear only in result phases. `GAME_RESULT` adds allow-listed final entries with nickname, score, competition rank, and rounds played; the frontend strips nested or out-of-phase secrets.
8. **Final-roster consistency.** The prospective latest roster is included in session completion before the final phase commits, then applied to runtime scores and room spectator promotions. New final participants persist and render at zero without losing departed players' ranks.
9. **Host and UI contract alignment.** Only the current host sees discussion-propose and return-to-waiting actions; realtime spectator changes update voting/readiness eligibility; revotes use authoritative candidates; result copy distinguishes Liar survival from comeback; E2E expectations match the rendered outcome.

## Minor findings resolved

- **Minor A — authoritative realtime `canStart`.** Eligibility-changing room events receive `canStart` from the same `GameApplicationService.canStart(Room.Snapshot)` calculation used by REST, including category-pack content availability. The focused room-service regression covers join, ready, settings, host transfer, and leave payloads.
- **Minor B — persistence-test isolation.** `GamePersistenceIntegrationTest` deletes `game_participants` and then `game_sessions` before each test so a test that intentionally leaves an unrelated session `RUNNING` cannot affect a later `interruptRunning` assertion.

## Additional compile/test correction found during resumption

Static compilation found three test-source errors in the interrupted diff: an assertion called `processedRequestCount()` on immutable `GameRuntime.Snapshot`, and two AssertJ wildcard-map `containsEntry` calls could not satisfy Java's captured generic type. The redundant snapshot assertion was removed while real vote-state assertions remain, and wildcard values are now asserted through `Map.get`.

The first runnable backend unit-test pass then found a real boundary defect: after persistence, `Room.startGame` could leak `RoomRuleViolation(GAME_START_CONDITION_NOT_MET)` even though the game application contract promised `GAME_START_STATE_CHANGED`. The existing regression was RED at 79/80; the minimal `RoomRuleViolation` translation made the same 80-test batch green while preserving session compensation.

## Verification

| Check | Result |
|---|---|
| Changed backend production static compile | 7 source files, `javac` exit 0 |
| Changed backend test static compile | 7 source files, `javac` exit 0; deprecated API note only |
| Changed-scope backend unit tests via temporary in-process JUnit runner | 7 containers, 80 found/started/successful, 0 failed |
| Full frontend Vitest JSON run | 26 suites, 106/106 tests passed |
| Frontend production build | `vue-tsc --noEmit` and Vite passed; 80 modules transformed |
| Playwright static discovery | 3 files, 9 tests |
| Whitespace | `git diff --check` clean before implementation commit |

The in-process backend runner used already cached dependencies and was removed after execution. It exists only as a fallback because the Gradle client cannot establish its local daemon channel on this host; it does not substitute for the still-pending full Gradle and PostgreSQL integration runs.

## Environmental blockers

- Focused Gradle test and a minimal `gradlew help --no-daemon` both fail before task execution with `java.io.IOException: Unable to establish loopback connection` (`java.net.SocketException: Invalid argument: connect`).
- Docker client is installed, but the daemon pipe `npipe:////./pipe/docker_engine` does not exist. PostgreSQL Testcontainers, packaged image/Compose readiness, and live Playwright E2E therefore remain unexecuted.
- The frontend test runtime continues to emit its pre-existing experimental `localStorage` warning. The manual JUnit fallback emits expected no-SLF4J-provider and Mockito dynamic-agent warnings; neither produced test failures.

## Secrecy and phase-gating audit

- Liar role/word remain private before result phases.
- Public hints contain only actor IDs, submitted text, and explicit submitted/skipped status.
- Revote candidates exist only in `REVOTING`.
- Liar ID, answer, round result, and final rankings are accepted/rendered only in their result phases.
- Final-score entries are rebuilt from an explicit field allowlist, so extra nested role data is discarded.

## Independent re-review follow-up

While tracing the original findings in code, the independent re-review identified one additional atomicity risk. Room mutations were committed before the content-backed `canStart` value was derived. If that lookup threw, the caller received a failure although the mutation and idempotency record remained, and retrying could become a no-op without recovering the lost realtime event.

Commit `8fe718c` routes REST snapshots and eligibility-changing event payloads through a fail-closed `canStartSafely` boundary. Successful lookups retain the authoritative content-aware value. Lookup failures are logged and return `false`, so accepted room state and its event remain consistent while the start control stays disabled. A regression test covers accepted readiness state, one `PLAYER_READY_CHANGED(canStart=false)` event, and idempotent retry without duplicate publication.

`git diff --check` passed. The focused Gradle test was attempted both inside the sandbox and with elevated execution. The elevated attempt again failed before task execution with `java.io.IOException: Unable to establish loopback connection`; therefore this new regression test is not recorded as executed.

A fresh limited review of commit `8fe718c` found no Critical, Important, or Minor issues. It judged all original nine Important and two Minor findings mapped to code and regression tests, while explicitly noting that this was not a new full-branch audit. Merge readiness therefore remains conditional on the blocked Gradle, PostgreSQL, and packaged Playwright verification.
