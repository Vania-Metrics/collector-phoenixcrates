<img src="icon.png" alt="" width="96" align="right">

# collector-phoenixcrates

VaniaMetrics collector for PhoenixCrates. A module is one jar, loaded by the platform if and only if the core is present.

## Build

```sh
./gradlew build                                # build/libs/vania-metrics-collector-phoenixcrates-<v>.jar
./gradlew build -PvaniaCore.ref=main           # API from another core ref
./gradlew build -PvaniaCore.dir=../core        # API from a local core (API development)
./gradlew compileJava                          # compile only
```

The API comes from the git repo [Vania-Metrics/core](https://github.com/Vania-Metrics/core), at the ref from `gradle.properties` (`vaniaCore.ref`). Gradle clones it into `.gradle/vania-core` and includes it as a composite build: `fr.samflix:vania-metrics-api` is compiled from its sources at that ref.

The jar's version is this collector's own, in `version.txt`, kept by release-please. Commit messages start with a type (`feat:`, `fix:`, `chore:`…): every push to `main` updates a release pull request, and merging it publishes the GitHub release with the jar. Moving to a new core is a `feat: core API x.y.z` commit that changes `vaniaCore.ref` (a `vX.Y.Z` tag).

## Dependencies

- `gradle.properties` — the core's repo and ref, where the API comes from.
- `gradle/libs.versions.toml` — paper-api, velocity-api, and the targeted plugins (Modrinth Maven repo), **compile-only**.
- `gradle/verification-metadata.xml` — SHA-256 checksums of everything resolved. After a version bump: `./gradlew --write-verification-metadata sha256 build`.

## License

[GNU General Public License v3.0](LICENSE).
