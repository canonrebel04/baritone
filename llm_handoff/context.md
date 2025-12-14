# Workspace Continuation Context (2025-12-14)

## Repos in this workspace

- Baritone repo: `/home/cachy/baritone`
- Meteor Client repo: `/home/cachy/meteor-client`

### Git state (known-good)

- Baritone: `master` @ `50df51dc439d4f1421ff39566b67578aafce9cf1` (also `origin/master`)
- Meteor Client: `master` @ `5c46172e0e9d240de6a187ef3b9d2411bc49cb36` (also `origin/master`)

### Build verification

- Baritone: `./gradlew --no-daemon test :fabric:build` ran successfully on `master`.
- Meteor Client: `./gradlew --no-daemon build -x test` previously succeeded.

## What was implemented (high signal)

### AutoCraft (Meteor)

- Container memory + selection controls:
  - Per-container enable/disable.
  - Global “use only selected containers” mode.
  - UI controls: Select All/None, Clear Memory, Clear Chests (snapshots-only), Reset Selection.
- Crafting reliability:
  - Filters to crafting recipes only.
  - Uses correct `syncId` for `clickRecipe`.
  - Crafts + places a crafting table automatically when none is nearby.

### Mining automation (Meteor)

- OreScanner stop conditions + optional return-home:
  - Stops when inventory is full.
  - Stops when tool durability is below threshold.
  - Optional path back to a waypoint named `Home`.

### Swarm (Meteor)

- Versioned structured protocol with allowlisted actions (`SwarmProtocol` + worker enforcement).
- Trusted host and optional token gating.

### Navigation/Exploration (Meteor)

- Waypoint rendering + editing UI exists (`WaypointsModule`).
- Navigator module exists (waypoint-name or coords marker).
- NewChunks module exists.
- CaveFinder module exists.
- POI detector exists (bounded sampling → auto-waypoints).

## Plan update

- Baritone project plan was pruned to remove already-implemented parts and extended with:
  - **PHASE 2**: advanced pathfinding/movement (routes, avoidance zones, return-home v2, combat-aware movement).
  - **PHASE 3**: intelligent resource management (farming + storage sorting/retrieval).

See: `baritone/PROJECT_PLAN.md`.

## Key files (starting points)

Meteor Client:

- `src/main/java/meteordevelopment/meteorclient/systems/modules/world/AutoCraft.java`
- `src/main/java/meteordevelopment/meteorclient/systems/autocraft/AutoCraftMemory.java`
- `src/main/java/meteordevelopment/meteorclient/gui/tabs/builtin/AutoCraftTab.java`
- `src/main/java/meteordevelopment/meteorclient/systems/modules/world/OreScanner.java`
- `src/main/java/meteordevelopment/meteorclient/systems/modules/misc/swarm/SwarmProtocol.java`

Baritone:

- `PROJECT_PLAN.md` (roadmap)

## Next work (suggested)

- PHASE 2 routing foundations:
  - Waypoint categories/tags.
  - Import/export format for waypoints and named routes.
  - Route objects + order optimization + resume.
  - Return-to-home v2 triggers (add low health) and ETA.
- PHASE 3 foundations:
  - Storage discovery area + item-location index.
  - Rule-based deposit/retrieve and bulk operations.

## Safety / scope guardrails

- No bypass/evasion features.
- Remote control must remain allowlisted and safe-by-default.
- All scanners/overlays must be bounded and non-allocating on hot render paths.
