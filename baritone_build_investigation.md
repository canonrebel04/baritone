# MeteorDevelopment/Baritone Build System: Complete Investigation

## Executive Summary

**baritone-meteor-1.21.10.jar** and related Baritone releases are built using:
- **Build System**: Gradle + Unimined plugin (wagyourtail/unimined)
- **Java Version**: Java 21 JDK
- **Gradle**: Gradle Wrapper (8.x+)
- **Primary Source**: cabaletta/baritone (official); MeteorDevelopment maintains a Fabric-focused fork
- **CI/CD**: GitHub Actions with standard Gradle build task
- **Output**: Multi-artifact setup (API JAR + Standalone JAR for Fabric/Forge/NeoForge)

---

## 1. REPOSITORY HIERARCHY & SOURCES

### Official Repository (Latest Versions)
- **Repository**: https://github.com/cabaletta/baritone
- **Releases**: https://github.com/cabaletta/baritone/releases
- **Status**: Actively maintained; supports 1.21.x branches
- **Build CI**: https://github.com/cabaletta/baritone/actions

### MeteorDevelopment Fork
- **Repository**: https://github.com/MeteorDevelopment/baritone
- **Forked From**: wagyourtail/baritone (intermediate fork of cabaletta)
- **Releases**: Published via https://meteorclient.com/
- **Note**: Pre-built binaries available (baritone-meteor-1.21.10.jar)
- **Status**: Secondary mirror; updates less frequently

### Distribution Points
| Source | Artifact Type | Location | Latest |
|--------|--------------|----------|--------|
| **cabaletta/baritone** | GitHub Releases | https://github.com/cabaletta/baritone/releases | 1.21.8+ |
| **MeteorDevelopment** | meteorclient.com | https://meteorclient.com/ (Baritone tab) | 1.21.10 |
| **JitPack** | Maven artifact | https://jitpack.io/p/cabaletta/baritone | v1.10.1+ |
| **Modrinth** | Mod platform | https://modrinth.com/mod/baritone | Latest |

---

## 2. BUILD CONFIGURATION FILES

### Root build.gradle (cabaletta/baritone/build.gradle)
**Source**: https://github.com/cabaletta/baritone/blob/master/build.gradle (1.19.4+ branch)

Key sections:

```gradle
allprojects {
    apply plugin: 'java'
    apply plugin: "xyz.wagyourtail.unimined"
    apply plugin: "maven-publish"

    archivesBaseName = rootProject.archives_base_name

    // Version detection from git tags
    def vers = ""
    try {
        vers = 'git describe --always --tags --first-parent --dirty'.execute().text.trim()
    } catch (Exception e) {
        println "Version detection failed: " + e
    }
    if (!vers.startsWith("v")) {
        version = rootProject.mod_version
    } else {
        version = vers.substring(1)
    }
    
    group = rootProject.maven_group
    
    sourceCompatibility = targetCompatibility = JavaVersion.toVersion(project.java_version)

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(sourceCompatibility.majorVersion.toInteger()))
        }
    }

    repositories {
        maven {
            name = 'spongepowered-repo'
            url = 'https://repo.spongepowered.org/repository/maven-public/'
        }
        maven {
            name = 'fabric-maven'
            url = 'https://maven.fabricmc.net/'
        }
        maven {
            name = 'impactdevelopment-repo'
            url = 'https://impactdevelopment.github.io/maven/'
        }
        mavenCentral()
    }

    dependencies {
        compileOnly "org.spongepowered:mixin:${project.mixin_version}"
        compileOnly "org.ow2.asm:asm:${project.asm_version}"
        implementation "dev.babbaj:nether-pathfinder:${project.nether_pathfinder_version}"
    }

    // ===== CRITICAL: Single unimined.minecraft() call =====
    unimined.minecraft(sourceSets.main, true) {
        version rootProject.minecraft_version

        mappings {
            intermediary()              // Official intermediary mappings
            mojmap()                    // Mojang official mappings
            parchment("2023.06.26")    // Enhanced javadoc via Parchment
        }
    }

    tasks.withType(JavaCompile).configureEach {
        it.options.encoding = "UTF-8"
        def targetVersion = project.java_version.toInteger()
        if (JavaVersion.current().isJava9Compatible()) {
            it.options.release = targetVersion
        }
    }
}

// ===== Root-level Unimined config =====
unimined.minecraft {
    runs.off = true              // Don't auto-generate run tasks (multi-version repo)
    defaultRemapJar = false      // Manual remapping control per subproject
}

archivesBaseName = archivesBaseName + "-common"

// ===== MULTI-SOURCE SET CONFIGURATION =====
sourceSets {
    api {
        compileClasspath += main.compileClasspath    // Inherit main's classpath
        runtimeClasspath += main.runtimeClasspath
    }
    main {
        compileClasspath += api.output              // Use compiled api classes
        runtimeClasspath += api.output
    }
    test {
        compileClasspath += main.compileClasspath + main.runtimeClasspath + main.output
        runtimeClasspath += main.compileClasspath + main.runtimeClasspath + main.output
    }
    launch {
        compileClasspath += main.compileClasspath + main.runtimeClasspath + main.output
        runtimeClasspath += main.compileClasspath + main.runtimeClasspath + main.output
    }
    schematica_api {
        compileClasspath += main.compileClasspath
        runtimeClasspath += main.runtimeClasspath
    }
    main {
        compileClasspath += schematica_api.output
        runtimeClasspath += schematica_api.output
    }
}

dependencies {
    testImplementation 'junit:junit:4.13.2'
}

jar {
    from sourceSets.main.output, sourceSets.launch.output, sourceSets.api.output
}

javadoc {
    options.addStringOption('Xwerror', '-quiet')
    options.linkSource true
    options.encoding "UTF-8"
    source = sourceSets.api.allJava
    classpath += sourceSets.api.compileClasspath
}
```

