# Task 5 report — liar voting, scoring, and departures

## Scope

- Extended the liar state machine with discussion closure, private voting and one revote, accused-liar guessing, round/game result phases, score deltas, deadlines, roster synchronization, and departure invalidation.
- Added `LiarScoring` and focused tests for majority discussion, target/duplicate validation, ties, abstention, normalized aliases, guess timeout, liar/citizen departure, invalidated rounds, and next-round roster synchronization.
- Public projections expose only submission status until a round result; vote targets remain state-internal.

## RED/GREEN evidence

- RED: `backend\\.\\gradlew.bat test --tests "com.minigame.platform.game.domain.liar.LiarVotingTest" --tests "com.minigame.platform.game.domain.liar.LiarDepartureTest"`
  - Failed as expected before Task 5 implementation because `VOTING`, `REVOTING`, and `ROUND_RESULT` did not exist.
- GREEN (focused): same command — `BUILD SUCCESSFUL`.
- GREEN (liar package): `backend\\.\\gradlew.bat test --tests "com.minigame.platform.game.domain.liar.*"` — `BUILD SUCCESSFUL`.
- GREEN (backend): `backend\\.\\gradlew.bat test` — `BUILD SUCCESSFUL`.

## Files

- `backend/src/main/java/com/minigame/platform/game/domain/liar/LiarGameModule.java`
- `backend/src/main/java/com/minigame/platform/game/domain/liar/LiarGameState.java`
- `backend/src/main/java/com/minigame/platform/game/domain/liar/LiarPhase.java`
- `backend/src/main/java/com/minigame/platform/game/domain/liar/LiarProjection.java`
- `backend/src/main/java/com/minigame/platform/game/domain/liar/LiarScoring.java`
- `backend/src/test/java/com/minigame/platform/game/domain/liar/LiarVotingTest.java`
- `backend/src/test/java/com/minigame/platform/game/domain/liar/LiarDepartureTest.java`

## Commit

`69bc479` (`feat: complete liar voting and scoring rules`)

## Notes / concerns

- Cumulative score ownership remains in the existing `GameRuntime`; this task produces only per-transition deltas, as defined by the Task 3 contract. Task 6 is responsible for applying deltas, participant promotion, and session completion.
- The first Gradle invocation required an approved wrapper download; all recorded test commands subsequently completed successfully.
