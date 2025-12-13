# Baritone (1.21.10) + Meteor Client — Project Plan (2025-12-13)

**Goal**: Make Meteor Client the primary UX for Baritone on 1.21.10 with powerful **buttons + panels + HUD overlays**, while improving practical “AI” (decision logic), mining/building automation, swarm control, and performance.

This plan is intentionally **future-only** (no completed history).

---

## 0) Engineering rules (non-negotiable)

- **No lag regressions**: anything that can be heavy (render overlays, scanning, AI calls, networking) must be bounded (rate limits, timeouts, sampling caps) and moved off the client thread where possible.
- **Safe-by-default**: anything that accepts remote commands (LAN swarm, Discord, webhooks) is **OFF by default**, and uses a strict allowlist + trusted controller model.
- **UI in Meteor, behavior in Baritone**: Meteor owns UX; Baritone owns pathing internals and heuristics.
- **Always validate**: compile, run relevant tests, and clear real errors/lint before moving on.

### 0.1 Build + error-check workflow (always)

- Meteor compile: `cd meteor-client && ./gradlew --no-daemon classes`
- Baritone verify: `cd baritone && ./gradlew test :fabric:build`
- If something fails: fix the error first, then continue.

### 0.2 “Maximize MCP servers” workflow

Use MCP tooling intentionally:

- **Library/API docs**: Context7 (MCP) to pull current docs when integrating libraries (HTTP servers, file formats, etc.).
- **Web research**: Firecrawl (MCP) to fetch upstream specs (schematic formats, Wurst-like feature behavior, etc.).
- **Security**: Snyk (MCP) before shipping any new parser/network/webhook code or adding dependencies.

### 0.3 Server compliance & safety guardrails

- **No bypass/evasion features**: do not implement or describe “anti-cheat bypass,” packet abuse, or behavior intended to evade enforcement.
- **Off by default on multiplayer**: anything commonly disallowed (macros, scaffold-style assistance, remote control) must be clearly labeled and default to OFF in multiplayer profiles.
- **Normal-client cadence**: automation must follow normal placement/breaking cadence with explicit throttles and immediate cancel.
- **Remote triggers are not special**: LAN/webhook/Discord/voice can only request the same allowlisted actions exposed in the UI.

### 0.4 Git hygiene (two repos)

This workspace contains two separate repos: `baritone/` and `meteor-client/`.

- Before/after each milestone: run `git status` in both repos and keep changes scoped.
- Prefer small, reviewable commits per feature slice (UI + behavior + tests + docs).
- Keep `PROJECT_PLAN.md` updated when scope changes (add/merge items; avoid duplicate sections).
- Avoid committing generated build outputs; keep `.gitignore` tidy.

---

## 1) Product goals

- **Meteor-first UX**: common actions are buttons, not chat parsing.
- **Smarter automation**: fewer stalls, safer navigation, better recovery.
- **Faster**: reduce replans/allocations; keep client thread smooth.
- **Extensible agent**: background task queue + policies.
- **Swarm**: coordinate multiple clients safely.
- **Exploration**: mapping overlays, waypoints, cave tools.

---

## 2) Meteor UX surfaces (where features appear)

### 2.1 Baritone menu (Path Manager tab)

Use for “immediate actions” + lightweight queue controls only:

- Primary workstreams here: A (core actions), E (mine/goto primitives), F (queue controls)
- Keep this screen minimal; larger systems live in dedicated tabs.

### 2.2 Right-Shift panels/tabs (Meteor GUI)

Use for larger systems:

- **AI Assistant** tab: plan/suggestions UI that emits allowlisted queued tasks (Workstream F/H).
- **Swarm Control** tab: trust + targeting + broadcast of allowlisted tasks (Workstream B).
- **Navigation/Explorer** tab: POIs/waypoints + toggles + performance caps (Workstream C).

### 2.3 HUD overlays

- HUD overlays implement Workstreams C/F (status, nav, exploration) and must be toggleable, throttled, and allocation-free on render hot paths.

---

## 3) Workstreams (each includes implementation + docs + tests + error checks)

### A) Smarter Baritone behavior + automation runtime (offline-first)

