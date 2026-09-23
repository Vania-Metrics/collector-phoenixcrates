# collector-phoenixcrates

VaniaMetrics collector for PhoenixCrates. A module is one jar, loaded by the platform if and only if the core is present.

## Build

```sh
./gradlew build                                # build/libs/vania-metrics-collector-phoenixcrates-<v>.jar
./gradlew build -PvaniaCore.ref=main           # API from another core ref
./gradlew build -PvaniaCore.dir=../core        # API from a local core (API development)
./gradlew compileJava                          # compile only
```

The API comes from the git repo [Vania-Metrics/core](https://github.com/Vania-Metrics/core), at the ref from `gradle.properties` (`vaniaCore.ref`). Gradle clones it into `.gradle/vania-core` and includes it as a composite build: `fr.samflix:vania-metrics-api` is compiled from its sources at that ref. The produced jar's version is that of its `Version.java`.

To bump the version: change `vaniaCore.ref` (a `vX.Y.Z` tag).

## Dependencies

- `gradle.properties` — the core's repo and ref, where the API comes from.
- `gradle/libs.versions.toml` — paper-api, velocity-api, and the targeted plugins (Modrinth Maven repo), **compile-only**.
- `gradle/verification-metadata.xml` — SHA-256 checksums of everything resolved. After a version bump: `./gradlew --write-verification-metadata sha256 build`.
