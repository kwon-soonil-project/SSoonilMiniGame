# Task 6 implementation report

## Commit

- Implementation commit: `d7034aafdab9dd534e3f400798703f4e5005f89b` (`feat: orchestrate liar games over room websocket`)
- Branch: `codex/liar-game-implementation`
- Worktree: `C:\Users\Administrator\Desktop\새 폴더 (2)\project\harness\.worktrees\liar-game-implementation`
- Push: not performed, as requested.

## Implemented files

Created:

- `backend/src/main/java/com/minigame/platform/game/application/GameApplicationService.java`
- `backend/src/main/java/com/minigame/platform/room/application/LockedRoomResult.java`
- `backend/src/test/java/com/minigame/platform/game/application/GameApplicationServiceTest.java`

Modified:

- `backend/src/main/java/com/minigame/platform/game/domain/GameRuntime.java`
- `backend/src/main/java/com/minigame/platform/room/domain/Room.java`
- `backend/src/main/java/com/minigame/platform/room/domain/RoomEvent.java`
- `backend/src/main/java/com/minigame/platform/room/domain/Participant.java`
- `backend/src/main/java/com/minigame/platform/room/application/ActiveRoomRepository.java`
- `backend/src/main/java/com/minigame/platform/room/adapter/out/memory/InMemoryActiveRoomRepository.java`
- `backend/src/main/java/com/minigame/platform/room/application/RoomApplicationService.java`
- `backend/src/main/java/com/minigame/platform/room/adapter/in/realtime/RoomCommandGateway.java`
- `backend/src/main/java/com/minigame/platform/room/adapter/in/web/RoomWebDtos.java`
- `backend/src/main/java/com/minigame/platform/shared/config/RoomConfig.java`
- `backend/src/test/java/com/minigame/platform/room/adapter/in/realtime/RoomCommandGatewayTest.java`
- `backend/src/test/java/com/minigame/platform/room/adapter/in/web/RoomControllerTest.java`

No frontend files were changed.

## RED evidence

Command from `backend`:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest" --tests "com.minigame.platform.room.adapter.in.realtime.RoomCommandGatewayTest"
```

Actual result: `BUILD FAILED`, exit code `1`, during `compileTestJava`. The 13 compile errors named the intentionally missing Task 6 contracts, including `GameApplicationService`, `ActiveRoomRepository.withRoomValue`, `Room.Snapshot.gameRuntime`, and the game-aware `RoomCommandGateway` constructor. This was the expected RED caused by the unimplemented feature, not by a test assertion typo.

The first sandboxed invocation could not download the pinned Gradle distribution because network access was denied. The same command was rerun with the approved Gradle execution permission; the failure above is the recorded feature RED.

## GREEN evidence

Focused application and STOMP command tests:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest" --tests "com.minigame.platform.room.adapter.in.realtime.RoomCommandGatewayTest"
```

Actual result: `BUILD SUCCESSFUL in 5s`; 4 actionable tasks, 3 executed and 1 up-to-date.

Focused REST snapshot test:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.room.adapter.in.web.RoomControllerTest"
```

Actual result: `BUILD SUCCESSFUL in 6s`.

Expanded lifecycle test for `<4` handoff, persisted `GAME_RESULT`, ranks/round counts, recent content, and return to waiting:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest"
```

Actual result: `BUILD SUCCESSFUL in 3s`.

