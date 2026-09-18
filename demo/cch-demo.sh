#!/usr/bin/env bash
#
# The platform and a criminal history repository, end to end.
#
# A CAD export lands as a file drop, is mapped to canonical, and is projected into FDLE CCH --
# which then holds an offence-adjacent record it cannot produce itself: where and when something
# was reported, and who was named on the report, none of which an arrest carries.
#
# Two days, because the second is the point. The vendor changes its export format overnight and
# nothing errors on their side; the contracts quarantine the rows that drifted and the repository
# receives only what survived. A feed that quietly changes shape is the failure this platform
# exists to prevent, and this is what that looks like from the consumer's end.
#
# Usage:
#   demo/cch-demo.sh --cch /path/to/fdlecch [--work DIR] [--port 5199]
#
# Requires a JDK 21 and the .NET SDK the repository pins. Nothing else -- no Docker, no object
# store: `run` maps bronze to canonical and projects it, and silver is a separate concern.

set -euo pipefail

NV="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CCH=""
WORK="${TMPDIR:-/tmp}/niem-cch-demo"
PORT=5199
TOKEN="demo-token"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --cch)  CCH="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "$CCH" ]]; then
    echo "--cch is required: the path to a checkout of the FDLE CCH repository." >&2
    exit 2
fi

MODULE="$NV/modules/law-enforcement/src/main/resources"
MAPPING="$MODULE/mappings/leon-cad-to-canonical-1.0.0.yaml"
DRIFT_MAPPING="$MODULE/mappings/cad-to-canonical-1.0.0.yaml"
NIEM="$NV/tools/cli/build/install/niem/bin/niem"
BASE="http://127.0.0.1:$PORT"

step() { printf '\n\033[90m%s\033[0m\n \033[36m%s\033[0m\n\033[90m%s\033[0m\n\n' \
    "$(printf '=%.0s' {1..78})" "$1" "$(printf '=%.0s' {1..78})"; }
note() { printf '  \033[90m%s\033[0m\n' "$1"; }

# --- setup ------------------------------------------------------------------

rm -rf "$WORK"
mkdir -p "$WORK/day1" "$WORK/day2" "$WORK/bronze-leon" "$WORK/bronze-riverton" "$WORK/cch-data"
cp "$MODULE/fixtures/leon-incidents.csv"    "$WORK/day1/"
cp "$MODULE/fixtures/incidents-drifted.csv" "$WORK/day2/"

if [[ ! -x "$NIEM" ]]; then
    note "Building the operator CLI..."
    (cd "$NV" && ./gradlew :tools:cli:installDist --console=plain -q)
fi

step "0. Start the repository"
note "POC mode, its own synthetic corpus, and the projection endpoint switched on."
note "The endpoint is off by default: a door that writes incidents has no business"
note "existing on a deployment with no platform attached to it."

CCH_LOG="$WORK/cch.log"

# Built once and launched directly, rather than through `dotnet run`. The difference is not
# tidiness: `dotnet run` starts the application as a child of itself, so killing what this script
# backgrounded leaves the application holding the port, and the next run of the demo fails to
# bind. Launching the built binary makes the process this script started the process it can stop.
note "Building the repository..."
(cd "$CCH" && dotnet build src/Fdle.Cch.Web/Fdle.Cch.Web.csproj -v q --nologo) > "$CCH_LOG" 2>&1

CCH_DLL="$(find "$CCH/src/Fdle.Cch.Web/bin" -name Fdle.Cch.Web.dll -path '*/net*' | head -1)"
if [[ -z "$CCH_DLL" ]]; then
    echo "Could not find a built Fdle.Cch.Web.dll under $CCH. See $CCH_LOG." >&2
    exit 1
fi

(
    cd "$CCH/src/Fdle.Cch.Web"
    ASPNETCORE_URLS="$BASE" \
    ASPNETCORE_ENVIRONMENT=Development \
    Cch__DataDirectory="$WORK/cch-data" \
    Cch__Persons=300 Cch__Incidents=300 \
    Cch__NiemVault__Enabled=true \
    Cch__NiemVault__Token="$TOKEN" \
    exec dotnet "$CCH_DLL"
) >> "$CCH_LOG" 2>&1 &
CCH_PID=$!
# Stop the repository however this script ends, including on a failed step. A demo that leaves a
# web application holding a port is one nobody can run twice.
trap 'kill $CCH_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
    if [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health" || true)" == "200" ]]; then
        break
    fi
    sleep 1
