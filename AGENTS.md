# Minecraft Custom Utilities Project Standards

These instructions apply to the entire repository.

## Project identity

- The project and human-readable release name is **Minecraft Custom Utilities**.
- The repository and Maven artifact identifier is `minecraft-custom-utilities`.
- The distributable plugin JAR is `MinecraftCustomUtilities.jar`.
- Paper's plugin identifier is `MinecraftCustomUtilities` because plugin identifiers
  cannot contain spaces.
- This is a server-side Paper plugin containing several Minecraft utilities. It is
  not exclusively a minecart plugin; do not restore the former FastMinecarts name.

## Technology and layout

- Build with Maven and Java 25.
- Paper API is a provided dependency; do not package a Paper server into the plugin.
- Java sources are under `src/main/java` and filtered Paper resources are under
  `src/main/resources`.
- `plugin.yml` is the authoritative Paper entry-point declaration.
- `Dockerfile.paper` and `docker-compose.yml` provide the local integration server.
- Use `make package` for a normal build and `make verify-release` for the full
  version and packaged-metadata check.

## Compatibility

- The `local.fastminecarts` Java package, `FastMinecartsPlugin` main class, and
  `fastminecarts.admin` permission are legacy compatibility identifiers. Do not
  rename them merely for branding consistency; a migration must be deliberate and
  account for server configuration and permissions.
- Preserve existing command names and configuration keys unless a change explicitly
  requires a migration.

## Releases and versioning

- Treat the `revision` property in `pom.xml` as the canonical project version.
- A release commit must contain the exact release version; never release a
  `-SNAPSHOT` or reuse an existing version/tag.
- Keep the default `APP_VERSION` values in `.env.example`, `Dockerfile.paper`, and
  `docker-compose.yml` identical to the POM version.
- The release tag must be `v` followed by that exact version. For example, POM
  version `1.4.0` requires tag `v1.4.0`.
- Before committing or tagging a release, run `make verify-release`. It builds with
  the checked-in POM and without a `-Drevision` override.
- The resulting `target/MinecraftCustomUtilities.jar` must contain a `plugin.yml`
  version and `META-INF/MANIFEST.MF` implementation version identical to the POM
  version. Its manifest title must be `Minecraft Custom Utilities`.
- Do not push a release tag if the verification command fails or any version,
  artifact name, plugin name, or manifest title differs.
- Push the release commit to the default branch and push the matching version tag.
  The GitHub Actions workflow publishes both the rolling `latest` build and the
  versioned GitHub release.

## Change verification

- Run `make package` for source or resource changes.
- Run `make verify-release` for any change to versions, Maven metadata, plugin
  metadata, Docker build inputs, or release automation.
- When behavior changes, test it on the Docker Paper server when practical with
  `make restart-test-server`, then inspect `make logs` and `make plugins`.
