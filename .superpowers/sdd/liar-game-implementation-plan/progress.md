# SDD ledger — plan: .superpowers/liar-game-implementation-plan.md

Workspace: `C:/Users/Administrator/Desktop/새 폴더 (2)/project/harness/.worktrees/liar-game-implementation`
Branch: `codex/liar-game-implementation`
Plan source: `docs/portfolio-development-journal.md`, `Liar Game Vertical Slice Implementation Plan`
Scratch plan: `.superpowers/liar-game-implementation-plan.md` (mechanically extracted because the portfolio file contains an older plan with duplicate Task numbers)

## Baseline

- Backend: 112 tests passed after Docker Desktop started; the first attempt failed only because `com.docker.service` was stopped.
- Frontend: 11 files, 67 tests passed. Node emitted pre-existing `localStorage is not available because --localstorage-file was not provided` experimental warnings.
- Worktree creation base: `75bc199`; preflight plan corrections: `d185f2b`, `18402d4`.
- SDD helper compatibility: `sdd-workspace` was invoked twice but Git Bash on Windows could not handle the Windows repository path. The workspace was created manually at the exact path the script specifies.

## Preflight Interface and File Scan

| Tasks | Producer → consumer / shared surface | Finding |
|---|---|---|
| 1 | Own tests → `Room`, snapshot readiness, settings bounds | Internally consistent after host fixture call was corrected in the plan. |
| 2 | Own migration tests → schema, seed counts, persistence ports | Internally consistent; V2/V3 ordering is explicit. |
| 3 | Own tests → module SPI, deadline, scheduler | Internally consistent after `GameSignal`, start context and scheduler cancellation types were made explicit. |
| 4 | Own tests → liar roles, projections and hints | Internally consistent; consumes the Task 2 `LiarWord` and Task 3 SPI. |
| 5 | Own tests → voting, scoring, departure, roster sync | Internally consistent; score deltas belong to `GameTransition`, cumulative scores to `GameRuntime`. |
| 6 | Own tests → application orchestration and transport | Internally consistent after `start` was ruled `void` and `LockedRoomResult<T>` was defined. |
| 7 | Own tests → Pinia state and deadline clock | Internally consistent; public/private state separation is explicit. |
| 8 | Own tests → host controls and responsive game UI | Internally consistent; consumes accessible actions and category bounds. |
| 9 | Own E2E → container journey and docs | Internally consistent; uses real fixed timers and no test-only timing bypass. |
| 1 ↔ 6 | `participantsReadyToStart` and room settings → server `canStart`, start validation | Clean; Task 6 combines participant readiness with content availability. |
| 1 ↔ 8 | `canStart`, host readiness semantics → host/participant buttons | Clean; UI does not replace server revalidation. |
| 2 ↔ 3 | `GameContent` and `LiarWord` → common start context | Conflict found and resolved before execution: Task 2 now creates the marker and value; Task 3 only consumes them. |
| 2 ↔ 4 | `LiarWord` → liar module | Clean; Task 4 modifies the already compiled value object only if its projection needs it. |
| 2 ↔ 6 | content/session ports → game orchestration | Clean; start persists RUNNING before attaching state and completion writes scores/ranks. |
| 3 ↔ 4 | `GameModule`, transitions, projections → role/hint implementation | Clean. |
| 3 ↔ 5 | roster synchronization, score deltas → voting/departure implementation | Clean. |
| 3 ↔ 6 | runtime, scheduler, registry → orchestration | Clean; stale deadlines are checked under the room lock. |
| 4 ↔ 5 | `LiarGameState`, module and projection → later phases | Clean; Task 5 extends the Task 4 state machine rather than duplicating it. |
| 4 ↔ 6 | registered liar module → transport and persistence integration | Clean. |
| 5 ↔ 6 | full liar transition contract → participant leave and round boundary | Clean; spectators are promoted only before a non-final next round. |
| 6 ↔ 7 | REST/STOMP game DTOs → Pinia sanitizer and sequencer | Clean; public and private sidecars share the public sequence. |
| 6 ↔ 8 | room status, snapshot, game actions → routed game shell | Clean. |
| 6 ↔ 9 | server behavior → four-context E2E | Clean; E2E uses public UI only. |
| 7 ↔ 8 | game types/store/deadline helper → Vue components | Clean. |
| 7 ↔ 9 | action envelope and reconnection state → E2E helpers | Clean. |
| 8 ↔ 9 | accessible action names → Playwright locators | Clean; names are listed verbatim in Task 8 and consumed by Task 9. |

Ruling: Task 2 owns creation of `GameContent` and `LiarWord`; Task 3 consumes them — this preserves numeric task order and makes Task 2 compile independently — if wrong, the cost is moving two small domain files and updating imports.

Ruling: `GameApplicationService.start` returns `void`; snapshots continue through the existing authenticated REST path and STOMP events — this avoids coupling game application code to `RoomApplicationService.RoomSnapshotView` — if wrong, the cost is adding a dedicated top-level snapshot result type.

Ruling: Use the manually created plan-scoped SDD workspace because the provided helper cannot create a directory from this Windows `C:/...` git root — artifacts still live at the helper's documented location and remain ignored — if wrong, only scratch artifact cleanup is affected, not tracked code or commits.

Ruling: Discussion early-finish uses the explicit strict-majority formula `activePlayers / 2 + 1`; with four active players the threshold is three approvals, so the conflicting two-vote example is treated as erroneous — if wrong, the cost is changing one threshold expectation and its domain tests.

Ruling: A participant promoted between rounds joins the active roster and starts at zero immediately, but enters the liar shuffle bag only when that bag is next refilled; Task 4's explicit no-repeat/refill rule controls the ambiguous Task 5 phrase "role candidate included" — if wrong, the cost is changing the bag reconciliation rule and its newcomer test.

Ruling: `DISCUSSION_END_PROPOSE` host authorization is enforced by Task 6 inside the room lock against the room's current `hostId`; the game module validates active membership and counts an already-authorized proposal but never infers host identity from player order — this stays correct across in-game host transfer without expanding every game SPI — if wrong, the cost is adding current host identity to the action/module context and updating its call sites.

## Task Status

- Task 1: complete (`18402d4..0213f66`; fix round 1/5 addressed 3 findings; scoped re-review approved)
- Task 2: complete (`0213f66..1cc1566`; fix round 1/5 addressed 2 findings and left 1 test gap; fix round 2/5 addressed the remaining gap; final scoped re-review approved; 124 backend tests passed)
- Task 3: complete (`1cc1566..6edf006`; fix round 1/5 addressed 2 test-coverage findings and exposed/fixed duplicate actor IDs; scoped re-review approved)
- Task 4: complete (`6edf006..dcbe708`; fix round 1/5 preserved authoritative public hint order; scoped re-review approved)
- Task 5: complete (`dcbe708..da0550a`; fix round 1/5 addressed 7 findings; scoped re-review approved)
- Task 6: complete (`da0550a..d7034aa`; room-locked orchestration, public/private projections, lifecycle persistence, scheduling and leave integration implemented; 173 backend tests passed; delegated review skipped per explicit no-subagent constraint)
- Task 7: pending
- Task 8: pending
- Task 9: pending