**Implementation steps**
0) Automation runtime v1 (the “moat”)
   - Define cancelable `Task` primitives and a `TaskScheduler` (priorities, pause/resume, cooperative yield per tick).
   - Add a shared `Blackboard` (inventory summary, hazards, POIs/waypoints, last failures).
   - Define `RecoveryPolicy` hooks (stuck, low health, inventory full, unreachable goal) that can pause/cancel/retask safely.
1) Recover routine v2
   - Add stuck detection signals (progress delta, collision patterns, repeated replans).
   - Recovery sequence: cancel → clear inputs → rotate/backstep/jump → replan.
   - Add cooldown/backoff to avoid loops.
2) Smart Goto v2
   - Choose goal type by context (Y delta, hazards).
   - Add optional safe approach radius.
3) Safe Mode preset v2
   - Curated bundle (fall risk, lava/water assumptions, conservative movement).
   - Define a reversible application model (restore previous values or clearly “reset to defaults”).
4) Goal-oriented automation v1 (optional, offline-first)
   - Simple declarative goals that decompose into tasks (start with predictable behavior-tree style rules; keep GOAP-style planning as a later extension).
   - Ensure all plans are explainable and interruptible.
5) Combat-aware movement (optional)
   - PvE-focused “keep distance / retreat” movement as a safe process (no combat-module coupling required initially).

**Documentation**
- Add a short “Smart Goto / Recover / Safe Mode” guide (what it changes, what it won’t do).
- Add an “Automation runtime” doc: task lifecycle, cancel semantics, recovery policy, and safety rules.

**Testing**
- Unit tests for deterministic decisions (goal selection heuristics, backoff policy).
- In-game checklist for common stuck cases (doors, water edges, block collisions).

**Error/lint**
- Ensure builds succeed and remove real compile warnings before merging.

---

### B) Multi-Client Coordination (Swarm)

**Vision**: One controller can coordinate many Meteor/Baritone clients at once (LAN first; Discord relay optional).

**Security model (required)**
- Slaves default to **reject all remote control**.
- “Trusted controllers” list (UUIDs or shared keys).
- Allowlisted command set (goto/mine/stop/queue/build primitives; no arbitrary execution).
- Rate limit broadcasts and require schema versioning.

**Implementation steps**
1) Extend Meteor’s existing swarm/LAN mechanism
   - Find current swarm transport and message format.
   - Introduce a versioned, structured message schema.
2) Swarm Control UI (Right‑Shift tab)
   - Register trusted controllers.
   - Show connected bots + status.
   - Targeting: all / subset / tags.
3) Swarm command relay
   - Controller broadcasts safe commands (e.g., “everyone mine diamond_ore”).
   - Slaves verify trust + allowlist before acting.
4) Team coordination primitives (opt-in)
   - Shared waypoints/POIs (export/import) and basic “status broadcast” (current task, health/inventory summary).
   - Optional “claim” hints (avoid two bots mining the same target area) without enforcing ownership.
5) Optional Discord relay (opt‑in)
   - Prefer: Discord → n8n → local webhook → allowlisted actions.

**Documentation**
- “Swarm security” doc: trust model, allowlists, examples, warnings.

**Testing**
- LAN test matrix: 1→1, 1→N, untrusted reject, malformed reject, replay/dup handling.
- Fuzz/robustness tests for any message parsers.

**Error/lint**
- Run Snyk (MCP) before shipping network-facing changes.

---

### C) Exploration & Mapping Tools

**Implementation steps**
1) NewChunks/Explorer overlay
   - Track chunk novelty signals client-side.
   - Render overlay efficiently (avoid allocations per frame).
2) Perception engine + world model v1
   - Persist chunk facts keyed by dimension + chunk (novelty, cave likelihood, hazard score, POI signatures).
   - Add bounded sampling caps; never scan unbounded radii on the client thread.
3) Waypoints + Navigator/Compass
   - Destination marker and direction indicator.
   - Optional: show current Baritone goal.
   - Waypoint categories, import/export, and avoidance/danger zones.
   - Multi-destination routing (small N optimizer: greedy + 2-opt is enough).
4) Baritone path integration
   - Draw planned route in HUD (toggleable).
