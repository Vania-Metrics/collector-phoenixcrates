#!/usr/bin/env bash
# =============================================================================
# colecteur — un jar VaniaMetrics-<Nom>-<v>.jar dans dist/
#
# L'API vient du DÉPÔT GIT core, à la ref épinglée dans core.ref — jamais d'un
# dossier voisin. Le collecteur se construit donc seul, cloné n'importe où :
#   ./build.sh                      # core.ref : <url> <tag ou branche>
#   ./build.sh --core-ref main      # une autre ref, le temps d'un build
#   ./build.sh --api <jar>          # échappatoire : un jar d'API local
#   ./build.sh --verifier           # compile seulement
#
# Variables équivalentes : VANIA_METRICS_CORE_URL, VANIA_METRICS_CORE_REF,
# VANIA_METRICS_API_JAR.
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

API_JAR="${VANIA_METRICS_API_JAR:-}"
CORE_URL="${VANIA_METRICS_CORE_URL:-}"
CORE_REF="${VANIA_METRICS_CORE_REF:-}"
VERIFIER=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --api) API_JAR="${2:-}"; shift ;;
        --core-ref) CORE_REF="${2:-}"; shift ;;
        --verifier) VERIFIER=1 ;;
        *) erreur "argument inconnu : $1" ;;
    esac
    shift
done

command -v javac >/dev/null || erreur "javac introuvable"
mkdir -p "$DEPS"

# --- L'API, depuis le dépôt core ---------------------------------------------
etape "API VaniaMetrics"
if [[ -n "$API_JAR" ]]; then
    [[ -f "$API_JAR" ]] || erreur "jar d'API introuvable : $API_JAR"
    VERSION="$(basename "$API_JAR" | sed -n 's/^vania-metrics-api-\(.*\)\.jar$/\1/p')"
    [[ -n "$VERSION" ]] || erreur "nom attendu : vania-metrics-api-<version>.jar"
    info "jar local : $API_JAR (v$VERSION)"
else
    command -v git >/dev/null || erreur "git introuvable"
    while read -r url ref; do
        [[ -z "${url:-}" || "$url" == \#* ]] && continue
        CORE_URL="${CORE_URL:-$url}"
        CORE_REF="${CORE_REF:-${ref:-}}"
        break
    done < core.ref
    [[ -n "$CORE_URL" && -n "$CORE_REF" ]] || erreur "core.ref : une ligne « <url> <ref> » attendue"

    # Un clone superficiel à chaque build : c'est la seule façon de savoir où
    # pointe la ref AUJOURD'HUI, qu'elle soit tag ou branche. Le dépôt est petit.
    # L'API compilée, elle, est gardée par commit : même SHA, même jar.
    clone="$DEPS/core-clone"
    rm -rf "$clone"
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$CORE_REF" "$CORE_URL" "$clone" \
        || erreur "clone impossible : $CORE_URL @ $CORE_REF"
    sha="$(git -C "$clone" rev-parse HEAD)"
    VERSION="$(sed -n 's/.*VALEUR = "\([^"]*\)".*/\1/p' "$clone/api/src/fr/samflix/vaniametrics/api/Version.java")"
    [[ -n "$VERSION" ]] || erreur "version illisible dans le Version.java de core @ $CORE_REF"

    cache="$DEPS/api/$sha"
    API_JAR="$cache/vania-metrics-api-$VERSION.jar"
    if [[ ! -f "$API_JAR" ]]; then
        # Même règle que dans core : l'API se compile SEULE, sur le JDK.
        rm -rf "$cache" && mkdir -p "$cache/classes"
        javac --release "$CIBLE_JAVA" -proc:none -Xlint:all -Werror -encoding UTF-8 \
            -d "$cache/classes" $(find "$clone/api/src" -name '*.java')
        jar --create --file "$API_JAR" -C "$cache/classes" .
        rm -rf "$cache/classes"
    fi
    rm -rf "$clone"
    info "core @ $CORE_REF (${sha:0:12}) — API v$VERSION"
fi

# --- Dépendances de compilation ---------------------------------------------
etape "Dépendances"
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
info "$(ls "$DEPS"/*.jar 2>/dev/null | wc -l) jar(s) + l'API"

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
