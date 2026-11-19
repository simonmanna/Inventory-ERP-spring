# ADR-S079 — In-app guided tours (the "Learn" assistant), Sales first

- Status: accepted by the owner (2026-11-19). G1 (engine, Learn panel, two Sales tours) and G2 (the rest of the Sales
  catalog, seven tours in all) are built; G3 (branch cash sales, delivery notes, reports, written tutorials) is built too; the AI chat (G4) needs its own approval.
- Extension: no NestJS counterpart. Three new routes under `/guide/progress`; nothing existing changes shape.
- Migration: `V580__guide_tour_progress.sql`.

## Context

New staff should learn the ERP on the job, not in classroom training. The owner asked for an assistant that shows
users how to work through a task like a wizard, starting with Sales (quotation → order → delivery → invoice). In Sales
these steps are separate full pages, each with a status ribbon. There is no step-by-step wizard to lean on.

Owner decisions (2026-11-19):
- **Guided tours on the real pages.** The guide highlights the field or button and explains it, and the user does the
  clicking. It is not an auto-pilot demo.
- **No LLM in phase 1.** Ship a deterministic guide first and leave a seam for an AI chat.
- **Teach and navigate only.** The guide never creates, changes or reads business data.

## Decision

1. **The tour engine is built in-house** (`web/src/features/guide/`), not on driver.js, intro.js or react-joyride.
   None of those libraries carries a tour across route changes or reloads, waits for the user's own action, or filters
   tours by the ERP's permissions. Those three are most of the work, and the highlight itself is about 200 lines.
2. **Tours are code, not data.** Each step names a `data-tour` anchor on a real element, so a tour has to change
   together with the screen it describes. `catalog.test.ts` fails when a tour points at an anchor or route that no
   longer exists. The text goes through `tx()` like every other string in the UI.
3. **A step ends when the user does the thing.** It can end on Next, on a click on the anchor, on reaching a route (for
   example saving a new quotation moves the browser to `/sales/quotations/:id`), or when an element appears. Only fixed
   pages can be opened for the user ("Take me there"). A record page is reached through the steps. The guide never
   navigates to a URL it built from input.
4. **Visibility comes from permissions.** A tour lists the permissions of the routes it walks through, and the user
   needs any one of them (the same rule as `RequirePermission`). The server still enforces every real action, as it
   always does.
5. **Progress lives on the server, per user and organization.** It is stored in table `GuideTourProgress` and served
   at `/guide/progress`. Any signed-in user may call these routes, because the data is their own (`AuthUser`, never an
   id taken from the request). The tour being run is kept in sessionStorage, so it survives page changes and reloads.
6. **AI seam.** The Learn panel's search box goes through `AssistantResolver`. Phase 1 matches keywords against the
   tour catalog. A later `/guide/ask` can answer with a model and return the same suggestions, or actions such as
   `START_TOUR` and `NAVIGATE`, and drive the same engine. That phase needs its own ADR (provider choice, data sent out,
   cost).

## Consequences

- Adding a tour means adding anchors (inert `data-tour` attributes) to the screens and a `TourDef`. No backend change
  is needed.
- A screen redesign that drops an anchor makes the catalog test fail instead of silently breaking a tour. At runtime
  a missing element shows "I can't find this on the screen" with the options to skip or end the tour.
- The overlay leaves the page fully usable: the spotlight ignores pointer events. Clicking the card does not close a
  Radix dialog the step points into.
