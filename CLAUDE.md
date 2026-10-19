# CLAUDE.md

@AGENTS.md

## Claude Code specifics

- Start each task by reading the phase brief you were given (`docs/phases/Pn-*.md`) and the module
  spec (`docs/spec/<module>.md` + `docs/spec/generated/<module>.md`).
- The NestJS reference lives in `reference/nest-api/` inside this repo. If the original POS-CAFE
  checkout exists on this machine (`C:\Dev\POS-CAFE`), it is the same code — never modify it.
- Use the generated route table as your checklist; implement routes in table order and tick them
  in `docs/PORT_STATUS.md`.
- Run `cd backend && ./gradlew build` before declaring anything done. Docker must be running
  (Testcontainers). On Windows use Git Bash; `JAVA_HOME` may point at any JDK 17+.
- Prefer small PRs per module or sub-area (e.g. `pos/tables`, `pos/kds`), branch `phase/Pn-<area>`.
- When the Nest code and these docs disagree, the Nest code wins — fix the doc in the same PR.
