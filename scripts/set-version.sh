#!/usr/bin/env bash

set -euo pipefail

VERSION="${1:-}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Version must use the form 1.2.3; got '$VERSION'." >&2
    exit 1
fi

sed -i -E "s#<revision>[^<]+</revision>#<revision>$VERSION</revision>#" pom.xml
sed -i -E "s/^ARG APP_VERSION=.*/ARG APP_VERSION=$VERSION/" Dockerfile.paper
sed -i -E "s/(APP_VERSION:-)[^}]*/\1$VERSION/" docker-compose.yml
sed -i -E "s/^APP_VERSION=.*/APP_VERSION=$VERSION/" .env.example

echo "Set Minecraft Custom Utilities version to $VERSION."
