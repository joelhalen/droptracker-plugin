# DropTracker Plugin Revamp — Investigation & Roadmap (2026-07-12)

Goal: improve reliability + latency of the RuneLite plugin's API integration, and redesign
the side panel to match the droptracker.io design system (`web/apps/web/app/globals.css`
tokens: dark parchment surfaces `#15110c → #3a2f20`, gold `#ffb83f`, muted text `#d8c9a3`).

---

## 1. Current architecture (as investigated)

### Plugin → API surface (`api/DropTrackerApi.java`, base `https://api.droptracker.io`)
| Endpoint | Caller | Notes |
|---|---|---|
| `GET /load_config` | plugin loop (120s refresh / 60s retry) | per-account group configs |
| `GET /top_groups` | GroupPanel default view | **was 1.6s cold — FIXED backend-side (see §3)** |
| `GET /top_players` | PlayerStatsPanel default view | Redis-first, fast (~4ms) |
| `GET /group_search?name=` | GroupPanel search + leaderboard row click | slow: WOM fetch + full member loot sum per request |
| `GET /player_search?name=` | PlayerStatsPanel search, login lookup | N+1 per group; `best_pb_rank` hardcoded `42` |
| `POST /check` | SubmissionManager status polling | one uuid per request |
| `GET /plugin_version` | startup version gate | fine |
| `GET /presigned_upload_url`, `POST /video/upload-failed` | video pipeline | recently hardened |
| `GET /latest_welcome`, `/latest_news` | HomePanel (async) | falls back to GitHub Pages |
| `POST /webhook` (multipart) | SubmissionManager | fast-accept queue mode is live (~3ms) |

Non-API fallback: Fernet-encrypted Discord webhook lists from
`droptracker-io.github.io/content/{yyyyMMdd}.json` (UrlManager).

### Side panel (`ui/`)
- `DropTrackerPanel` — header (logo GIF, version, API status, tracked account) + JTabbedPane:
  `Players | Groups | Welcome | API` (API-enabled) or just `Welcome`.
- `HomePanel` — welcome/news collapsibles, link buttons, static "feature" blurbs.
- `PlayerStatsPanel` / `GroupPanel` — search + top-5 leaderboard + detail cards.
- `ApiPanel` — session stats, submission list w/ retry, group config viewer.
- `PanelElements` / `LeaderboardComponents` — hand-rolled Swing helpers, hardcoded
  pixel sizes, RuneLite `ColorScheme` grays.

### Known plugin-side issues found
- `TopGroupResult.TopGroup` expects `top_member`; API sent `top_player` → always null.
  (Backend now emits **both** keys.)
- `GroupPanel` leaderboard click re-runs full `/group_search` (incl. WOM call) even though
  the row already has name/loot/rank/member_count → slow click UX.
- `DropTrackerApi.getGroupConfigs()` kicks off a network load from whoever calls it (UI paths).
- Demo/"test" fallback data still baked into GroupPanel.
- Duplicate Map-round-trip JSON parse fallbacks in `searchGroup`/`lookupPlayer` (should be
  removed once response shapes are guaranteed).
- No OkHttp timeouts/cache tuning; every panel open refetches everything.

---

## 2. Backend change tracker