The first clean full-suite run found one existing atomicity regression: `RoomApplicationServiceTest.failedSaveRollsBackPreparedPasswordHash` failed because the new room-level password-protected lobby metadata was not rolled back with a failed save. The exact regression was kept as the failing test, the root cause was fixed by rolling the room flag back in the existing save-failure boundary, and the focused rerun passed:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.room.application.RoomApplicationServiceTest.failedSaveRollsBackPreparedPasswordHash" --rerun-tasks
```

Actual result: `BUILD SUCCESSFUL in 4s`.

Final fresh, uncached backend verification:

```powershell
.\gradlew.bat cleanTest test --rerun-tasks
```

Actual result: `BUILD SUCCESSFUL in 25s`; 5 actionable tasks executed. JUnit XML totals: **173 tests, 0 failures, 0 errors, 0 skipped** across 31 test result files.

`git diff --check` reported no whitespace errors. Gradle emitted only the pre-existing deprecated-API test compilation note and the JVM class-data-sharing warning.

## Architecture decisions

- `Room` owns the optional `GameRuntime`, room status transition, the last 20 used content IDs, and every room sequence increment. `GameRuntime` owns session-scoped state, score/nickname/round participation data, used content, and bounded action request idempotency.
- `LockedRoomResult<T>` and `withRoomValue` extend the existing repository lock boundary without weakening existing `withRoom` callers. Start, action, expiry, round-boundary synchronization, and participant departure all mutate game state while holding the same per-room lock used by room commands.
- A logical game mutation publishes exactly one public `GAME_STATE_CHANGED`. Any `GAME_PRIVATE_STATE_CHANGED` sidecars reuse that public sequence. Spectator promotions receive preceding public room sequences, so public consumers do not observe sequence gaps caused by private-only messages.
- Public projections are rebuilt from an explicit allowlist (`gameType`, phase/round/deadline, public hints/submissions/result, scores). Role, word, liar identity, and vote target data are never copied into the public map. Private projections are sent only to the relevant actor queue.
- Start selects content before taking the room lock, then revalidates current host/readiness/settings inside the lock, persists the `RUNNING` session, attaches the runtime, publishes projections, replaces the deadline, and publishes the lobby status change.
- Deadline replacement cancels the prior token. Expiry rechecks session, round, phase version, and exact deadline inside the room lock, so cancelled or late callbacks become silent no-ops.
- `DISCUSSION_END_PROPOSE` authorization is checked against the current locked `Room.hostId`, preserving host-transfer semantics without leaking room authority into `GameModule`.
- Non-final `ROUND_RESULT` expiry promotes spectators in join order, then calls `GameRuntime.synchronizePlayers` followed by `GameModule.synchronizePlayers` with the latest active roster. The last round does not promote spectators. A roster below four hands off to `GAME_RESULT` instead of opening a round.
- Entering `GAME_RESULT` persists shared ranks and per-player round counts before publishing the result projection. Host `RETURN_TO_WAITING` or the game-result deadline removes the runtime, retains recent content, cancels scheduling, resets readiness, and publishes the waiting-room lobby state.
- Both manual leave and presence grace expiry already converge on `RoomApplicationService.leave`; it now invokes `GameApplicationService.participantLeft` before `Room.leave`, yielding consecutive game and `PLAYER_LEFT` sequences under one lock.
- Password-protected state is mirrored in the room snapshot so game-originated lobby upserts retain the established complete lobby DTO. The flag is rolled back with the hash when room persistence fails, preserving the pre-existing create atomicity test.

## Concerns and scope notes

- Runtime and deadline ownership remain process-local by design; restart recovery of an active in-memory game is explicitly outside this vertical slice. Existing startup recovery still interrupts persisted orphan `RUNNING` sessions.
- Game-originated lobby upserts now carry complete room metadata, including `passwordProtected`, but their payload is intentionally assembled without exposing password material.
- The requested no-subagent constraint prevented delegated code review; the Task 6 plan/ADR checklist, focused tests, full regression suite, and diff audit were performed locally.
- No frontend tasks, branch merge, push, or pull request were performed.

## Fix Round 1

### Commit and files

- Implementation commit: `3c3f89f7bb011ec9c0d229430ad58128046fce1b` (`fix: harden liar game orchestration failures`).
- Modified production files: `JpaGameSessionAdapter`, `GameApplicationService`, `GameSessionPort`, `GameRuntime`, `RoomCommandGateway`, `RoomApplicationService`, and `Room`.
- Modified tests: `GamePersistenceIntegrationTest`, `GameApplicationServiceTest`, `GameRuntimeTest`, `RoomCommandGatewayTest`, `RoomApplicationServiceTest`, and `RoomTest`.
- No frontend files were changed and no push was performed.

### RED evidence

Initial contract RED from `backend`:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest" --tests "com.minigame.platform.game.domain.GameRuntimeTest" --tests "com.minigame.platform.room.adapter.in.realtime.RoomCommandGatewayTest" --tests "com.minigame.platform.game.adapter.out.persistence.GamePersistenceIntegrationTest"
```

Actual result: `BUILD FAILED` during `compileTestJava` with five expected missing-contract errors for `GameSessionPort.interrupt(UUID, Instant)`, `GameApplicationService.roomClosed(Room, Instant)`, and `GameRuntime.snapshot()`.

Failure-safe room-close RED:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest" --tests "com.minigame.platform.room.application.RoomApplicationServiceTest"
```

Actual result: `BUILD FAILED`; 32 tests executed and `closing_interrupt_failure_does_not_consume_leave_and_retry_can_close_room` failed because the original leave path committed `CLOSED` before the fallible session interruption.

Strict deadline-cancellation RED:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest.room_close_does_not_report_success_until_deadline_cancellation_can_be_retried"
```

