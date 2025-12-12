# Baritone 1.21.10 Fork - Development Plan & Progress Tracker

**Project Start Date**: December 11, 2025  
**Target Minecraft Version**: 1.21.10 (Official, released October 7, 2025)  
**Target Mod Loader**: Fabric (Loader 0.18.2)  
**Java Version**: Java 21  
**Status**: Under Active Development

---

## Project Overview

This is a fork of Baritone (Minecraft pathfinding bot) maintained for **Minecraft 1.21.10** with integration into the Meteor Client framework. This project inherits from the fork chain:

- **Original**: cabaletta/baritone (up to 1.21.3)
- **Upstream 1**: wagyourtail/baritone
- **Upstream 2**: MeteorDevelopment/baritone (1.21.10 Meteor Client version)
- **Current**: canonrebel04/baritone (branch: `1.21.10`)

### Key Design Decisions

- **Fabric-Only**: Multi-loader support (Forge/NeoForge/Tweaker) is disabled for simpler maintenance
- **Meteor Integration**: Integrated with Meteor Client for advanced utilities and UI
- **Version Lock**: Will track Minecraft releases and upgrade as new versions become available (currently 1.21.11 exists, but maintaining 1.21.10)
- **Upstream Syncing**: Plan periodic merges from MeteorDevelopment/baritone as features/fixes become available

---

## Core Architecture & Components

### Source Code Organization

```
src/
├── api/              # Public API interfaces (BaritoneAPI, Settings, Goals, Commands)
├── main/             # Implementation (Baritone.java, processes, behaviors, pathfinding)
├── launch/           # Fabric Mixin layer (MixinMinecraft, chunk caching, player controller)
├── schematica_api/   # Schematic mod compatibility stubs
└── test/             # Unit tests
```

### Key Systems