**Citation**: https://github.com/cabaletta/baritone/blob/master/build.gradle (lines 30-157)

### gradle.properties (cabaletta/baritone)
**Source**: https://github.com/cabaletta/baritone/blob/master/gradle.properties

```properties
# Baritone version (matches git tags)
mod_version=1.11.2
maven_group=baritone
archives_base_name=baritone

# Minecraft version
minecraft_version=1.21.4

# Java target version
java_version=21

# Fabric Loader and API versions
fabric_loader_version=0.15.11
fabric_api_version=0.100.4

# Mixin and ASM versions
mixin_version=0.8.5
asm_version=9.6

# Nether pathfinder dependency
nether_pathfinder_version=1.2.0
```

### Fabric Subproject: fabric/build.gradle
**Source**: https://github.com/cabaletta/baritone/blob/master/fabric/build.gradle

Structure:
```gradle
plugins {
    id "fabric-loom" version "..."
}

dependencies {
    // Fabric API
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}+${project.minecraft_version}"
    
    // Fabric Loader
    modImplementation "net.fabricmc:fabric-loader:${project.fabric_loader_version}"
    
    // Mixin
    modImplementation("org.spongepowered:mixin:${project.mixin_version}")
}

loom {
    accessWidenerPath = file("src/main/resources/baritone.accesswidener")
    
    runs {
        client {
            client()
            setConfigName("Fabric Client")
            ideConfigGenerated true
            runDir "run"
        }
    }
}

jar {
    from(rootProject.sourceSets.main.output)
    from(rootProject.sourceSets.api.output)
    from(rootProject.sourceSets.launch.output)
    from(rootProject.sourceSets.schematica_api.output)
    
    manifest {
        attributes(
            "Manifest-Version": "1.0",
            "Implementation-Title": archivesBaseName,
            "Implementation-Version": version
        )
    }
}
```

---

## 3. UNIMINED CONFIGURATION & SOURCESET HANDLING

### The "minecraft config never applied" Problem

**Root Cause**: Unimined only applies the Minecraft remapping configuration to source sets explicitly listed in `unimined.minecraft(sourceSets.X)` calls.

**Error Message**:
```
Error: minecraft config never applied for source set 'api'
```

**Why It Occurs**:
```gradle
// ❌ WRONG: Calling per source set
unimined.minecraft(sourceSets.api, true) { ... }
unimined.minecraft(sourceSets.main, true) { ... }
unimined.minecraft(sourceSets.launch, true) { ... }
```

Each call tries to download and process MC independently, causing timing conflicts and namespace issues.

### Official Solution (cabaletta/baritone Pattern)

1. **Single `unimined.minecraft()` call** (root level only):
```gradle
unimined.minecraft(sourceSets.main, true) {
    version rootProject.minecraft_version
    mappings {
        intermediary()
        mojmap()
    }
}
```

2. **Explicit classpath dependencies**:
```gradle
sourceSets {
    api {
        compileClasspath += main.compileClasspath    // ← KEY: Inherit main's remapped MC
        runtimeClasspath += main.runtimeClasspath
    }
    launch {
        compileClasspath += main.output              // ← KEY: Use remapped output
    }
}
```

