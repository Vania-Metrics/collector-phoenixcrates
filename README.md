# colecteur-phoenixcrates

Collecteur VaniaMetrics pour phoenixcrates. Un module = un jar, chargé par la plateforme si — et seulement si — le noyau est présent.

## Construire

```sh
./build.sh                    # produit dist/VaniaMetrics-<Nom>-<v>.jar
./build.sh --core-ref main    # API d'une autre ref de core, pour ce build seulement
./build.sh --api <jar>        # API depuis un jar local (développement de l'API)
./build.sh --verifier         # compile seulement
```

L'API vient du dépôt git [Vania-Metrics/core](https://github.com/Vania-Metrics/core), à la ref épinglée dans `core.ref`. Le build la clone, compile `api/` seule et la garde en cache dans `.deps/api/<sha>/`. La version du jar produit est celle du `Version.java` de cette ref.

Monter de version : changer la ref dans `core.ref` (tag de préférence, `vX.Y.Z`).

## Dépendances

- `core.ref` — dépôt et ref du noyau, d'où vient l'API.
- `deps.txt` — API Maven (paper, velocity, adventure, jspecify, guava…). Empreintes SHA-256 vérifiées.
- `deps-plugins.txt` — jars des plugins tiers ciblés, à la **compilation seulement**.