| System | Location | Purpose |
|--------|----------|---------|
| **Pathfinding** | `src/main/java/baritone/pathing/` | A* pathfinding, path execution, movement costs |
| **Chunk Caching** | `src/main/java/baritone/cache/` | World data caching for long-distance navigation |
| **Processes** | `src/main/java/baritone/process/` | Mine, Build, Follow, Explore, Farm, Elytra |
| **Commands** | `src/main/java/baritone/command/` | In-game command system with prefix (#) |
| **Settings** | `src/api/java/baritone/api/Settings.java` | 100+ configurable options |
| **Events** | `src/api/java/baritone/api/event/` | Event bus system for extensibility |
| **Behaviors** | `src/main/java/baritone/behavior/` | Look, Pathing, Inventory, Waypoints |

### Entry Points

- `BaritoneAPI` - Public singleton for accessing Baritone instances
- `BaritoneProvider` - Creates and manages Baritone instances
- `Baritone` (main class) - Core bot instance
- Fabric Mixin - Hooks into Minecraft lifecycle (`FabricClientMixin` plugin)

### Build System

- **Tool**: Gradle with Unimined plugin (modern Minecraft mapping system)
- **Obfuscation**: ProGuard 7.4.2 (deterministic, Docker-reproducible builds)
- **Artifact Types**:
  - `baritone-api-fabric-*.jar` - Obfuscated except API packages
  - `baritone-standalone-fabric-*.jar` - Fully obfuscated (best performance)
  - `baritone-unoptimized-fabric-*.jar` - No obfuscation (debugging)

---

## Development Roadmap

### Phase 1: Validation & Build System ✅ COMPLETE

**Goal**: Establish working build system and validate all dependencies

- [x] **Step 1.1**: Verify Minecraft 1.21.10 version (COMPLETED - Official release Oct 7, 2025)
- [x] **Step 1.2**: Test initial build with `./gradlew clean build` (Found Gradle config issues)
- [x] **Step 1.3**: Fix Unimined/Fabric configuration namespace errors
- [x] **Step 1.4**: Fix Mixin loading conflicts in Fabric module  
- [x] **Step 1.5**: Resolve API source set compilation configuration
- [x] **Step 1.6**: Build Fabric module successfully
- [x] **Step 1.7**: Document build pipeline and artifact generation
- [x] **Step 1.8**: Ensure Meteor Client integration works without errors

**Current Status**: Completed.

**What was fixed**:
- Root source sets (`api`, `launch`, `schematica_api`) are now defined early enough for Unimined to attach Minecraft configs.
- Unimined is applied to each of those source sets (plus `main`) using a shared helper, and the root project is forced onto a working provider (Fabric) so it doesn’t end up compiling against an empty/invalid “none-merged” jar.
- Toolchain auto-provisioning was enabled via the Foojay resolver plugin in `settings.gradle` to satisfy Unimined’s Java 11 toolchain needs.

**Verified**:
- `./gradlew :compileApiJava` succeeds.
- `./gradlew :fabric:build` succeeds and produces the Fabric artifacts.
- `./gradlew :fabric:runClient --dry-run` configures successfully and the task exists (actual client launch still depends on having a usable graphical environment).

#### Step 1.7: Build pipeline & artifacts (verified)

**Baritone build / dist generation**

```bash
cd /home/cachy/baritone

# Clean + build Fabric artifacts
./gradlew clean :fabric:build

# Creates distribution JARs
./gradlew :fabric:createDist
```

**Where artifacts are produced**
- `fabric/build/libs/` contains dev jars produced by `jar`, `shadowJar`, `remapJar` (for development/testing).
- `dist/` contains the distributable jars produced by `:fabric:createDist` (including the published `baritone-api-fabric-<version>.jar`).

**Local publishing (for Meteor Client testing)**

```bash
cd /home/cachy/baritone
./gradlew :fabric:publishToMavenLocal

# Expected local Maven location
ls -la ~/.m2/repository/meteordevelopment/baritone/1.21.10-SNAPSHOT/
```

#### Step 1.8: Meteor Client integration test (verified)

**Meteor Client local dependency wiring (for local testing)**
- Add `mavenLocal()` in Meteor’s `repositories {}` so it can resolve the locally published Baritone.
- Add `modRuntimeOnly(libs.baritone)` so Baritone classes exist at dev runtime.
- Add Baritone’s dependency repo `https://babbaj.github.io/maven/` and `runtimeOnly("dev.babbaj:nether-pathfinder:1.4.1")` so Baritone doesn’t crash on `NetherPathfinder`.

**Meteor build / run**

```bash
cd /home/cachy/meteor-client

# Verify it builds against the local Baritone publish
./gradlew build

# Actually launch the client (use a timeout for quick validation)
timeout 120s ./gradlew runClient --no-daemon
```

**Observed runtime validation**
- Fabric Loader listed `baritone-meteor 1.21.10-SNAPSHOT` loaded.
- Meteor logged `Path Manager: Baritone`.
- `nether-pathfinder` native library loaded successfully.

**Success Criteria** (unchanged):
- `./gradlew :fabric:build` completes without errors (CURRENTLY: PASS)
- All source sets compile (api, main, launch, schematica_api) (CURRENTLY: PASS)
- IDE shows no unresolved symbols (after refresh)
- All three JAR variants generate correctly
- Meteor Client can load Baritone without conflicts


---

### Phase 2: Dependency Updates 🚧 IN PROGRESS

**Goal**: Modernize dependencies while maintaining compatibility (one change at a time)

**Rules for Phase 2**:
- Change one version, rebuild, and re-run basic runtime validation.
- If anything breaks, revert that single bump before trying the next.

**Phase 2.1 (COMPLETED)**: Fabric Loader bump (verified)
- Changed `fabric_version` in `gradle.properties`: `0.17.2` → `0.18.2`
- Verified build:

```bash
cd /home/cachy/baritone
./gradlew :fabric:build
```

- Verified run task configuration still works:

```bash
cd /home/cachy/baritone
./gradlew :fabric:runClient --dry-run
```

**Phase 2.2 (COMPLETED)**: Fabric API bump (Meteor Client runtime test harness)

Notes:
- Baritone itself does not currently depend on Fabric API; this bump is for the Meteor-side runtime validation project.

- Changed `fabric-api` in `/home/cachy/meteor-client/gradle/libs.versions.toml`: `0.136.0+1.21.10` → `0.138.0+1.21.10`

- Verified Meteor build:

```bash
cd /home/cachy/meteor-client
./gradlew build
```

- Verified Meteor dev runtime still loads Baritone:

```bash
cd /home/cachy/meteor-client
timeout 90s ./gradlew runClient --no-daemon
```

- Observed `baritone-meteor` loaded and `Path Manager: Baritone`.

**Phase 2.3 (COMPLETED)**: ASM bump (verified)
- Changed `asm_version` in `gradle.properties`: `9.7` → `9.9`
- Verified build:

```bash
cd /home/cachy/baritone
./gradlew :fabric:build
```

- Verified run task configuration still works:

```bash
cd /home/cachy/baritone
./gradlew :fabric:runClient --dry-run
```

**Potential Updates** (requires testing):
- Fabric Loader: 0.17.2 → 0.18.2 (COMPLETED)
- ASM: 9.7 → 9.9 (COMPLETED)
- Unimined plugin: Verify current version (1.2.9 or 1.4.1)
- Parchment mappings: Verify availability for 1.21.10

**Decision**: Continue iterating until a desired baseline is reached.

---

### Phase 3: Feature Testing & Validation 🚧 IN PROGRESS

**Goal**: Verify all core Baritone features work correctly on 1.21.10

**Phase 3.1 (COMPLETED)**: Automated unit test baseline

```bash
cd /home/cachy/baritone
./gradlew test
```

Baseline verify (2025-12-11)

```bash
cd /home/cachy/baritone
./gradlew test
./gradlew :fabric:build
./gradlew :fabric:runClient --dry-run
```

Notes:
- Tests compile against `net.minecraft.*`; the root `test` source set is now configured with Unimined Minecraft+mappings to support this.
- Current unit test areas: cache (`CachedRegionTest`), pathing goals (`GoalGetToBlockTest`), movement costs (`ActionCostsTest`), open sets (`OpenSetsTest`), and pathing utils (`BetterBlockPosTest`, `PathingBlockTypeTest`).

**Test Scope**:
- [ ] Pathfinding system (basic navigation, A*)
- [ ] Mining automation
- [ ] Building from schematics
- [ ] Block breaking and placement
- [ ] Command system (chat integration)
- [ ] Settings persistence
- [ ] Chunk caching
- [ ] Elytra flight
- [ ] Follow system
- [ ] Meteor Client GUI integration

**Phase 3.X (COMPLETED)**: Success-rate telemetry (pathing + elytra)

Goal: make later optimization work data-driven (focus: success rate; secondary: speed).

Implementation approach (minimal)
- Add a lightweight metrics recorder inside Baritone (no separate mod required).
- Output as **JSONL** (one JSON object per event) to a file under the game directory, e.g. `baritone/metrics.jsonl`.
- Make it opt-in via settings/commands (e.g., `#metrics start|stop`, `#metrics reset`, `#metrics flush`).

Implemented (current)
- Output file: `<gameDir>/baritone/metrics.jsonl`
- Commands:
   - `#metrics status` (default)
   - `#metrics start` / `#metrics stop`
   - `#metrics flush` / `#metrics reset`
   - `#metrics path`
- Events (so far):
   - `path_start`: start position, goal/effective-goal (simplified or not), timeouts, segment (current/next)
   - `path_end`: success/result_type/time, nodes considered, path src/dst, length, cost (ticks), segment (current/next)
   - `elytra_start`: destination, start position, start distance (3D + XZ), settings snapshot
   - `elytra_end`: success/reason/time, ticks + glide ticks, start/end/min distance (3D + XZ), speed stats, overshoot flag, end state/landing spot

Metrics to record (start small)
- Global: `timestamp`, `mc_version`, `baritone_version`, key settings snapshot (at least elytra + pathing-related settings).
- Pathing attempt (A*): `goal_type`, `start`, `goal`, `success`, `fail_reason`, `time_ms`, `nodes_expanded`, `open_set_peak`, `path_length`, `path_cost`.
- Elytra attempt (`baritone.process.ElytraProcess` / `baritone.process.elytra.ElytraBehavior`):
   - `route_type` (overworld/nether if relevant), `success`, `fail_reason`, `time_ms`, `replans`, `emergency_land_triggered`.
   - Control quality (for overshoot/jitter): `overshoot_events`, `max_cross_track_error`, `max_alt_error`, `firework_uses`, `collisions_or_near_misses`.
   - Ray workload (for speed): `raycasts_count`, `raycasts_time_ms` (coarse timing), plus optional sampled debug rays.

Optional profiler (speed work)
- Use **JFR** captures for CPU hotspots during flight/pathing sessions; use telemetry to correlate “slow” runs with call stacks.

---

### Phase 4: Bug Fixes & Improvements 🚧 IN PROGRESS

**Goal**: Fix issues found during Phase 3 and implement improvements

**Phase 4.0 (NEXT)**: Collect telemetry + confirm top failure modes

Notes:
- We have the instrumentation needed (`path_*`, `elytra_*`).
- Next work should be driven by real `baritone/metrics.jsonl` captures (success-rate + overshoot/speed signals).

Workflow tip:
- Use `#metrics mark <label>` to write `mark` events into the JSONL so you can segment runs (e.g. `goto_overworld_1`, `follow_nether_2`).
- Each `#metrics start` begins a new `session_id` and writes a `session_start` event with basic version + settings snapshot.

Concrete capture recipe (repeat per scenario):
1) `#metrics reset`
2) `#metrics start`
3) `#metrics mark <label>`
4) Perform the action (`#goto ...`, `#follow ...`, etc)
5) `#metrics stop`