Actual result: `BUILD FAILED`; 1 test executed and 1 failed because cancellation exceptions were originally swallowed during room closure.

### GREEN evidence

Focused application, room, gateway, controller, domain, and persistence verification:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest" --tests "com.minigame.platform.room.application.RoomApplicationServiceTest" --tests "com.minigame.platform.room.domain.RoomTest" --tests "com.minigame.platform.game.domain.GameRuntimeTest" --tests "com.minigame.platform.room.adapter.in.realtime.RoomCommandGatewayTest" --tests "com.minigame.platform.room.adapter.in.web.RoomControllerTest" --tests "com.minigame.platform.game.adapter.out.persistence.GamePersistenceIntegrationTest"
```

Actual result: `BUILD SUCCESSFUL in 15s`; JUnit XML totals: **94 tests, 0 failures, 0 errors** across 7 suites.

Fresh persistence rerun, including the session-specific atomic interrupt coverage:

```powershell
.\gradlew.bat test --rerun-tasks --tests "com.minigame.platform.game.adapter.out.persistence.GamePersistenceIntegrationTest"
```

Actual result: `BUILD SUCCESSFUL in 14s`; all 4 actionable tasks executed.

Final clean backend verification after implementation commit:

```powershell
.\gradlew.bat clean test
```

Actual result: `BUILD SUCCESSFUL in 25s`; all 5 actionable tasks executed. JUnit XML totals: **196 tests, 0 failures, 0 errors, 0 skipped** across 31 suites. `git diff --check` reported no whitespace errors; output contained only the existing deprecated-test-API note and JVM class-data-sharing warning.

### Architecture decisions

- A normalized `GameAction` is constructed before authorization; current-room-host authorization is applied to the normalized type. STOMP map validation converts null nested data into a private `COMMAND_REJECTED` instead of allowing an NPE to escape.
- Start now uses a locked validation token, performs content I/O outside the lock, and then revalidates the exact host, membership, status, readiness, settings, roster, rounds/category, and recent-content basis under the room lock. In-flight and already-processed duplicates do not query content.
- New deadlines are scheduled before phase mutation and installed before the prior token is cancelled. Session completion, schedule creation, and request-id marking are ordered so a failure leaves the prior state/deadline and permits retry. Broker publication is explicitly best-effort; reconnect REST snapshots are authoritative recovery.
- Spectator promotion is previewed rather than mutated before schedule/session work. The latest promoted roster is passed to both runtime and module synchronization only after critical work succeeds. Entering `GAME_RESULT` persists prospective final scores before committing that phase.
- `Room.Snapshot` owns an immutable `GameRuntime.Snapshot`; requester-specific projection and `canStart` are computed inside `ActiveRoomRepository.withRoomValue`, producing one coherent room sequence/state/score view without exposing a live runtime after unlock.
- Lifecycle return request ids are retained by `Room`, so a duplicate `RETURN_TO_WAITING` remains a successful no-op after runtime removal. Final return/expiry resets readiness and converts waiting spectators back to active seats in join order, publishing spectator changes before the empty-game state.
- Room leave uses a request-aware, read-only close preflight. A specific `RUNNING` session is atomically changed to `INTERRUPTED`, and its deadline must cancel successfully, before `Room.leave` may commit `CLOSED` and before repository removal. Failures leave the leave request unconsumed for retry and never affect unrelated sessions.
- Recent content remains capped to the newest 20 ids, and selection retries without exclusions only when the exclusion-aware query cannot fill all rounds.

### Concerns and scope notes

- Runtime/deadline ownership remains intentionally process-local; reconnect recovery uses the locked REST snapshot, while process restart recovery continues to interrupt persisted orphan `RUNNING` sessions.
- Broker delivery is non-authoritative and may lose an individual frame during an outage; gameplay remains scheduled and committed, and clients reconcile through the requester-specific snapshot rather than blocking the room lock on broker availability.
- No frontend work, subagent delegation, push, merge, or pull request was performed.

## Fix Round 2

### Commit and files

- Implementation commit: `48c5a4906e8e66e96daa2c2bc8f47cb81ec66239` (`fix: retry failed game deadlines`).
- Modified production files: `SpringGameScheduler`, `GameApplicationService`, and `RoomApplicationService`.
- Modified tests: `SpringGameSchedulerTest` and `GameApplicationServiceTest`.
- No frontend files were changed and no push was performed.

### RED evidence

Repeating scheduler contract RED from `backend`:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.adapter.out.scheduling.SpringGameSchedulerTest"
```