| # | Change | Status |
|---|---|---|
| B1 | `/top_groups`: read `gleaderboard:{YYYYMM}` + 3 batched queries + pipelined per-group top player. 1,570ms → ~65ms live. Also emits `top_member` alias. Old recompute kept as month-rollover fallback. | **DEPLOYED** 2026-07-12 |
| B2 | `/group_search`: WOM fetch + per-member loot sum removed from request path; total+rank from `gleaderboard`; added partial-name (ilike) fallback. ~120ms measured. | **DONE** — restart pending |
| B3 | `/player_search`: per-group N+1 replaced with batched name/member-count queries + pipelined `gleaderboard` ZSCOREs; numeric loot sort (formatted-string sort put 999M above 1.2B); dropped unused hardcoded `best_pb_rank`. | **DONE** — restart pending |
| B4 | `GET /panel_data?player_name&acc_hash` (both optional): one round-trip returning `{configs, player_found, top_groups, top_players, welcome, news, version}`. Shapes identical to the individual endpoints. | **DONE** — restart pending |
| B5 | `/check` was a stub (always returned processed=true!). Now real: intake paths (`api/routes/webhook.py` + `workers/webhook_consumer.py`) write `submission:status:{guid}` Redis markers (24h TTL, `services/submission_status.py`) post-commit; `/check` reads them. Batch form: `{"uuids": [...]}` → `{"results": [...]}` (max 100); legacy single-uuid form unchanged; 10-poll give-up fallback for never-marked guids. | **DONE** — api + webhook-consumer restarts pending |
| B6 | `Cache-Control: public, max-age=15` on `/top_groups` + `/top_players`. (Redis-shared cache skipped: api runs a single process, in-proc cache already shared.) | **DONE** — restart pending |
| B7 | Consider SSE or long-poll endpoint for submission status → kills polling loop. | IDEA |

Deploy for B2–B6: restart `droptracker-api` and `droptracker-webhook-consumer`.
All 787 unit tests pass; endpoints exercised via Quart test client against prod data.

### Plugin adoption notes (for Phase A follow-up)
- Panel boot: switch to single `GET /panel_data` (anon → leaderboards/news only;
  with `player_name`+`acc_hash` → also configs). `player_found=false` ⇒ treat like
  the current /load_config 404.
- Status polling: `POST /check {"uuids": [all pending]}` once per tick instead of
  one request per submission. `status` is now honest: `pending` until actually
  processed (previously instant fake "processed").

## 3. Plugin roadmap

**Phases A + B are DONE (2026-07-12)** on branch `claude/panel-redesign` (5 commits,
compile + tests green, NOT pushed): timeout-bounded panel client, EDT audit, instant
row-click group details, demo data removed, `ui/DropTrackerTheme.java` web-token palette,
`Home | Activity | Player | Group` tab structure (Activity replaces ApiPanel), shared
StateViews, `/panel_data` + batch `/check` adoption with graceful fallback + 5-min
backoff for older custom endpoints. Remaining below for reference / Phase C.

### Phase A — reliability & latency (no visual change)
- OkHttp: explicit connect/read timeouts, single retry w/ backoff for panel GETs.
- All panel data loads strictly off the EDT (audit `getGroupConfigs()` call sites).
- Use leaderboard row data directly for detail views; fetch `/group_search` only for
  missing fields (recent submissions), showing cached fields instantly.
- Adopt B4 aggregate endpoint; drop the parse-twice fallbacks.
- Batch `/check` polling (B5).

### Phase B — side panel redesign (align with web design system)
- Palette: map web tokens to Swing constants (`Surface0 #15110c`, `Surface1 #211a12`,
  `Surface2 #2c2318`, `Gold #ffb83f`, `TextMuted #d8c9a3`, green/red status).
- Structure: `Home | Activity | Player | Group | Settings/API` with a persistent
  status strip (tracked account, API state, last submission).
- Activity tab = session submission feed (icons via ItemManager, status chips,
  retry buttons) — promote from buried ApiPanel.
- Replace hardcoded-size stat boxes with shared card components (mirror web stat tiles).
- Kill demo data; consistent empty/error/loading states (match web empty-state patterns).

### Phase C — feature expansion
- Live recent-submissions ticker (reuse web `feed:recent` via new public endpoint).
- Badges + PB leaderboards in player detail (web APIs already exist under `/api/v1`).
- Group lootboard image inline w/ refresh, event progress panel (events v2).
- Right-click player "DropTracker lookup" menu entry.

---

Verification notes for B1: benchmarked via direct `_build_top_groups_payload()` calls
against prod Redis/DB — 229 groups, 49–59ms warm, output shape identical (plus
`top_member`), top-5 spot-checked against previous endpoint output.