Suggested scenarios (keep each run short, one action per mark label):
- `goto_overworld_short_1`: short `#goto x z` in overworld
- `goto_nether_short_1`: short `#goto x z` in nether
- `follow_players_1`: `#follow players` (or `#follow player <name>`) for ~30–60s
- `follow_entities_1`: `#follow entities` (or `#follow entity <type>`) for ~30–60s

Analysis commands:

```bash
# Replace with your instance path
python3 scripts/metrics_summary.py /path/to/.minecraft/baritone/metrics.jsonl --by-mark

# Drill into dominant path failures (prints example attempt IDs with correlated mark/command)
python3 scripts/metrics_summary.py /path/to/.minecraft/baritone/metrics.jsonl --by-mark --examples 3
```

What to look for:
- `Pathing (path_end)` overall success-rate + latency.
- `Top result_type (failures)` to identify dominant failure modes.
- `By command (path_end, correlated)` to compare `goto` vs `follow` outcomes (correlated via `path_attempt_id`).
- `By mark label` to compare scenario-to-scenario quickly.

**Potential Areas**:
- Performance optimizations
- New pathfinding algorithms
- Enhanced schematic support
- Improved command interface
- Meteor Client feature additions

**Elytra focus (success rate + speed)**
- Reduce overshoot/jump-off failures by turning common failure modes into explicit metrics + targeted fixes.
- Use telemetry to drive changes in `ElytraBehavior` (raycast pruning, caching, fewer per-tick allocations) while watching success-rate regressions.

