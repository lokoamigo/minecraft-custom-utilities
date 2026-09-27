#!/usr/bin/env bash

set -euo pipefail

EXPECTED_TAG="${1:-}"
JAR="target/MinecraftCustomUtilities.jar"

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

if [[ "$VERSION" == *-SNAPSHOT ]]; then
    echo "Release version must not be a SNAPSHOT: $VERSION" >&2
    exit 1
fi

if [[ -n "$EXPECTED_TAG" && "$EXPECTED_TAG" != "v$VERSION" ]]; then
    echo "Release tag $EXPECTED_TAG does not match POM version $VERSION." >&2
    exit 1
fi

check_value() {
    local label=$1
    local actual=$2
    local expected=$3

    if [[ "$actual" != "$expected" ]]; then
        echo "$label is '$actual'; expected '$expected'." >&2
        exit 1
    fi
}

check_value "Dockerfile.paper APP_VERSION" \
    "$(sed -n 's/^ARG APP_VERSION=//p' Dockerfile.paper)" "$VERSION"
check_value "docker-compose.yml APP_VERSION" \
    "$(sed -n 's/.*APP_VERSION:-\([^}]*\).*/\1/p' docker-compose.yml)" "$VERSION"
check_value ".env.example APP_VERSION" \
    "$(sed -n 's/^APP_VERSION=//p' .env.example)" "$VERSION"

mvn --batch-mode clean package

if [[ ! -f "$JAR" ]]; then
    echo "Expected release artifact not found: $JAR" >&2
    exit 1
fi

PLUGIN_NAME=$(unzip -p "$JAR" plugin.yml | sed -n 's/^name: //p')
PLUGIN_VERSION=$(unzip -p "$JAR" plugin.yml | sed -n "s/^version: ['\"]\([^'\"]*\)['\"]$/\1/p")
MANIFEST=$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r')
MANIFEST_TITLE=$(sed -n 's/^Implementation-Title: //p' <<< "$MANIFEST")
MANIFEST_VERSION=$(sed -n 's/^Implementation-Version: //p' <<< "$MANIFEST")

check_value "plugin.yml name" "$PLUGIN_NAME" "MinecraftCustomUtilities"
check_value "plugin.yml version" "$PLUGIN_VERSION" "$VERSION"
check_value "manifest title" "$MANIFEST_TITLE" "Minecraft Custom Utilities"
check_value "manifest version" "$MANIFEST_VERSION" "$VERSION"

echo "Release metadata verified for Minecraft Custom Utilities $VERSION."
