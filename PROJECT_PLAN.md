# Baritone (1.21.10) + Meteor Client — Project Plan (2025-12-14)

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

## 1) Product goals (remaining)

- **Advanced navigation**: waypoint categories, routes, avoidance zones, and multi-destination routing.
- **Smarter “return home”**: consistent triggers (health/tool/inventory/etc.), ETA display, and bounded safety checks.
- **Combat-aware movement (opt-in)**: safe repositioning logic that can integrate with combat modules without bypass/evasion.
- **Intelligent resource management**: automated farming + storage sorting/retrieval workflows.

---

## 2) Meteor UX surfaces (for the next phases)

### 2.1 Baritone menu (Path Manager tab)

- Keep for immediate actions + lightweight queue controls.

### 2.2 Dedicated Right‑Shift tabs

- **Navigation & Routing**: waypoints → routes → avoidance zones → route execution.
- **Resource Manager**: farms, storage rules, deposit/retrieve, status/metrics.
- **Swarm (extensions only)**: team coordination primitives and route/POI sharing (trust model remains required).

---

## 3) Roadmap additions (requested)

## PHASE 2: ADVANCED PATHFINDING & MOVEMENT (Baritone Enhancement)

### 2.1 Predictive Pathfinding with Waypoints

Feature: Waypoint Management & Multi-destination Routing

#### Waypoint System (remaining work)

- [x] Waypoint categories (home, mine, farm, etc.)
- [x] Waypoint import/export (share routes)

#### Multi-Destination Routing

- [x] Create route through multiple waypoints
- [x] Optimize route order (shortest path)
- [ ] Calculate total distance/time
- [x] Resume from any point in route
- [ ] Dynamic route creation based on resources

#### Smart Return-to-Home (remaining work)

- [x] Configurable triggers beyond existing inventory/tool stops (e.g., low health)
- [x] Estimated arrival time display
- [/] Route safety assessment

#### Advanced Features

- [x] Avoidance zones (mark danger areas)
- [ ] Preferred biome routing
- [ ] Nether/End dimension awareness
- [ ] Parkour movement integration

### 2.2 Combat-Aware Movement (opt-in)

Feature: Dynamic Movement During Combat

#### Threat Assessment

- [x] Real-time entity tracking
- [ ] Damage prediction (incoming damage estimate)
- [x] Safe zone identification
- [x] Kite detection (moving away from threat)

#### Combat Movement Modes

- [x] STRAFE MODE: circle target while maintaining distance
- [x] KITE MODE: escape while dealing damage
- [x] CLUSTER MODE: group attacks together (Horde Avoidance)
- [x] RETREAT MODE: return to safe base
- [ ] CRYSTAL MODE: position for crystal PvP (multiplayer-default OFF; no bypass/evasion)

#### Hitbox Prediction

- [ ] Predict enemy position next tick
- [x] Calculate safe movement paths
- [ ] Block placing for protection
- [x] High-ground seeking

#### Integration Points

- [x] Hooks into combat modules (KillAura, etc.)
- [x] Respects team members (doesn't harm teammates)
- [x] Server-lag compensation

---

## PHASE 3: INTELLIGENT RESOURCE MANAGEMENT (Farming/Automation)

### 3.1 Automated Farm Management

Feature: Multi-type Automated Farming

#### Farm Types Supported

- [ ] Wheat/Crops (detect growth, harvest at maturity)
- [ ] Tree farming (detect logs, auto-fell with pathfinding)
- [ ] Mob farming (auto-kill mobs with pathfinding)
- [ ] Fish farming (auto-fish with click detection)
- [ ] Villager trading (auto-trade for resources)
- [ ] Custom farm types (user-defined patterns)

#### Crop Detection System

- [ ] Growth stage analysis (block metadata checking)
- [ ] Maturity prediction
- [ ] Harvest readiness assessment
- [ ] Replanting automation

#### Farm Efficiency Metrics

- [ ] Items/hour tracking
- [ ] Growth rate analysis
- [ ] Optimal harvest timing
- [ ] Bottleneck detection
- [ ] Performance dashboard

#### Multi-Farm Management

- [ ] Rotate between multiple farms
- [ ] Optimize farm visit order
- [ ] Load balancing (prioritize slower farms)
- [ ] Return-to-home safety checks

#### Advanced Features

- [ ] Bonemeal auto-application (if needed)
- [ ] Custom harvest patterns
- [ ] Redstone integration (auto-activate farm)
- [ ] Crop rotation logic

### 3.2 Smart Item Sorting & Storage

Feature: Automated Inventory & Chest Management

#### Inventory Analyzer

- [ ] Real-time item tracking
- [ ] Categorization system (tools, building, food, etc.)
- [ ] Stack size awareness
- [ ] Rarity/value scoring
- [ ] Durability tracking

#### Automated Sorting

- [ ] Auto-deposit to matching chests
- [ ] Custom sorting rules (user-defined)
- [ ] Multi-chest routing (distributes to chests)
- [ ] Stack combining (consolidates partial stacks)
- [ ] Duplicate detection

#### Storage System Integration

- [ ] Linked chest discovery (auto-finds storage areas)
- [ ] Item location tracking (where is X stored?)
- [ ] Storage capacity monitoring
- [ ] Low-stock alerts
- [ ] Storage optimization suggestions

#### Smart Retrieval

- [ ] Request specific item (auto-retrieves from storage)
- [ ] Bulk operations (get 64x of item X)
- [ ] Recipe-aware retrieval (get all components for recipe)
- [ ] Emergency retrieval (if inventory full)

---

## 4) Milestones (future-only)

### M0 — Waypoint categories + import/export

- [x] Categories/tags for waypoints and a shareable export format
- [x] Import/export UI in the new Navigation & Routing tab

### M1 — Multi-destination routing

- [x] Route objects (named routes with ordered legs)
- [x] Order optimization + total distance/time estimate
- [x] Resume from any waypoint in a route

### M2 — Smart return-to-home v2

- [x] Centralize triggers (health/tool/inventory/etc.) into a shared policy
- [x] ETA + lightweight route risk checks

### M3 — Avoidance zones + dimension/biome-aware routing

- [x] User-defined avoidance zones
- [ ] Nether/End awareness and preferred-biome routing

### M4 — Combat-aware movement foundation (opt-in)

- [x] Threat assessment + safe-zone identification
- [x] Strafe/kite/retreat modes; lag compensation

### M5 — Farm automation foundation

- [ ] Crop detection + harvest/replant loop
- [ ] Items/hour metrics + bounded scanning

### M6 — Storage sorting & retrieval foundation

- [ ] Storage discovery + item location index
- [ ] Rule-based deposit/retrieve + bulk operations

---

## 5) Acceptance criteria

- Features are bounded: no unbounded scanning, no render allocations in hot paths.
- Return-to-home is consistent across automations and never loops forever.
- Combat-aware movement is opt-in and multiplayer-default OFF where commonly disallowed.
- Farming/sorting are interruptible and have explicit stop conditions.