---

### Phase 5: Upstream Sync Strategy ⏳ ONGOING

**Goal**: Keep in sync with MeteorDevelopment/baritone for new features/fixes

**Strategy**:
- Monitor MeteorDevelopment/baritone for releases and updates
- When Minecraft 1.21.11, 1.21.12, etc. are released:
  1. Check if Meteor has updated their fork
  2. Evaluate changes and test compatibility
  3. Merge or cherry-pick relevant updates
  4. Test full build and features
  5. Tag release if stable
- Avoid auto-merging; manual review all upstream changes

**Upstream Repositories**:
- `MeteorDevelopment/baritone` - Primary upstream (Meteor 1.21.10 version)
- `wagyourtail/baritone` - Secondary reference
- `cabaletta/baritone` - Original repository (currently at 1.21.3)

---

### Phase 6: Upgrade Baseline to Minecraft 1.21.11 ⏳ PLANNED (LAST)

**Goal**: Move this fork from the 1.21.10 baseline to **Minecraft 1.21.11** while keeping Fabric + Meteor integration working.

**Rules**:
- One axis at a time (Minecraft version → mappings → loader/API → integration), rebuild after each change.
- If something breaks, revert the single bump and isolate the failing delta.

**Planned Steps**:
- [ ] Update Baritone Minecraft version + mappings
   - Update Unimined `minecraft { version(...) }` to `1.21.11`.
   - Update Yarn/Intermediary/Mojmap/Parchment versions as needed for `1.21.11`.