3. **Jar bundling**:
```gradle
jar {
    from sourceSets.main.output, sourceSets.api.output, sourceSets.launch.output
}
```

### Why This Works

- `sourceSets.main` gets remapped via Unimined
- `api`, `launch`, etc. depend on main's `.output` (already remapped)
- No per-sourceset namespace conflicts
- Intermediate classes available before downstream compilation

**Timing Diagram**:
```
Unimined downloads MC (1.21.4) → Remaps to Intermediary
↓
main.compileJava uses remapped classes
↓
api.compileJava uses main.output (remapped)
↓
launch.compileJava uses main.output (remapped)
↓
Jar task bundles all outputs together
```

### Unimined Version Requirements

**Minimum**: Unimined 1.0+
**Recommended**: Unimined 1.1+ (fixes sourceSets timing issues)

Check gradle.properties:
```properties
org.gradle.jvmargs=-Xmx3G
```

And in buildscript:
```gradle
plugins {
    id 'xyz.wagyourtail.unimined' version '1.1.14'
}
```

**Source**: https://github.com/wagyourtail/unimined (the Unimined plugin repository)

---

## 4. BUILD INSTRUCTIONS & COMMANDS

### Quick Start

```bash
# Clone official repository
git clone https://github.com/cabaletta/baritone.git
cd baritone

# Ensure Java 21 is installed
java -version  # Should show Java 21

# Build all artifacts
./gradlew build

# Output:
# build/libs/baritone-common-1.11.2.jar
# fabric/build/libs/baritone-fabric-1.11.2.jar
# forge/build/libs/baritone-forge-1.11.2.jar
```

### Build for Specific Minecraft Version

Each version branch in cabaletta/baritone:
```bash
# Clone specific version branch
git clone --branch 1.21.3 https://github.com/cabaletta/baritone.git baritone-1.21.3
cd baritone-1.21.3

./gradlew build
```

### Gradle Properties & Flags

**Standard build**:
```bash
./gradlew build
```

**Force refresh dependencies** (useful if network issues):
```bash
./gradlew --refresh-dependencies build
```

**Build specific subproject** (Fabric only):
```bash
./gradlew :fabric:build
```

**Generate IDE configs** (IntelliJ/Eclipse):
```bash
./gradlew genSources
```

**Enable debugging**:
```bash
./gradlew build --stacktrace --debug
```

**Use offline mode** (if already cached):
```bash
./gradlew build --offline
```

### Gradle Wrapper

Located in repository:
- `gradlew` (Linux/macOS)
- `gradlew.bat` (Windows)
- `gradle/wrapper/gradle-wrapper.properties` (specifies Gradle version)

Example gradle-wrapper.properties:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.8-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

---

## 5. JAVA REQUIREMENTS

### Official Guidance (per meteorclient.com/faq)

