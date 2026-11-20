# Loss-prevention demo footage (tenant DEMO)

Synthetic video and stills for a loss-prevention demo with no real camera (ADR-S077 LP1/LP1c). When a case opens, the
system asks the edge box for clips of the till cameras; with a simulated box those clips wait in "Upload requested"
for ever. This tool uploads a generated clip for each of them **through the edge agent's own upload route**, so the
backend checks the SHA-256, encrypts and stores it and records it in the access trail exactly as for real footage.
For each case with a till clip it also asks for a still at the moment the drawer is open and uploads that frame.

Every frame is labelled `DEMO FOOTAGE - synthetic, not a real recording`. The scene is drawn by ffmpeg: a till whose
cash drawer is open from 18 s to 30 s, or a pick-up counter, with the capture time (UTC) burnt in.

```bash
# on the server, after backing up the database; needs ffmpeg (libx264) and the DejaVu fonts
cd tools/demo-data/lp-footage
LP_PASSWORD='<admin password>' python3 load.py --confirm DEMO \
    --agent-state ../loss-prevention/state.DEMO.json         # the LP pilot seeder's state file, or:
LP_PASSWORD='<admin password>' LP_AGENT_CLIENT_ID=ierp_... LP_AGENT_SECRET=... python3 load.py --confirm DEMO
```

| Variable | Default |
|---|---|
| `ERP_BASE` | `http://127.0.0.1:3001/api/v1` (only localhost is accepted; run it on the server) |
| `LP_ORG` | `DEMO` |
| `LP_EMAIL` | `admin@demo.test` (needs `lp:evidence`) |
| `LP_PASSWORD` | `Admin@123` (the local seed default) |
| `LP_AGENT_CLIENT_ID`, `LP_AGENT_SECRET` | the enrolled edge agent's credentials, when `--agent-state` is not given |

- Only clips in state `requested` or `uploading` are filled; a re-run finds none and changes nothing. A case that
  already has a still gets no second one. `--no-stills` uploads the clips only.
- Stills come from the box's buffer, so only cases within `evidenceBufferDays` (7 by default) of today get one.
- The agent must be `active`. Its credentials are secrets: never commit a state file.

Live DEMO was loaded on 2026-11-20 (before this tool was in the repo, from the same scripts): 8 clips and 4 stills for
the KRD cases LP-000009, LP-000014, LP-000017 and LP-000028.
