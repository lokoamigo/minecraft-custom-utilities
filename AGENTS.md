# Project Standards

## Releases and versioning

- Treat the version in `pom.xml` as the canonical project version.
- A release commit must use the exact release version in `pom.xml`; do not leave
  `-SNAPSHOT` or an older version there.
- Keep the default `APP_VERSION` values in `Dockerfile.paper` and
  `docker-compose.yml` identical to the version in `pom.xml`.
- The release tag must be `v` followed by that exact version. For example, a
  POM version of `1.3.2` must be released with tag `v1.3.2`.
- Before committing or tagging a release, run the Maven build without a
  `-Drevision` override. This ensures the checked-in POM is sufficient to build
  the correctly versioned artifact.
- Verify both `plugin.yml` and `META-INF/MANIFEST.MF` inside the built JAR report
  the same exact version as `pom.xml` and the release tag.
- Do not push a release tag if any of these versions differ.