Actual result: `BUILD FAILED`; 4 tests executed and all 4 failed with `UnsupportedOperationException` because production still used the one-shot `TaskScheduler.schedule` path instead of the requested fixed-delay watchdog.

Start precommit-ordering RED:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest"
```

Actual result: `BUILD FAILED`; 24 tests executed and 2 failed. `start_scheduler_failure_creates_no_session_and_same_request_can_retry` exposed persistence before scheduling, while `start_session_failure_leaves_room_unstarted_and_same_request_can_retry` exposed that no prepared schedule existed to cancel and rollback still depended on `sessions.interrupt`.

Composed real-close publication RED:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.application.GameApplicationServiceTest.real_close_*"
```

Actual result: `BUILD FAILED`; 3 tests executed and 1 failed. The real room/game composition completed interruption and deadline cancellation, but a public room-event exception escaped after `Room.leave`, preventing repository cleanup.

### GREEN evidence

Focused scheduler verification:

```powershell
.\gradlew.bat test --tests "com.minigame.platform.game.adapter.out.scheduling.SpringGameSchedulerTest"
```

Actual result: `BUILD SUCCESSFUL in 2s`; callback exceptions were contained, the same deadline callback retried, and cancellation prevented subsequent attempts.

Focused scheduler, application, room-domain, room-application, and persistence verification:

```powershell
.\gradlew.bat test --rerun-tasks --tests "com.minigame.platform.game.adapter.out.scheduling.SpringGameSchedulerTest" --tests "com.minigame.platform.game.application.GameApplicationServiceTest" --tests "com.minigame.platform.room.application.RoomApplicationServiceTest" --tests "com.minigame.platform.room.domain.RoomTest" --tests "com.minigame.platform.game.adapter.out.persistence.GamePersistenceIntegrationTest"
```

Actual result: `BUILD SUCCESSFUL in 15s`; all 4 actionable tasks executed. JUnit XML totals: **71 tests, 0 failures, 0 errors, 0 skipped** across 5 suites.

Final clean backend verification:

```powershell
.\gradlew.bat clean test
```

Actual result: `BUILD SUCCESSFUL in 26s`; all 5 actionable tasks executed. JUnit XML totals: **200 tests, 0 failures, 0 errors, 0 skipped** across 31 suites. `git diff --check` reported no whitespace errors; output contained only the existing deprecated-test-API note and JVM class-data-sharing warning.

### Architecture decisions

- `SpringGameScheduler` now registers a cancellable fixed-delay watchdog beginning at the exact deadline, or at the injected clock instant for an overdue deadline. It retries every second, contains callback exceptions so Spring does not terminate the repeating task, and relies on the existing session/round/phase-version/deadline token check as the authoritative stale-attempt gate.
- Successful expiry installs the replacement watchdog before cancelling the prior one. Tests fire the recorded scheduler task itself: the first prepare-schedule or session-completion attempt fails, the original task remains active, and its next retry advances the phase and cancels that original task without a direct second `service.expire` call.
- Start precommit work is ordered as deadline registration, then `RUNNING` session persistence, then deterministic locked room/runtime attachment and schedule-handle installation. A scheduling failure creates no session; a persistence failure cancels the prepared watchdog. The impossible returned-session-id mismatch branch and all start rollback calls to `sessions.interrupt` were removed.
- Close orchestration still performs player transition, session-specific `RUNNING` to `INTERRUPTED`, and strict deadline cancellation before consuming the leave request. Room and lobby publications after `Room.leave` are best-effort, so broker failure cannot prevent removal of a closed room.
- Composed tests use the real `GameApplicationService`, `RoomApplicationService`, room runtime, and leave path. They prove the target session is interrupted, the current watchdog is cancelled, the room is removed, and an unrelated running session remains untouched; interrupt and cancellation failures leave the same leave request retryable.

### Concerns and scope notes

- A persistently failing expiry logs once per one-second retry until recovery or cancellation. This is intentionally bounded per active room/deadline and favors automatic gameplay recovery; production log aggregation should alert on sustained repetitions.
- Runtime/deadline ownership remains process-local, and restart recovery continues to interrupt persisted orphan `RUNNING` sessions.
- No frontend work, subagent delegation, push, merge, or pull request was performed.