- [ ] Update Fabric module metadata
   - Update `fabric/src/main/resources/fabric.mod.json` (Minecraft version range + loader constraints if required).
- [ ] Rebuild Baritone and publish locally

```bash
cd /home/cachy/baritone
./gradlew clean :fabric:build
./gradlew :fabric:createDist
./gradlew :fabric:publishToMavenLocal
```

- [ ] Update Meteor Client harness to 1.21.11
   - Update `/home/cachy/meteor-client/gradle/libs.versions.toml`: `minecraft`, `yarn-mappings`, and `fabric-api` to `+1.21.11` variants.
   - Keep `modRuntimeOnly(libs.baritone)` and `runtimeOnly("dev.babbaj:nether-pathfinder:1.4.1")` so runtime validation remains meaningful.
- [ ] Validate Meteor build + runtime

```bash
cd /home/cachy/meteor-client
./gradlew build
timeout 120s ./gradlew runClient --no-daemon
```

**Success Criteria**:
- Baritone compiles for all source sets (`api`, `main`, `launch`, `schematica_api`).
- `:fabric:createDist` produces all expected jars.
- Meteor runtime logs show `baritone-meteor` loaded and `Path Manager: Baritone`.

---

## Current Status

### Configuration Summary

| Property | Value | Status |
|----------|-------|--------|
| Minecraft | 1.21.10 | ✅ Official release |
| Mod Loader | Fabric | ✅ Primary target |
| Fabric Version | 0.18.2 | ✅ Updated |
| Java | Java 21 | ✅ Supported |
| Build Tool | Gradle + Unimined | ✅ Compatible |
| Meteor Integration | baritone-meteor | ✅ Configured |

### Known Issues

1. **IDE Compilation Errors** (~4800 unresolved symbols)
   - **Cause**: Incomplete Gradle sync / Unimined remapping cache
   - **Status**: False positives, should resolve after `./gradlew clean build`
   - **Fix**: Run `./gradlew --refresh-dependencies` and IDE reimport

2. **Deprecated Fabric Loader**
   - **Issue**: 0.17.2 released Aug 2025, 0.18.2 available (Dec 2025)
   - **Status**: ✅ Updated to 0.18.2 (Phase 2.1)
   - **Fix**: None

3. **Multi-Loader Support Disabled**
   - **Issue**: Forge/NeoForge modules exist but are disabled
   - **Status**: By design (Fabric-only focus)
   - **Action**: Delete `forge/`, `neoforge/`, `tweaker/` if cleanup desired

### Verified Dependencies

| Dependency | Version | Compatible | Notes |
|------------|---------|-----------|-------|
| nether-pathfinder | 1.4.1 | ✅ Yes | No breaking changes for 1.21.10 |
| Mixin | 0.8.5 | ✅ Yes | Fabric Loader provides newer version |
| ASM | 9.9 | ✅ Yes | Updated (Phase 2.3) |
| Parchment | 1.21.4 | ✅ Yes | Mappings available for 1.21.10 |

---

## Next Steps

### Immediate Actions (What to do now)

1. **Create/Review this Plan Document** ✅ COMPLETED
   - This file serves as living documentation
   - Update as progress is made
   - Reference for future decisions

