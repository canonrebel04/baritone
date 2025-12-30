# Installation

The easiest way to install Baritone is to install it as a Forge/NeoForge/Fabric mod.
If you are an advanced user, you can also use it with a custom `version.json` via the standalone tweaker.

Once Baritone is installed, look [here](USAGE.md) for instructions on how to use it.

## Prebuilt official releases

Releases are hosted on GitHub: [Releases](https://github.com/cabaletta/baritone/releases)

The mapping between major Minecraft versions and major Baritone versions is as follows:
| Minecraft version | 1.12 | 1.13 | 1.14 | 1.15 | 1.16 | 1.17 | 1.18 | 1.19 | 1.20 | 1.21 |
|-------------------|------|------|------|------|------|------|------|------|-------|-------|
| Baritone version | v1.2 | v1.3 | v1.4 | v1.5 | v1.6 | v1.7 | v1.8 | v1.9 | v1.10 | v1.11 |

Official releases are GPG signed by leijurv (44A3EA646EADAC6A).

## Build it yourself

Baritone uses Gradle for building. The project is modular, consisting of several subprojects that handle different aspects of the mod.

### Project Structure

- **:api**: The public API for Baritone.
- **:core**: The core implementation logic.
- **:launch**: Mixins and launch integration.
- **:schematica-api**: Compatibility layer for Schematica.
- **:fabric**: The Fabric mod wrapper and loader logic.
- **:tweaker**: The standalone/universal tweaker wrapper.

### Prerequisites

- **Java 21** (Required for Minecraft 1.20.5+)
- Git

### Building from Command Line

1.  Clone the repository:

    ```bash
    git clone https://github.com/cabaletta/baritone.git
    cd baritone
    ```

2.  Run the build command:
    - **Linux/macOS:**
      ```bash
      ./gradlew build
      ```
    - **Windows:**
      ```powershell
      gradlew build
      ```

### Artifacts

After a successful build, the artifacts will be generated in the `build/libs` directory of their respective subprojects:

- **Fabric/NeoForge Mod:**
  `fabric/build/libs/baritone-fabric-[version].jar`
  _This is the file you drop into your mods folder._

- **Standalone Tweaker:**
  `tweaker/build/libs/baritone-[version].jar`
  _Used for non-modloader environments._

- **API & Core Jars:**
  `api/build/libs/` and `core/build/libs/` contain the library jars used for development.

### IntelliJ IDEA

1.  Open IntelliJ IDEA.
2.  Select **Open** and choose the `baritone` directory (the root of the repo).
3.  IntelliJ should automatically detect it as a Gradle project. If asked, choose to "Import as Gradle Project".
4.  Wait for dependencies to download and indexing to complete.
5.  Run the `build` task from the Gradle tool window to verify setup.

## Github Actions

The repository uses GitHub Actions for CI. Pushes to branches can trigger automatic builds, artifacts from which are available in the Actions tab.
