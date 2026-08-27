# Task 8 report — responsive liar game UI

## Implementation commit

- Commit: `b31ba5e` — `feat: add responsive liar game experience`
- Scope: frontend only; no backend files changed.

## Delivered files

- `frontend/src/features/games/GameShell.vue`
- `frontend/src/features/games/liar/LiarGameView.vue`
- `frontend/src/features/games/liar/RoleRevealPanel.vue`
- `frontend/src/features/games/liar/HintPanel.vue`
- `frontend/src/features/games/liar/DiscussionPanel.vue`
- `frontend/src/features/games/liar/VotePanel.vue`
- `frontend/src/features/games/liar/LiarResultPanel.vue`
- `frontend/src/features/games/liar/LiarGameView.spec.ts`
- `frontend/src/features/room/RoomView.vue`
- `frontend/src/features/room/RoomView.spec.ts`
- `frontend/src/features/room/ParticipantList.vue`
- `frontend/src/features/room/RoomSettingsPanel.vue`

## Evidence

1. RED: `npm.cmd test -- RoomView.spec.ts LiarGameView.spec.ts` failed before implementation because the liar view was absent and the host start control did not exist.
2. Focused GREEN: `npm.cmd test -- RoomView.spec.ts LiarGameView.spec.ts` passed: 2 files, 18 tests.
3. Full frontend test: `npm.cmd test` passed: 13 files, 95 tests.
4. Production build: `npm.cmd run build` passed (`vue-tsc --noEmit` and Vite production build).
5. `git diff --check` was clean before the implementation commit.

## Requirement coverage

- WAITING renders host-only `게임 시작`, enabled only by `canStart`; non-host ready controls remain.
- PLAYING renders `GameShell`, keeps participants, chat, connection errors, and makes settings read-only.
- Role reveal displays a citizen word only from private state; liar role/category never render a word. Public state is not used as a secret source.
- Exact accessible game actions are wired to typed store actions. Hint turn, submission, reconnect, vote self-exclusion, and phase-only polite live announcements are covered by component tests.
- The shell uses a right sidebar at desktop width, collapses below 900px, and hides desktop chat at mobile width so the existing RoomView chat drawer retains Escape/focus-trap/focus-return behavior.

## Concerns

- Vitest emits the pre-existing Node warning that localStorage needs `--localstorage-file`; it does not affect the passing tests.
- CSS breakpoint behavior was verified by component/build coverage rather than a browser screenshot test.
- `.superpowers/sdd/liar-game-implementation-plan/progress.md` was already modified in the worktree and was intentionally left out of the implementation commit.

## Fix Round 1 — backend action contract and closed-room guards

- Fix commit: `d8f4398` — `fix: align liar game action envelopes`
- Scope: frontend only; no backend files changed.

### Root cause and coverage

- The initial Task 8 UI used stale action-data field names. The backend `LiarGameModule` parses `hint`, `agree`, `targetActorId`, and `answer`; the UI now sends those exact envelopes.
- A mounted `RoomView` regression test exercises the game UI through `sendGameAction` and asserts `HINT_SUBMIT`, `DISCUSSION_END_VOTE`, `VOTE_SUBMIT`, `REVOTE_SUBMIT`, and `LIAR_GUESS_SUBMIT` envelopes.
- Voting is conservatively disabled while the private sidecar is absent, after the current actor appears in public `submittedPlayerIds`, and after private `voteSubmitted` arrives.
- `CLOSED` has an explicit display state and never renders the waiting-room start or ready controls.

### Fresh evidence

1. RED: `npm.cmd test -- RoomView.spec.ts LiarGameView.spec.ts` failed with the old vote payload (`targetId`), absent-sidecar vote enablement, stale outgoing payloads, and CLOSED incorrectly rendering the start control.
2. Focused GREEN: `npm.cmd test -- RoomView.spec.ts LiarGameView.spec.ts` passed: 2 files, 21 tests.
3. Full frontend suite: `npm.cmd test` passed: 13 files, 98 tests.
4. Production build: `npm.cmd run build` passed (`vue-tsc --noEmit` and Vite production build).
5. `git diff --check` was clean before committing the fix.

### Fix-round concern

- Vitest still emits the existing Node localStorage warning; no test failure or build error resulted.
