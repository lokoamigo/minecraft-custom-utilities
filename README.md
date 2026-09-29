# Minecraft Custom Utilities

Paper plugin that raises minecart speed and caps high-speed carts before rail curves.

Minecraft Custom Utilities only modifies minecarts while a player is riding them. Empty carts and
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

## Elytra Slot

Players can wear chest armor and use an Elytra at the same time:

1. Run `/elytraslot` and place an Elytra in the center slot.
2. Equip chest armor normally.
3. Jump, then press jump again while airborne to start gliding. Crouching while
   falling also works as a fallback.

The Elytra is stored persistently on the player, takes normal durability damage
while gliding, and can consume collected experience through Mending. It drops with
the player's other inventory on death, unless
`keepInventory` is enabled. Because this is a server-only plugin, the additional
slot uses a small command-opened inventory instead of changing the client inventory
screen. While gliding with either a chest-slot or additional Elytra, a color-coded
durability meter appears in the action bar above the hotbar.

## Docker Paper Server

Build and start a local Paper server with this plugin and WorldEdit:

```sh
docker compose up --build
```

The compose setup:

- builds `MinecraftCustomUtilities.jar` from the current source using Java 25
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

The canonical version is defined by the `revision` property in `pom.xml`.
Maven injects it into `plugin.yml` and the JAR manifest so the build and the
version reported by Paper cannot drift apart. The Docker build defaults must
use the same version.

Build the current version:

```sh
make package
make version
```

To prepare a release, run the **Prepare Minecraft Custom Utilities Release**
workflow from the Actions tab. Leave the version input blank to increment the
current patch version automatically, or enter an explicit newer
`MAJOR.MINOR.PATCH` version without a `v` prefix for a minor or major release. The
workflow updates and commits all checked-in version values, verifies the packaged
metadata, atomically pushes the release commit and matching `vMAJOR.MINOR.PATCH`
tag, attaches `MinecraftCustomUtilities.jar` to a draft GitHub release, and
updates the rolling `latest` release. Review the completed workflow and attached
JAR, then publish the draft release.

Release versions must be newer than the checked-in version and tags must not be
reused. Do not manually create the release commit, tag, or draft. The workflow
needs permission to push to the default branch; its release commit must be allowed
by branch protection rules.
