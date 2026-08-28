# Task 9 report — four-player liar-game E2E

## Implementation commit

- Commit: `aef50f4` — `test: verify four-player liar game journey`
- Scope: `e2e/` and `docs/portfolio-development-journal.md`; no production application code changed.

## Scenario coverage

- Four independently authenticated guest browser contexts create and join a public room through the normal UI.
- Every guest sees the same participant roster; the host saves one-round settings using the production minimums (15-second action and 60-second discussion timers), guests ready, and the host starts the game.
- The first role reveal reloads a citizen page and verifies that its role, word, and visible phase recover from the requester's private snapshot.
- The role-reveal secrecy assertion confirms that the liar DOM does not contain the citizen word, no page storage serializes the word or private-state keys, displayed roles remain per-player only, and captured browser-console messages do not contain the word or known private action fields.
- Explicit vote matrices cover an accused liar whose deliberately wrong guess gives citizens the win, an accused liar whose correct guess gives the liar the win, and a first-vote tie followed by a revote that accuses the liar.
- Each completed one-round game waits through the real five-second role reveal and eight-second round-result timing, reaches the final result, and uses the public UI to return to the waiting room. No server flag or test-only timing bypass was introduced.

## Validation evidence — 2026-08-29

| Command | Result |
|---|---|
| `npm.cmd test -- --list` in `e2e` | Passed static Playwright discovery: 9 tests in 3 files, including `liar-game.spec.ts`. |
| `npm.cmd test` in `frontend` | Passed: 13 test files, 98 tests. Node emitted the existing localStorage experimental warnings. |
| `npm.cmd run build` in `frontend` | Passed: `vue-tsc --noEmit` and Vite production build. |
| `git diff --check` before the implementation commit | Passed with no whitespace error output. |
| `./gradlew.bat test` in `backend` | Not completed: after Gradle distribution access was allowed, Gradle failed before tests with `java.io.IOException: Unable to establish loopback connection`. |
| Docker build / Compose readiness / Playwright execution | Not run: the local Docker engine remained inaccessible; see blocker below. |

## Blocker and concerns

- Docker initially had no `//./pipe/docker_engine`. Docker Desktop was started hidden as requested, but `com.docker.service` remained stopped and an elevated `Start-Service` attempt failed with `Cannot open 'com.docker.service' service on computer '.'`. The app therefore could not be packaged, started, or exercised by Playwright on this host.
- The target E2E is intentionally a single 180-second journey because real role-reveal and result intervals must elapse for all three branch variants. Every assertion uses a 20-second condition timeout; the test does not use arbitrary fixed sleeps.
- Re-run on a host with Docker daemon access: `docker build -t minigame:liar .`, `docker compose up -d --build`, wait for `http://localhost:8080` to return 200, then `npm.cmd test` in `e2e`. Re-run `./gradlew.bat test` once the local Gradle loopback restriction is removed.
- `.superpowers/sdd/liar-game-implementation-plan/progress.md` was already modified in the worktree and remains deliberately uncommitted and untouched by Task 9.

## Fix Round 1 — all-category content and E2E event causality

- Fix commit: `e9edce0` — `fix: support all liar categories in E2E flow`
- Root cause: the UI exposes the virtual `all` category, but `JpaLiarContentAdapter` treated every category as an exact `content_packs.code` filter. The seed contains eight active concrete LIAR packs and no synthetic `all` pack, so availability and content selection returned no candidates.
- The adapter now treats only the literal `all` as a request across every active LIAR pack. Concrete category filters, active-item filtering, exclusion IDs, deterministic ordering, and result limits remain unchanged. A PostgreSQL integration test was written first to require six exclusions to leave 394 unique words across all eight categories and to verify both true and false remaining-capacity checks.
- The requested RED execution could not reach the integration test body: `./gradlew.bat test --tests com.minigame.platform.game.adapter.out.persistence.GamePersistenceIntegrationTest` failed during Gradle startup with `java.io.IOException: Unable to establish loopback connection`. This is the same host-level Gradle blocker recorded above, not a passing or failing Testcontainers result.
- The E2E discussion helper no longer races a proposal delivery. The approval action is intentionally absent until the public discussion-proposal acknowledgement is reflected in `submittedPlayerIds`; the helper waits for that action to be enabled in each approving guest before clicking. A focused frontend RED test failed first because the old approval control already existed before acknowledgement, then passed after the UI change.
- The E2E saves `1 / 15 / 60` settings once only. It first proves all guests are ready and the host can start, then requires the server acknowledgement to show the configured summary and clear readiness before the first round. The second and third branch rounds only ready and start, preventing a stale settings event or repeated save from satisfying the flow.

### Fix Round 1 validation — 2026-08-29

| Command | Result |
|---|---|
| `npm.cmd test -- LiarGameView.spec.ts --reporter=verbose` before the UI fix | RED: 1 expected failure, `approval ... expected false, received true`; 7 other focused tests passed. |
| Same focused frontend test after the UI fix | GREEN: 1 file, 8 tests passed. |
| `npm.cmd test` in `frontend` | Passed: 13 test files, 99 tests. Existing Node localStorage experimental warnings remained. |
| `npm.cmd run build` in `frontend` | Passed: `vue-tsc --noEmit` and Vite production build. |
| `npm.cmd test -- --list` in `e2e` | Passed static Playwright discovery: 9 tests in 3 files. |
| Focused PostgreSQL integration / Docker build, Compose, packaged E2E | Still not executable on this host: Gradle loopback and Docker named-pipe/service access remain blocked. No packaged journey pass is claimed. |
