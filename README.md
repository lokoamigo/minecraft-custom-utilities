# FastMinecarts

Paper plugin that raises minecart speed and caps high-speed carts before rail curves.

## Docker Paper Server

Build and start a local Paper server with this plugin and WorldEdit:

```sh
docker compose up --build
```

The compose setup:

- builds `FastMinecarts.jar` from the current source using Java 25
- starts Paper `26.2` by default
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
minecartspeed inclinespeed <value>
minecartspeed reload
```
