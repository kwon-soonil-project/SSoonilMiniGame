# Task 7 implementation report

## Commit

- Implementation commit: `e5debad717df5f567c5ba6716e87d80ba986a8db` (`feat: synchronize liar game state in pinia`)
- Branch: `codex/liar-game-implementation`
- Worktree: `C:\Users\Administrator\Desktop\새 폴더 (2)\project\harness\.worktrees\liar-game-implementation`
- Push: not performed.

## Delivered

- Added typed Liar game public/private snapshot contracts, command actions, and strict allowlist sanitizers.
- Added Pinia game-state handling for shared public/private sequences in either delivery order, duplicate/stale sidecar rejection, REST reconnect replacement, invalid-payload snapshot recovery, and game-state removal on return to waiting.
- Preserved existing room/chat sequencing, recovery, command-rejection warnings, and connection behavior.
- Added a server-deadline timer helper that rounds up fractional seconds and relies solely on the supplied ISO deadline and current-time argument.

## RED evidence

From `frontend`:

```powershell
npm.cmd test -- roomStore.spec.ts deadlineClock.spec.ts
```

Actual result before the implementation: 8 room-store tests failed and the deadline suite could not resolve the missing `deadlineClock` module. Failures covered absent `snapshot.game`, missing `startGame`, missing `sendGameAction`, missing event ingestion, reconnect replacement, return-to-waiting removal, and the unknown-phase recovery contract.

After independent review, the added delayed-sidecar regression was also RED before its fix: public `GAME_STATE_CHANGED(2)`, unrelated public `CHAT_MESSAGE(3)`, then private `GAME_PRIVATE_STATE_CHANGED(2)` left `privateState` null.

## GREEN evidence

Focused frontend tests:

```powershell
npm.cmd test -- roomStore.spec.ts deadlineClock.spec.ts
```

Actual result: **2 files passed, 29 tests passed**.

Full frontend suite:

```powershell
npm.cmd test
```

Actual result: **12 files passed, 79 tests passed**. Node emitted only the pre-existing experimental `localStorage` warning caused by the test runtime.

Production type-check and build:

```powershell
npm.cmd run build
```

Actual result: `vue-tsc --noEmit` and Vite production build both completed successfully.

`git diff --check` reported no whitespace errors before the implementation commit. An independent scoped review found and verified the delayed private-sidecar ordering fix; no remaining findings were reported.

## Concerns and scope notes

- The client deliberately treats REST snapshots as authoritative after invalid/unknown game projections or sequence gaps; the server remains responsible for game deadlines and state transitions.
- No game UI components were added; Task 8 consumes these contracts.
- No backend changes, pushes, merges, or pull requests were performed.
- The unrelated pre-existing Task 6 ledger edit in `progress.md` was intentionally left unstaged.

## Fix Round 1

### Commit and scope

- Implementation commit: `d31b5930b4ad733b6f842fe3eb95e285a38bfc81` (`fix: harden liar game room synchronization`)
- Frontend-only changes: backend was not modified.
- Added the backend-aligned `canStart` snapshot field, game-event status transitions, `RETURN_TO_WAITING` action typing, bounded/deduplicated recovery buffers, and expanded sanitizer/state tests.

### RED evidence

From `frontend`:

```powershell
npm.cmd test -- roomStore.spec.ts deadlineClock.spec.ts
```

Actual result: **5 expected failures** before implementation. They showed that REST/event `canStart` was absent, non-null `GAME_STATE_CHANGED` left the room `WAITING`, duplicate synchronization frames replayed the first payload, and recovery accepted more than 100 buffered events instead of failing deterministically. The test batch also established the exact `RETURN_TO_WAITING` command envelope and nested malicious-payload sanitizer contract before the type/store changes.

### GREEN evidence

Focused frontend tests:

```powershell
npm.cmd test -- roomStore.spec.ts deadlineClock.spec.ts
```

Actual result: **2 files passed, 35 tests passed**.

Final full frontend suite:

```powershell
npm.cmd test
```

Actual result: **12 files passed, 85 tests passed**. The only output noise was the existing Node experimental `localStorage` warning from the test runtime.

Production type-check and build:

```powershell
npm.cmd run build
```

Actual result: `vue-tsc --noEmit` and the Vite production build both succeeded.

### Design notes and concerns

- `canStart` is allowlisted from REST with `false` as the invalid/missing-value default. Public room events update it only when their payload explicitly carries a boolean value.
- A non-null public game projection marks the room `PLAYING`; a null projection returns it to `WAITING` and clears all client-private game state. The room sequence remains solely owned by the existing sequencer.
- Recovery buffering is capped at 100 combined public/private entries, replaces duplicate entries within each channel by sequence, and sorts before replay. Public/private entries remain separate, so a same-sequence private sidecar is retained. Overflow fails recovery with a stable visible error rather than accumulating memory.
- During synchronization, event room IDs are checked against the target room before buffering; stale game sidecars are also prevented from attaching by the tracked latest public-game sequence. The STOMP envelope has no game-session ID, so session isolation is necessarily sequence-based within its validated room.
- No UI components, backend changes, push, merge, or pull request were performed. The unrelated Task 6 `progress.md` edit remains unstaged.
