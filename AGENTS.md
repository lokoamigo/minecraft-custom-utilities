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

- The `local.fastminecarts` Java package and `fastminecarts.admin` permission are
  legacy compatibility identifiers. Do not rename them merely for branding
  consistency; a migration must be deliberate and account for server configuration
  and permissions.
- Preserve existing command names and configuration keys unless a change explicitly
  requires a migration.
- Keep the `/mcu help` output complete and synchronized with every user-facing
  command, argument form, alias, and permission requirement. Any command change
  must update the help output in the same change.

## Releases and versioning

- Treat the `revision` property in `pom.xml` as the canonical project version.
- A release commit must contain the exact release version; never release a
  `-SNAPSHOT` or reuse an existing version/tag.
- Keep the default `APP_VERSION` values in `.env.example`, `Dockerfile.paper`, and
  `docker-compose.yml` identical to the POM version.
- The release tag must be `v` followed by that exact version. For example, POM
  version `1.4.0` requires tag `v1.4.0`.
- Prepare releases only through the manually triggered **Prepare Minecraft Custom
  Utilities Release** GitHub Actions workflow. Leave its version input empty to
  increment the current patch version automatically, or supply an explicit newer
  version without a `v` prefix for a minor or major release. Do not manually create
  the release commit, tag, or draft release.
- The release workflow must reject malformed, non-incrementing, or previously used
  versions and tags. It updates all checked-in version fields, runs the equivalent
  of `make verify-release`, atomically pushes the release commit and matching tag,
  attaches the JAR to a draft GitHub release, and updates the rolling `latest`
  release.
- Before publishing the draft, confirm that the workflow succeeded and that the
  draft contains `MinecraftCustomUtilities.jar`.
- The resulting `target/MinecraftCustomUtilities.jar` must contain a `plugin.yml`
  version and `META-INF/MANIFEST.MF` implementation version identical to the POM
  version. Its manifest title must be `Minecraft Custom Utilities`.
- The workflow must not push a release commit or tag if verification fails or any
  version, artifact name, plugin name, or manifest title differs.
- The workflow requires permission to push its release commit and tag to the
  default branch. Treat branch protection that blocks `github-actions[bot]` as a
  release blocker; do not bypass verification or create the release manually.

## Change verification

- Run `make package` for source or resource changes.
- Run `make verify-release` for any change to versions, Maven metadata, plugin
  metadata, Docker build inputs, or release automation.
- When behavior changes, test it on the Docker Paper server when practical with
  `make restart-test-server`, then inspect `make logs` and `make plugins`.
