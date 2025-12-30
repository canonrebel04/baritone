#!/bin/bash
set -e

# Define target directory
TARGET_DIR="/home/cachy/.local/share/PrismLauncher/instances/Baritone Dev/minecraft/mods/"

echo "Building Baritone..."
./gradlew clean :fabric:build

echo "Cleaning old jars from $TARGET_DIR..."
# Use find to safely delete files even if the path contains spaces
find "$TARGET_DIR" -maxdepth 1 -name 'baritone-fabric-*.jar' -delete

echo "Deploying new jar..."
# Find the correct jar (excluding -dev and -shadow) and copy it
find fabric/build/libs -maxdepth 1 -name 'baritone-fabric-*-SNAPSHOT.jar' ! -name '*-dev.jar' ! -name '*-shadow.jar' -print0 | xargs -0 -I {} cp -v {} "$TARGET_DIR"

echo "Deployment complete."
