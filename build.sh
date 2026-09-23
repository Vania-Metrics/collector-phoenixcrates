#!/usr/bin/env bash
# =============================================================================
# colecteur — un jar VaniaMetrics-<Nom>-<v>.jar dans dist/
#
# L'API vient du dépôt voisin core/ (ou d'un chemin donné) :
#   ./build.sh                                     # ../core/dist/ par défaut
#   ./build.sh --api /chemin/vers/vania-metrics-api-0.2.0.jar
#   VANIA_METRICS_API_JAR=... ./build.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

DEPS=.deps
BUILD=.build
DIST=dist
CIBLE_JAVA=21

etape() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
info()  { printf '    %s\n' "$1"; }
erreur() { printf '\n\033[1;31m!! %s\033[0m\n' "$1" >&2; exit 1; }

API_ARG=""
VERIFIER=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --api) API_ARG="${2:-}"; shift ;;
        --verifier) VERIFIER=1 ;;
        *) erreur "argument inconnu : $1" ;;
    esac
    shift
done

API_JAR="${API_ARG:-${VANIA_METRICS_API_JAR:-}}"
if [[ -z "$API_JAR" ]]; then
    API_JAR="$(ls ../core/dist/vania-metrics-api-*.jar 2>/dev/null | head -1 || true)"
fi
[[ -n "$API_JAR" && -f "$API_JAR" ]] || erreur "API introuvable — construisez ../core d'abord (./build.sh dans core/), ou passez --api <chemin>"
VERSION="$(basename "$API_JAR" | sed 's/vania-metrics-api-\(.*\)\.jar/\1/')"
info "API : $API_JAR (v$VERSION)"

command -v javac >/dev/null || erreur "javac introuvable"

etape "Dépendances"
mkdir -p "$DEPS"
while read -r somme url; do
    [[ -z "${somme:-}" || "$somme" == \#* ]] && continue
    f="$DEPS/${url##*/}"
    if [[ -s "$f" ]] && [[ "$(sha256sum "$f" | cut -d' ' -f1)" == "$somme" ]]; then continue; fi
    info "téléchargement ${url##*/}"
    curl -sSfL -o "$f" "$url" || erreur "téléchargement impossible : $url"
    reelle="$(sha256sum "$f" | cut -d' ' -f1)"
    [[ "$reelle" == "$somme" ]] || erreur "empreinte fausse pour ${url##*/}
    attendue $somme
    obtenue  $reelle"
done < deps.txt

while read -r url; do
    [[ -z "${url:-}" || "$url" == \#* ]] && continue
    f="$DEPS/${url##*/}"
    [[ -s "$f" ]] && continue
    info "téléchargement ${url##*/}"
    curl -sSfL -o "$f" "$url" || erreur "téléchargement impossible : $url"
done < deps-plugins.txt

CP="$(printf '%s:' "$DEPS"/*.jar 2>/dev/null || true)$API_JAR"
info "$(ls "$DEPS"/*.jar 2>/dev/null | wc -l) jar(s) locaux + l'API"

etape "Compilation"
rm -rf "$BUILD" && mkdir -p "$BUILD"
# -proc:full — indispensable pour le module Velocity : @Plugin y génère le
# velocity-plugin.json. Inoffensif pour un module Paper-seul.
javac --release "$CIBLE_JAVA" -proc:full -Xlint:all,-path,-processing,-options -Werror -encoding UTF-8 \
    -cp "$CP" -d "$BUILD" $(find src -name '*.java')

if [[ "$VERIFIER" == 1 ]]; then
    etape "Vérification seule"
    exit 0
fi

etape "Empaquetage"
affiche="$(sed -n 's/^name: VaniaMetrics-//p' resources/plugin.yml)"
[[ -n "$affiche" ]] || erreur "resources/plugin.yml : « name: » attendu sous la forme VaniaMetrics-<Nom>"
sed "s/\${version}/$VERSION/g" resources/plugin.yml > "$BUILD/plugin.yml"

rm -rf "$DIST" && mkdir -p "$DIST"
jar --create --file "$DIST/VaniaMetrics-$affiche-$VERSION.jar" -C "$BUILD" .
info "VaniaMetrics-$affiche-$VERSION.jar"

rm -rf "$BUILD"
