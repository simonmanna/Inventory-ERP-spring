# CLAUDE.md

@AGENTS.md

## Claude Code specifics

- Start each task by reading the phase brief you were given (`docs/phases/Pn-*.md`) and the module
  spec (`docs/spec/<module>.md` + `docs/spec/generated/<module>.md`).
- The NestJS reference lives in `reference/nest-api/` inside this repo. If the original POS-CAFE
  checkout exists on this machine (`C:\Dev\POS-CAFE`), it is the same code — never modify it.
- Use the generated route table as your checklist; implement routes in table order and tick them
  in `docs/PORT_STATUS.md`.
- Fast loop (use while working; full suite is ~14 min):
  1. Batch edits, then `./gradlew compileJava compileTestJava -q` once — not after every file.
  2. Run only affected classes: `./gradlew fastTest --tests '*XxxIT'` (reused DB container, no JaCoCo,
     skips seed/arch suites). Pure logic → plain JUnit/Mockito test, no Spring context.
  3. New ITs extend `ApiTestSupport` (one cached context). No new `@MockitoBean`/profile combos — each forks a
     fresh context.
  4. Never read `build/`, `.gradle/`, `node_modules/`, `var/`. Use tokensave/Grep to locate code before Read.
- **Build rule (overrides every other doc, phase brief and memory):** NEVER run `./gradlew build`, `clean`,
  `check`, a bare `./gradlew test`, or `docker compose ... --build` unless the owner explicitly asks in the
  current request. Finishing a task, phase or merge is not a request. CI runs the full build on push.
  - Enough to call a change done: `compileJava compileTestJava -q` once, plus `fastTest --tests` for the
    affected classes. Docs/config-only change: no Gradle at all. Web-only change: no Gradle.
  - Before a commit: `./gradlew spotlessApply` (CI rejects unformatted code).
  - In the done report, state which tests ran and that the full build did not.
- Testcontainers needs Docker running. On Windows use Git Bash; `JAVA_HOME` may point at any JDK 17+.
- Prefer small PRs per module or sub-area (e.g. `pos/tables`, `pos/kds`), branch `phase/Pn-<area>`.
- When the Nest code and these docs disagree, the Nest code wins — fix the doc in the same PR.