5) CaveFinder overlay
   - Bounded sampling approach; avoid scanning whole chunks constantly.
6) Minimap/Coordinate HUD
   - Start minimal: coordinate HUD + waypoint list; expand later if needed.
7) POI detection (high impact)
   - Structure/“base” signatures (block palette anomalies, lights underground, portals/obsidian frames, chest clusters).
   - Auto-create POIs with confidence scores; allow user review/edit.

**Documentation**
- “Navigation Tools” guide + performance notes.

**Testing**
- In-game validation: toggles, dimension switches, FPS impact on/off.

**Error/lint**
- Profile render hot paths (JFR) and cap sampling.

---

### D) Building & Creative Features

**Implementation steps**
1) Schematic format strategy
   - Decide first supported format: `.schematic` / `.schem` / `.litematic`.
   - If relying on Litematica for interactive placement, define the integration boundary (import/build from file vs. cooperate with Litematica’s placement data).
   - Add schematic analysis: resource requirement totals, build order priorities (foundation → structure → detail).
2) Builder primitives
   - Commands/actions: build shaft, stairs, bridge, clear area.
3) ScaffoldWalk / InstantBuilder
   - Place blocks under player while moving using normal placement cadence, strict throttles, and immediate cancel.
4) TemplateTool
   - Pick a block type and place repeatedly; auto-switch blocks/tools.
5) Resume + verification
   - Resume interrupted builds and verify placed blocks match expected; surface a concise diff.
6) Material-aware building (optional)
   - Pull required blocks from inventory/nearby chests (bounded search); block substitution rules if missing.

**Documentation**
- “Building workflows” guide: file paths, survival constraints, safety.

**Testing**
- In-game tests: survival inventory constraints, placement correctness, server compatibility settings.

**Error/lint**
- Avoid fast-place loops that can soft-freeze clients/servers; enforce throttles.

---

### E) Resource automation (mining, farming, storage)

**Implementation steps**
1) Advanced mining foundation
   - Ore detection (bounded radius) + vein clustering; prioritize by mode (efficiency/profit/tunnel/vein/custom).
   - Inventory thresholds + auto-return to home waypoint (opt-in) + stop conditions.
   - Hazard detection: lava, fall risk, suffocation, gravity blocks; safe responses must be bounded and reversible.
   - Tool durability + enchant-aware tool selection (Fortune/Efficiency) + auto-swap.
2) Branch mine mode
   - Pattern generator (main hallway + side tunnels), configurable spacing/length.
3) Vein mine
   - Connected-component mining with radius limit + safety rules.
4) AutoSmelt baseline
   - Sort ores → furnaces → collect output, with stop conditions.
5) Farming automation (optional)
   - Crop harvest/replant, simple tree farm loop, and safety return conditions (health low / inventory full).
6) Storage/sorting (optional)
   - Auto-deposit/retrieve via rule-based categorization; track “where is X stored?” within a bounded storage area.
7) Brewing/smelting tasks (optional)
   - Keep as separate queued tasks.

**Documentation**
- “Resource automation” guide: mining modes, farming loops, storage rules, stop conditions, safety.

**Testing**
- Unit tests for pattern generators.
- In-game runs on multiple terrain types.

**Error/lint**
- Ensure every automation has stop conditions and cannot loop forever.

---

### F) Meteor UX, controls, and profiles

**Implementation steps**
1) AI Assistant tab
   - Conversation/history (local) and structured suggestions.
   - Suggestions become clickable allowlisted actions that enqueue tasks.
   - Add a safety gate: show proposed actions and require confirm when actions are high-impact.
2) Objective/status HUD
   - Current objective, queue preview, bot state.
3) Macro recorder
   - Record/replay a safe subset of queued tasks (not raw input events), with deterministic ordering and stop conditions.
4) Advanced config screens
   - Expose tuning parameters without cluttering the Path Manager tab.
5) Configuration profiles
   - Built-in profiles (e.g., survival/server-safe/exploration) and per-server auto-apply (opt-in).

**Documentation**
- “UI Tour” doc + hotkey bindings.

**Testing**
- UI regression checklist: open/close screens, save settings, no crashes.

**Error/lint**
- Avoid heavy reflective work per keystroke; cache metadata.