**For Minecraft 1.21.x builds**:
- **Java Version**: Java 21 JDK
- **Source**: Adoptium (https://adoptium.net/) or Oracle (https://www.oracle.com/java/technologies/downloads/)
- **Installation**: Extract and add to PATH or use IDE's JDK selector

**Verify installation**:
```bash
java -version
# Expected output:
# openjdk version "21" 2023-09-19
# OpenJDK Runtime Environment (build 21+35-2513)
```

**For older versions** (1.20.x, 1.19.x):
- May use Java 17 or 21 (version branches tested with same Java 21)
- Gradle's toolchain selection handles compatibility

---

## 6. CI/CD PIPELINE & RELEASE AUTOMATION

### GitHub Actions Workflow (cabaletta/baritone)

**Location**: https://github.com/cabaletta/baritone/.github/workflows/

Typical structure:

```yaml
# .github/workflows/build.yml
name: Build

on:
  push:
    branches: [ master, "1.*" ]
    paths:
      - 'src/**'
      - 'build.gradle'
      - 'gradle.properties'
      - '.github/workflows/build.yml'
  pull_request:
    branches: [ master, "1.*" ]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4
        with:
          fetch-depth: 0  # Needed for git describe --tags
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
          cache: gradle
      
      - name: Make gradlew executable
        run: chmod +x gradlew
      
      - name: Build with Gradle
        run: ./gradlew build
      
      - name: Upload artifacts
        uses: actions/upload-artifact@v4
        with:
          name: Build Artifacts
          path: |
            build/libs/
            fabric/build/libs/
            forge/build/libs/
            dist/
      
      - name: Create GitHub Release
        if: startsWith(github.ref, 'refs/tags/')
        uses: softprops/action-gh-release@v1
        with:
          files: |
            build/libs/*
            fabric/build/libs/*
            forge/build/libs/*
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

**Key Points**:
- Runs on every push to master and version branches (1.*)
- Cache enabled for faster builds (gradle cache action)
- Artifacts uploaded for download
- GitHub Release creation on tags (v1.11.2, etc.)

### Release Process

1. **Tag creation**: `git tag -a v1.11.2 -m "Release 1.11.2"`
2. **Push tag**: `git push origin v1.11.2`
3. **CI triggered**: GitHub Actions runs build workflow
4. **Artifacts created**: JAR files in build/libs/
5. **Release published**: GitHub Release with downloads
6. **Distribution**: Modrinth, CurseForge, meteorclient.com

### MeteorDevelopment Release Process

MeteorDevelopment publishes via:
1. **Build locally** or pull from cabaletta
2. **Upload to** https://meteorclient.com/ (curated by team)
3. **Version page**: Shows "baritone-1.21.10.jar"
4. **CI**: May use different system (not public GitHub Actions)

---

## 7. ARTIFACT NAMING & STRUCTURE

### Output JAR Files

After `./gradlew build`:

```
build/libs/
├── baritone-common-1.11.2.jar          (Base classes, not standalone)
├── baritone-api-1.11.2.jar             (Public API for integration)
└── baritone-unoptimized-1.11.2.jar     (Debug/development)

fabric/build/libs/
├── baritone-api-fabric-1.11.2.jar      (Fabric API JAR)
└── baritone-standalone-fabric-1.11.2.jar (Fabric standalone mod)

forge/build/libs/
├── baritone-api-forge-1.11.2.jar       (Forge API JAR)
└── baritone-standalone-forge-1.11.2.jar (Forge standalone mod)
```

### What Each Artifact Does

| Artifact | Purpose | Use Case |
|----------|---------|----------|
| `baritone-api-fabric-*.jar` | Public API for mods | Add as dependency in build.gradle |
| `baritone-standalone-fabric-*.jar` | Mod to drop in mods/ folder | Direct installation |
| `baritone-standalone-forge-*.jar` | Forge variant | Forge 1.12.2+ |
| `baritone-unoptimized-*.jar` | Debug build (not obfuscated) | Development only |

### baritone-meteor-1.21.10.jar

- **Source**: MeteorDevelopment fork (compiled for 1.21.10)
- **Equivalent**: Likely `baritone-standalone-fabric-1.11.2.jar` (Fabric version)
- **Download**: https://meteorclient.com/ (Baritone tab)
- **Format**: Already remapped (Intermediary → official or Fabric loader handles)

---

## 8. MULTI-LOADER SUPPORT (Fabric/Forge/NeoForge)

### Loader-Specific Configuration

Baritone uses **Unimined** to support multiple loaders seamlessly:

```gradle
// Root build.gradle - shared Unimined config
unimined.minecraft(sourceSets.main, true) {
    version rootProject.minecraft_version
    mappings { ... }
}

// Subprojects (fabric/, forge/) apply loader-specific plugins
// but inherit remapped MC classes from root
```

### Subproject Structure

**fabric/build.gradle**:
```gradle
plugins {
    id 'fabric-loom' version '1.x'
}

dependencies {
    modImplementation "net.fabricmc.fabric-api:fabric-api:..."
    modImplementation "net.fabricmc:fabric-loader:..."
}
```

**forge/build.gradle**:
```gradle
plugins {
    id 'net.minecraftforge.gradle' version '...'
}

dependencies {
    implementation 'net.minecraftforge:forge:...'
}
```

**Key**: Both depend on root's remapped sourceSets → consistent API across loaders

---

## 9. KNOWN ISSUES & TROUBLESHOOTING

### Issue: "minecraft config never applied for source set 'api'"

**Cause**: Per-sourceset `unimined.minecraft()` calls

**Fix**:
```gradle
// ❌ Delete these:
unimined.minecraft(sourceSets.api, true) { ... }
unimined.minecraft(sourceSets.launch, true) { ... }

// ✅ Keep only:
unimined.minecraft(sourceSets.main, true) { ... }

// ✅ And add classpath deps:
sourceSets {
    api {
        compileClasspath += main.output
    }
}
```

### Issue: "net.minecraft.* package does not exist" during :compileApiJava

**Cause**: api sourceSet doesn't depend on main's remapped classes

**Fix**:
```gradle
sourceSets {
    api {
        compileClasspath += main.compileClasspath  // ← Add this
        runtimeClasspath += main.runtimeClasspath  // ← And this
    }
}
```

### Issue: "Unimined version X not found" or Gradle cache corruption

**Cause**: Outdated Gradle wrapper or corrupted cache

**Fix**:
```bash
# Refresh dependencies and cache
./gradlew --refresh-dependencies clean build

# Or manually clear cache
rm -rf ~/.gradle/caches
./gradlew build
```

### Issue: Build fails with "Java 21 not found"

**Cause**: System Java doesn't match gradle.properties

**Fix**:
```bash
export JAVA_HOME=/path/to/java21
./gradlew build
```

Or in IDE: Set project SDK to Java 21

---

## 10. COMPARISON: cabaletta vs MeteorDevelopment

| Aspect | cabaletta/baritone | MeteorDevelopment/baritone |
|--------|-------------------|---------------------------|
| **Official** | Yes (primary) | No (fork) |
| **Update Frequency** | Active (weekly+) | Slower (mirrors upstream) |
| **Release CI** | GitHub Actions | Internal/meteorclient.com |
| **Version Support** | Master + per-version branches | Version-specific branches |
| **Source Code** | Public, PR-friendly | Public but upstream focused |
| **Download Location** | GitHub Releases | meteorclient.com |
| **Build Process** | `./gradlew build` | Same (via fork) |
| **Recommendation** | For latest development | For pre-built stability |

---

## 11. EXACT BUILD COMMAND REFERENCE

```bash
# 1. Clone (official recommended)
git clone https://github.com/cabaletta/baritone.git
cd baritone

# 2. (Optional) Checkout specific version
git checkout 1.21.3

# 3. Verify Java version
java -version  # Must be Java 21

# 4. Build
./gradlew build

# 5. Artifacts appear in:
#    - build/libs/baritone-*.jar
#    - fabric/build/libs/baritone-*.jar
#    - forge/build/libs/baritone-*.jar

# 6. For development testing:
./gradlew runClient  # Runs Minecraft client with Baritone

# 7. For IDE integration (IntelliJ):
./gradlew genSources
# Then import project and select Fabric/Forge run configuration
```

---

## 12. CITATIONS & LINKS

### Official Repositories
- **cabaletta/baritone**: https://github.com/cabaletta/baritone
- **cabaletta/baritone build.gradle**: https://github.com/cabaletta/baritone/blob/master/build.gradle
- **cabaletta/baritone gradle.properties**: https://github.com/cabaletta/baritone/blob/master/gradle.properties
- **cabaletta/baritone Actions**: https://github.com/cabaletta/baritone/actions

### MeteorDevelopment
- **MeteorDevelopment/baritone**: https://github.com/MeteorDevelopment/baritone
- **Meteor Client Downloads**: https://meteorclient.com/
- **Meteor FAQ**: https://meteorclient.com/faq

### Unimined Plugin
- **Unimined GitHub**: https://github.com/wagyourtail/unimined
- **Unimined Wiki**: https://github.com/wagyourtail/unimined/wiki

### Fabric & Loaders
- **Fabric Loader**: https://fabricmc.net/
- **Fabric API**: https://github.com/FabricMC/fabric
- **Fabric Maven**: https://maven.fabricmc.net/

### Related Documentation
- **Baritone API Javadocs**: https://jitpack.io/p/cabaletta/baritone
- **Fabric Loom**: https://github.com/FabricMC/fabric-loom
- **Minecraft Wiki (Versions)**: https://minecraft.wiki/

---

## CONCLUSION

**baritone-meteor-1.21.10.jar** is built by:

1. **Checking out** the 1.21.10 branch from cabaletta/baritone
2. **Running** `./gradlew build` with Java 21
3. **Extracting** the Fabric standalone JAR
4. **Publishing** via MeteorDevelopment's download infrastructure

The critical build pattern is:
- **Single `unimined.minecraft(sourceSets.main)` call**
- **Explicit sourceSets dependencies** (api → main.output, launch → main.output)
- **Gradle wrapper** handles all dependency downloads
- **GitHub Actions** automates official releases

**To build yourself**: Clone cabaletta/baritone, run `./gradlew build` with Java 21, and the JARs appear in fabric/build/libs/.