2. **Execute Phase 1 - Build Validation**
   - Run `./gradlew clean build` to test compilation
   - Document any errors found
   - Trigger IDE refresh to clear false positive errors
   - Verify generated JARs in `fabric/build/libs/`

3. **Set Up Development Environment**
   - Clone/verify meteor-client repo is accessible
   - Understand Meteor Client integration points
   - Plan how to test Baritone + Meteor together

4. **Future Planning**
   - Once Phase 1 passes, determine Phase 2/3 priority
   - Consider: Feature testing vs. Dependency updates
   - Plan release tagging strategy

### Telemetry Capture Workflow (for later optimization)

When collecting data for success-rate/speed work, capture a reproducible “run bundle”:
- World info: `singleplayer` vs `server`, seed (if SP), dimension, coordinates, render distance/simulation distance.
- Baritone settings snapshot (at least: elytra + pathing related settings).
- Scenario: start coords + goal coords, plus the exact command used.
- Output files:
   - `baritone/metrics.jsonl` (primary)
   - Optional JFR: `baritone/flight.jfr`

Then either paste a small excerpt + summary stats here, or add the file(s) to the workspace so I can aggregate and compare before/after.

Quick local summary (optional):

```bash
cd /home/cachy/baritone
python3 scripts/metrics_summary.py /path/to/.minecraft/baritone/metrics.jsonl
```

---

## Meteorcore Linking & Integration

### How Meteor Client Integrates with Baritone

- **Mod ID**: `baritone-meteor` (to avoid conflicts with other Baritone versions)
- **Maven Repo**: Published to `maven.meteordev.org/snapshots`
- **Mixin Config**: `mixins.baritone-meteor.json`
- **Mod Metadata**: See `fabric/src/main/resources/fabric.mod.json`
- **Fabric Version Range**: `[1.21.9, 1.21.10]` (both versions supported)

### Meteor Client Compatibility

- Meteor Client is an **actively maintained** Fabric utility mod
- It bundles/references Baritone for pathfinding automation
- The fork chain shows Meteor has own integration branch
- This fork maintains compatibility with Meteor ecosystem

### Testing Considerations

- When testing Baritone features, verify Meteor Client loads correctly
- Check for mixin conflicts if other mods are installed
- Monitor Meteor Client GitHub for breaking changes

---

## Repository Information

- **Current Branch**: `1.21.10` (as of Dec 11, 2025)
- **Fork Chain**: cabaletta/baritone → wagyourtail/baritone → MeteorDevelopment/baritone → canonrebel04/baritone
- **License**: LGPL-3.0 with anime exception (see LICENSE file)
- **Code Quality**: High-quality codebase with good separation of concerns

---

## Document Maintenance

**Last Updated**: December 11, 2025  
**Maintained By**: Development team  
**Update Frequency**: As new work is completed or major decisions are made

### How to Update This Document

1. After each completed step, mark it with ✅
2. If blockers are found, update "Known Issues" section
3. Add progress notes in relevant phase sections
4. Update "Current Status" when versions/dependencies change
5. Add new phases as they arise during development

---

## References

- **Official Minecraft Wiki**: https://minecraft.wiki/w/Java_Edition_version_history
- **Fabric Mod Loader**: https://fabricmc.net/
- **Meteor Client**: https://github.com/MeteorDevelopment/meteor-client
- **Unimined Plugin**: https://github.com/unimined/unimined
- **Original Baritone**: https://github.com/cabaletta/baritone

---

## Quick Links to Key Files

- **Build Config**: [gradle.properties](gradle.properties)
- **Root Build Script**: [build.gradle](build.gradle)
- **Fabric Module**: [fabric/build.gradle](fabric/build.gradle)
- **Fabric Mod Config**: [fabric/src/main/resources/fabric.mod.json](fabric/src/main/resources/fabric.mod.json)
- **API Entry Point**: [src/api/java/baritone/api/BaritoneAPI.java](src/api/java/baritone/api/BaritoneAPI.java)
- **Main Implementation**: [src/main/java/baritone/Baritone.java](src/main/java/baritone/Baritone.java)

---

**END OF DOCUMENT**
