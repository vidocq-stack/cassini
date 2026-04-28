#!/bin/bash
set -e

# ==============================================================================
# Script de lancement du TCK officiel Jakarta RESTful Web Services 4.0 — Cassini
# ==============================================================================
#
# Prérequis :
# 1. Avoir installé localement les artifacts du TCK officiel (non-publics) :
#    - jakarta.tck:jakarta-restful-ws-tck:4.0.1
#    Cf. cassini-tck/README.md
#
# Utilisation :
#   ./run-official-tck-restful-4.0.sh                          # smoke test
#   ./run-official-tck-restful-4.0.sh all                      # suite complète
#   ./run-official-tck-restful-4.0.sh -Dtest=ResourceTests     # classe ciblée
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Filtrage de l'argument "all"
MVN_ARGS=()
USE_ALL=false
for arg in "$@"; do
    if [[ "$arg" == "all" || "$arg" == "--all" ]]; then
        USE_ALL=true
    else
        MVN_ARGS+=("$arg")
    fi
done

echo "======================================="
echo " Étape 1 — Install reactor en M2 local "
echo "======================================="
mvn -q install -DskipTests

echo ""
echo "======================================="
echo " Étape 2 — Lancement du TCK REST 4.0   "
echo "======================================="

cd cassini-tck

if $USE_ALL; then
    mvn -Ptck-official verify "${MVN_ARGS[@]}"
else
    # Smoke : juste le test harness maison
    mvn -Ptck-official test -Dtest=CassiniHarnessSmokeTest "${MVN_ARGS[@]}"
fi
