# colecteur-phoenixcrates

Collecteur VaniaMetrics pour phoenixcrates. Un module = un jar, chargé par la plateforme si — et seulement si — le noyau est présent.

## Construire

```sh
# Le noyau doit avoir été construit d'abord :
(cd ../core && ./build.sh)

./build.sh                          # produit dist/VaniaMetrics-<Nom>-<v>.jar
./build.sh --api /autre/chemin.jar  # API depuis un autre chemin
./build.sh --verifier               # compile seulement
```

La version est LUE dans le nom du jar de l'API (`vania-metrics-api-<v>.jar`).

## Dépendances

- `deps.txt` — API Maven (paper, velocity, adventure, jspecify, guava…). Empreintes SHA-256 vérifiées.
- `deps-plugins.txt` — jars des plugins tiers ciblés, à la **compilation seulement**.

Voir la conception d'ensemble dans `../README.md`.