---

### G) Performance & Reliability

**Implementation steps**
1) Profiling harness
   - Add lightweight timing + guidance for JFR capture.
2) Async boundaries
   - AI/network I/O on workers with timeouts + cancellation.
3) Caching
   - Cache exploration results, render overlays, repeated calculations.
4) Robust retries
   - Backoff on transient failures.
5) Observability (opt-in)
   - Session metrics (tasks completed, blocks mined/hour, distance traveled/hour) and a lightweight dashboard.
   - Structured logs for task failures and recovery decisions; exclude sensitive data by default.

**Documentation**
- “Performance knobs” + known expensive features.
- “Metrics/logging” guide: what is recorded, retention expectations, how to disable/delete.

**Testing**
- Benchmark scenarios (pathing, mining, overlays on/off).

**Error/lint**
- No known compile errors left behind.

---

### H) External Integrations (n8n, Discord, webhooks, voice)

**Principle**: external calls must not block gameplay.

**Implementation steps**
0) Provider + schema baseline
   - Define a provider interface and a versioned plan schema that produces only allowlisted actions (enqueue task, create waypoint/POI annotation, toggle safe modules).
   - Cache responses and enforce rate limits.
1) n8n bridge
   - Define event payloads (in-game triggers) → webhook.
   - Define allowlisted commands returned by n8n.
2) Discord integration via n8n
   - Discord message → n8n → local webhook → allowlisted actions.
3) Gemini CLI via n8n
   - n8n calls gemini-cli and returns structured suggestions.
4) Voice commands (optional)
   - Local STT → n8n/webhook → allowlisted actions.

**Documentation**
- Setup guide: flows, tokens, security.

**Testing**
- Timeout handling, malformed payload reject, replay protection.

**Error/lint**
- Snyk scan before shipping any HTTP server/client changes.

---

## 4) Milestones (sequence)

### M0 — Automation runtime foundation
- Task scheduler + blackboard + cancellation semantics
- Minimal recovery policy hooks and status reporting in UI

### M1 — Smarter behavior hardening
- Recover v2 + Smart Goto v2 + Safe Mode v2
- Add unit tests for deterministic pieces

### M2 — Swarm foundations
- Extend LAN swarm + trust model
- Swarm Control UI (basic)
- Broadcast allowlisted Baritone tasks to selected bots

### M3 — Navigation tools
- NewChunks overlay + CaveFinder
- Perception/world model v1 + POI detection
- Waypoints/compass + Baritone goal/path HUD

### M4 — Mining automation
- Advanced mining foundation (ore detection + clustering + modes + hazards)
- Branch mine + vein mine
- AutoSmelt baseline

### M5 — Building automation
- Builder primitives + schematic import baseline
- ScaffoldWalk/TemplateTool

### M6 — External integrations (opt-in)
- n8n bridge + Discord/webhooks
- Optional AI assistant wiring

### M7 — Performance pass (ongoing)
- Profiling + hotspot fixes
- Regression checklist updates

---

## 5) Acceptance criteria

- Meteor UX is coherent: Baritone menu buttons + dedicated tabs for swarm/AI/nav.
- Swarm cannot be abused by default: explicit trust + allowlists + rate limits.
- Overlays don’t tank FPS; toggles respond immediately.
- Mining/building automation has explicit stop conditions and is interruptible.
- Every milestone compiles cleanly; tests added where practical; no ignored real errors.
- Optional metrics/logging are opt-in and easy to disable.

---

## Appendix — LLM feature intake (curated merge)

The following high-level ideas from the LLM input are explicitly included in this plan (and merged into the relevant workstreams):

- Automation runtime (task lifecycle + scheduler + blackboard) → Workstream A
- Perception/world model + POI detection + persistent maps/heatmaps → Workstream C
- Advanced mining (ore clustering, priority modes, inventory return, hazard handling, enchant-aware tools) → Workstream E
- Schematic-aware building (analysis, resume, verification, substitutions) → Workstream D
- Farming + storage/sorting → Workstream E
- Structured AI integration (providers + plan schema + safety gate) → Workstreams F/H

Explicitly excluded: any feature framed as bypassing server anti-cheat or evasion.
