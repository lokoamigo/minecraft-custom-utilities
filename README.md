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

## Phantom Cleanup

Operators can run `/killphantoms [radius]` to kill all phantoms near themselves.
The radius defaults to 64 blocks and can be set to any value up to 256 blocks.

## Lectern Book Editing

Sneak-right-click a lectern holding a writable book to edit it without taking it
off the lectern. The usual right-click action still reads the book. Only one player
can edit a lectern at a time; its book cannot be removed or its lectern broken while
an edit is open. Changes are saved when the player clicks Done or signs the book;
cancelling leaves the lectern unchanged. The `minecraftcustomutilities.lecternedit`
permission is granted to everyone by default.

## Player Shopkeepers

Rename any shovel to `shopkeeper`, then right-click a villager to turn it into
your shopkeeper and select it. Left-click chests with the same shovel to link or
unlink their stock. The shovel interaction cancels block damage.

Set per-item prices (in emeralds) for the selected shopkeeper with:

```text
/shopkeeper price <item> <emeralds>
/shopkeeper unprice <item>
/shopkeeper radius <blocks>
/shopkeeper speed <multiplier>
/shopkeeper health <points>
/shopkeeper name <name...>
/shopkeeper info
```

Other players can right-click the shopkeeper to open its store. Each purchase
removes one matching item from a currently loaded linked chest, charges the
configured number of emeralds, and deposits those emeralds into linked-chest
storage. Shop ownership, links, and prices persist across server restarts in
`plugins/MinecraftCustomUtilities/shopkeepers.yml`.

Linked chests cannot be broken by other players or explosions while their
shopkeeper is alive. Shopkeepers have 40 health points by default, plus armor
and toughness, and deal two hearts of thorns damage to direct or ranged
attackers. `/shopkeeper health <points>` sets the selected shopkeeper's maximum
health between 1 and 1024 points and immediately refills it. The configured
maximum persists across restarts. Killing a shopkeeper releases its linked
chests so they can be robbed.

Shopkeepers are marked persistent and configured not to despawn when far from a
player. These settings are reapplied whenever a saved shopkeeper is loaded.

A shopkeeper keeps its normal AI but is confined to a 5×5-block square centered
on the position where it was promoted. Near the edge, it pathfinds naturally
back toward the stored center. Teleporting is only used if it gets far away or
cannot return after several seconds. Owners can configure the square's radius
and overall movement-speed multiplier per selected shopkeeper. A radius of `1`
locks the shopkeeper to its center while leaving its AI active so it can still
look around. The center and settings persist across restarts. Owners can also
give each selected shopkeeper a persistent name, which is shown above the
villager and as the storefront title.

When any configured listing runs out of sellable stock, its owner is notified
online. Every unavailable listing in owned shops is also reported when the owner
joins the server.

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
make restart-test-server
make verify-test-server
docker compose logs -f paper
docker compose exec paper rcon-cli plugins
docker compose down
```

`make restart-test-server` rebuilds and recreates the Paper container, then
waits up to 120 seconds for it to become healthy. It also verifies through RCON
that `MinecraftCustomUtilities` loaded, fails if the plugin reported a startup
warning or error, and prints the final container status. Run
`make verify-test-server` to perform those checks without rebuilding or
restarting the server.

Plugin tuning from the server console or RCON:

```sh
minecartspeed <speed>
minecartspeed acceleration <value>
minecartspeed curvespeed <value>
minecartspeed reload
```

Players can search the shulker boxes in their local inventory for one or more
items. Each request is an item and quantity pair:

```text
/shulkerfind diamond 64 firework_rocket 32
```

The quantity can be omitted for non-stackable items and defaults to one. For
example, `/shulkerfind diamond_sword bow` requests one of each. An explicit
quantity is still accepted when requesting multiple non-stackable items.

The command lists matching shulker boxes by inventory slot and reports whether
the combined contents satisfy each requested quantity. If every requested item
is available and the player's inventory has enough room, the player can repeat
the same command within 30 seconds (normally Up Arrow followed by Enter) to move
the requested quantities out of the shulker boxes. The transfer is revalidated
before anything is changed. `/shulkerfind confirm` remains available as an
alternative.

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
