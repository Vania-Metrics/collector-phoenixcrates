# colecteur-phoenixcrates

Collecteur VaniaMetrics pour phoenixcrates. Un module = un jar, chargé par la plateforme si — et seulement si — le noyau est présent.

## Construire

```sh
./gradlew build                                # build/libs/VaniaMetrics-<Nom>-<v>.jar
./gradlew build -PvaniaCore.ref=main           # API d'une autre ref de core
./gradlew build -PvaniaCore.dir=../core        # API d'un core local (développement de l'API)
./gradlew compileJava                          # compile seulement
```

L'API vient du dépôt git [Vania-Metrics/core](https://github.com/Vania-Metrics/core), à la ref de `gradle.properties` (`vaniaCore.ref`). Gradle le clone dans `.gradle/vania-core` et l'inclut comme build composite : `fr.samflix:vania-metrics-api` est compilé depuis ses sources à cette ref. La version du jar produit est celle de son `Version.java`.

Monter de version : changer `vaniaCore.ref` (un tag `vX.Y.Z`).

## Dépendances

- `gradle.properties` — dépôt et ref du noyau, d'où vient l'API.
- `gradle/libs.versions.toml` — paper-api, velocity-api, et les plugins ciblés (dépôt Maven de Modrinth), à la **compilation seulement**.
- `gradle/verification-metadata.xml` — empreintes SHA-256 de tout ce qui est résolu. Après une montée de version : `./gradlew --write-verification-metadata sha256 build`.