done
note "Repository up on $BASE (log: $CCH_LOG)"

export NIEM_CCH_TOKEN="$TOKEN"

counts() {
    curl -s -H "X-NiemVault-Token: $TOKEN" "$BASE/api/niemvault/counts?sourceId=${1:-leon-so-cad}"
    echo
}

# --- 1 ----------------------------------------------------------------------

step "1. Day one: Leon County SO's CAD export arrives"
note "A real-shaped export: names packed as 'LAST, FIRST M', the same driver licence"
note "punctuated three different ways by whoever keyed it, UNK standing in for null."
note "Addresses come from the same 13.6 reserved street pool the repository's own"
note "corpus uses, so nothing here can collide with a real Florida address -- and the"
note "repository's gazetteer can still resolve them to a block."
echo
head -4 "$WORK/day1/leon-incidents.csv" | sed 's/^/    /'
echo "    ..."
echo

"$NIEM" run --module "$MODULE" --mapping "$MAPPING" \
    --drop "$WORK/day1" --bronze "$WORK/bronze-leon" --tenant fl.leon.so \
    --engine DIRECT --cch-url "$BASE"

echo
note "Every row became three canonical records -- a Person, an Incident and the"
note "association between them -- and the repository upserted them on the platform's"
note "own identities, so the people collapse to the cast that actually recurs:"
echo -n "    "; counts

# --- 2 ----------------------------------------------------------------------

step "2. The same drop again"
note "Bronze is append-only, so this lands every row again and maps them again."
note "A projection's apply is idempotent by contract: the repository upserts on"
note "canonical identity, so the counts do not move. Replay depends on this -- and an"
note "association view is built on co-occurrence, so a doubled corpus would not look"
note "broken. It would look like a finding."
echo

"$NIEM" run --module "$MODULE" --mapping "$MAPPING" \
    --drop "$WORK/day1" --bronze "$WORK/bronze-leon" --tenant fl.leon.so \
    --engine DIRECT --cch-url "$BASE" 2>&1 | tail -6

echo
echo -n "    "; counts

# --- 3 ----------------------------------------------------------------------

step "3. A second agency, whose vendor changed the format overnight"
note "Riverton PD ships the same export shape on its own mapping -- a different key"
note "prefix and a different time zone, which is the whole of what differs between"
note "onboarding one agency and the next."
note ""
note "It also gets its OWN bronze store, and that is not tidiness. A deployment"
note "serves exactly one agency (ADR 0026) and a store refuses anyone else's data"
note "on open, before anything is written. Two agencies is two deployments -- both"
note "projecting into the one state repository, which is what a state repository is"
note "for. Pointing the second run at the first store fails, by construction."
note ""
note "Their vendor pushed an update. Dates of birth are now ISO, one timestamp is"
note "ISO, and a column has been added. Nothing errors at the source. The contracts"
note "quarantine the rows that drifted."
echo
head -3 "$WORK/day2/incidents-drifted.csv" | sed 's/^/    /'
echo

"$NIEM" run --module "$MODULE" --mapping "$DRIFT_MAPPING" \
    --drop "$WORK/day2" --bronze "$WORK/bronze-riverton" --tenant co.riverton.pd \
    --engine DIRECT --cch-url "$BASE" 2>&1 | grep -v '^{' | tail -14

echo
note "Only what survived reached the repository. One incident landed with nobody"
note "named on it at all -- the incident row was clean and the person row on it was"
note "not -- which is a partial record the repository can show as partial, rather"
note "than a complete-looking one missing a victim."
echo
echo -n "    "; counts riverton-pd-cad

# --- 4 ----------------------------------------------------------------------

step "4. What the repository now holds"
note "Open $BASE and look at:"
note "  Incident Map        Leon County. Every report that came through the platform"
note "                      is ringed in blue; click one and the popup names the feed."
note "  Associations        who recurs across reports -- the reason this is a graph"
note "  the rail badge      'Powered by NIEMVAULT', and only because records arrived"
echo
note "Every person landed this way is marked as an UNCONFIRMED identity resolution."
note "That is not a defect in the demo. The platform computes a confidence and its"
note "evidence for every merge and persists neither -- spec 4.5 assigns them to"
note "lineage and core:lineage is not built yet -- so nothing the projection can read"
note "carries them. A repository shows an over-merge as a recurring association,"
note "which is the same shape as a real finding, so the claim is not made."
echo
read -r -p "  Press return to stop the repository. " _
