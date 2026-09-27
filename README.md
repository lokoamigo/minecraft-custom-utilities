# FastMinecarts

Paper plugin that raises minecart speed and caps high-speed carts before rail curves.

FastMinecarts only modifies minecarts while a player is riding them. Empty carts and
non-player passenger carts are restored to vanilla minecart settings and skipped by
the movement logic.

Detector rails and activator rails are treated as vanilla-speed rail sections. When
one is under or ahead of a player-ridden cart, the plugin caps velocity to vanilla
minecart speed and then leaves rail physics alone.

Ascending and descending rails always use vanilla minecart speed and physics,
regardless of the configured straight-track speed or acceleration.

Rail look-ahead checks are cached by rail block and travel direction. The cache is
bounded, expires automatically, and is invalidated near rails that are placed,
broken, or updated by physics.

Happy Ghasts accelerate gradually while a player is riding them. Unridden Happy
Ghasts and hostile Ghasts retain vanilla movement. The ridden maximum speed and
acceleration can be changed with `/ghastspeed <speed>` and
`/ghastspeed acceleration <value>`, or with the corresponding settings in
`config.yml`. The default maximum is 200 blocks per second with an acceleration of
1 block per second squared. A mount
that stops, collides, or loses its rider builds speed from the beginning again.

## Docker Paper Server

Build and start a local Paper server with this plugin and WorldEdit:

```sh
docker compose up --build
```

The compose setup:

- builds `FastMinecarts.jar` from the current source using Java 25
- starts the experimental Paper `26.3` line by default
- installs WorldEdit from Modrinth at container startup
- stores the Minecraft world and server files in the `paper-data` Docker volume
- exposes Minecraft on `localhost:25565`

Optional local overrides:

```sh
cp .env.example .env
```

Then edit `.env` and restart with:

```sh
docker compose up --build
```

Useful commands:

```sh
docker compose logs -f paper
docker compose exec paper rcon-cli plugins
docker compose down
```

Plugin tuning from the server console or RCON:

```sh
minecartspeed <speed>
minecartspeed acceleration <value>
minecartspeed curvespeed <value>
minecartspeed reload
```

## Versioning

The default development version is defined by the `revision` property in
`pom.xml`. Maven injects it into `plugin.yml` and the JAR manifest so the build
and the version reported by Paper cannot drift apart.

Build the current development version:

```sh
make package
make version
```

Build a specific release version locally:

```sh
mvn -Drevision=1.2.3 clean package
APP_VERSION=1.2.3 docker compose up --build -d
```

Pushing a tag such as `v1.2.3` creates an immutable GitHub release and embeds
`1.2.3` in its plugin JAR. Branch builds retain the configured snapshot version.
