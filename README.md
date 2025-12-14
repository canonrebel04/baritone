# Baritone - Meteor Client Integration Fork

A fork of [Baritone](https://github.com/cabaletta/baritone) optimized for seamless integration with Meteor Client, featuring enhanced pathfinding, automation capabilities, and intelligent navigation systems.

## 🌟 Features

### Core Baritone

- **Intelligent Pathfinding**: Advanced A\* pathfinding with cost optimization
- **Mining Automation**: `#mine <block>` - Efficient ore/block mining with optimal pathing
- **Exploration**: `#explore` - Systematic terrain exploration
- **Following**: `#follow entity <name>` - Follow players or entities
- **Building**: `#build <schematic>` - Automated structure building

### Meteor Integration Enhancements

- **Settings GUI**: In-game interface for all Baritone settings (no config file editing)
- **Path Visualization**: Real-time path rendering with cost estimates and ETA
- **Task Actions**: Quick access buttons for common tasks
- **Safe Mode**: Conservative settings preset for dangerous scenarios
- **Recovery System**: Automatic handling of stuck states and failures

### Smart Navigation

- **Avoidance System**: Define zones that Baritone will path around
- **Threat-Aware Pathing**: Factor in nearby hostile entities when planning routes
- **Stuck Detection**: Automatically detect and recover from stuck states
- **Damage Prediction**: Estimate path danger before committing

### Automation Features

- **Farm Integration**: Works with Meteor's TreeFarm and AutoFarm modules
- **Auto-Return**: Return to designated home location on pause
- **Goal Persistence**: Goals survive client restarts
- **Multi-Goal Queueing**: Chain multiple objectives

## 🚀 Building

### Prerequisites

- Java 21+
- Gradle 8.11+

### Build Commands

**Fabric (for Meteor Client)**:

```bash
cd fabric
./gradlew build
```

Output: `fabric/build/libs/baritone-standalone-fabric-*.jar`

**Standalone Jar**:

```bash
./gradlew build
```

## 📦 Installation

### With Meteor Client

This version is bundled with the [Meteor Client fork](https://github.com/canonrebel04/meteor-client). No separate installation needed.

### Standalone

1. Download from [Releases](https://github.com/canonrebel04/baritone/releases)
2. Place in `.minecraft/mods/` folder
3. Requires Fabric Loader and Fabric API

## 📖 Commands

All commands use the `#` prefix.

### Navigation

```bash
#goto <x> <y> <z>           # Path to coordinates
#path <x> <y> <z>           # Same as goto
#follow entity <name>       # Follow entity
#follow player <name>       # Follow player
#stop                       # Cancel current goal
#come                       # Path to your location
```

### Mining & Gathering

```bash
#mine <block>               # Mine specific block type
#mine diamond_ore 64        # Mine until inventory has 64
#farm                       # Farm nearby crops
#explore                    # Explore new chunks
```

### Building

```bash
#build <schematic>          # Build from schematic
#sel pos1                   # Set selection corner 1
#sel pos2                   # Set selection corner 2
```

### Settings

```bash
#set <setting> <value>      # Change setting
#set list                   # List all settings
#settings                   # Show current settings
#reset                      # Reset to defaults
```

### Utility

```bash
#help                       # Show help
#status                     # Show current status
#version                    # Show Baritone version
```

## ⚙️ Key Settings

Access via `#set <setting> <value>` or the in-game GUI:

### Pathfinding

- `allowBreak` - Allow breaking blocks (default: true)
- `allowPlace` - Allow placing blocks (default: true)
- `allowParkour` - Enable parkour movements (default: true)
- `allowSprint` - Allow sprinting (default: true)

### Safety

- `avoidance` - Avoid dangerous situations (default: true)
- `mobAvoidanceCoefficient` - How much to avoid mobs (default: 2.0)
- `assumeWalkOnWater` - Treat water as walkable (default: false)

### Performance

- `primaryTimeoutMS` - Path calculation timeout (default: 1000)
- `planAheadBlocks` - How far to plan ahead (default: 20)

## 🔧 Notable Differences from Upstream

- **Meteor Integration**: Native support for Meteor Client's systems
- **Enhanced GUI**: Settings accessible through Meteor's GUI
- **Avoidance Zones**: Integration with Meteor's zone system
- **Threat Detection**: Uses Meteor's threat manager for safer pathing
- **Auto-Return**: Coordinate with AutoReturn module
- **Metrics**: Telemetry for performance monitoring

## 📚 Documentation

- [Baritone Features](FEATURES.md) - Comprehensive feature list
- [Setup Guide](SETUP.md) - Installation and configuration
- [Usage Guide](USAGE.md) - Command reference and examples

## 🤝 Contributing

This is a fork optimized for Meteor Client integration. For core Baritone contributions, see the [upstream repository](https://github.com/cabaletta/baritone).

For integration-specific features:

1. Fork this repository
2. Create a feature branch
3. Submit a pull request

## 📜 License

This project is licensed under the GNU Lesser General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

## 🙏 Credits

- **Original Baritone**: [cabaletta](https://github.com/cabaletta/baritone) and [contributors](https://github.com/cabaletta/baritone/graphs/contributors)
- **Fabric Port**: [WagYourTail](https://github.com/wagyourtail)
- **Integration**: [canonrebel04](https://github.com/canonrebel04)

## ⚠️ Disclaimer

Baritone is a powerful automation tool. Use responsibly:

- Always follow server rules
- Respect other players
- Use ethically in multiplayer
- Primarily intended for single-player or authorized use

---

**Upstream**: [cabaletta/baritone](https://github.com/cabaletta/baritone)  
**Integration**: [canonrebel04/meteor-client](https://github.com/canonrebel04/meteor-client)
