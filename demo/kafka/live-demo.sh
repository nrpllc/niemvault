#!/usr/bin/env bash
#
# The live path: a CAD system on Kafka, landing in a criminal history repository as it happens.
#
#   cad-simulator.py  ->  Kafka topic  ->  niem run (ingest cycle)  ->  FDLE CCH
#
# The file-drop demo (demo/cch-demo.sh) tells the story in four discrete steps. This one just runs:
# calls are dispatched, the ingest cycle drains the topic on a short loop, and the repository fills
# up while you watch it. Both use the same mapping and the same projection -- transport is the only
# difference, which is the point spec 4.3 makes about splitting transport from schema.
#
# Usage:
#   demo/kafka/live-demo.sh --cch /path/to/fdlecch [--rate 1.5] [--every 10] [--port 5199] [--direct]
#
# Runs on Flink by default, which is how the platform actually executes a mapping. --direct runs
# the same mapping in process, which starts quicker and is for iterating rather than for showing.
#
# Requires a JDK 21, the .NET SDK, Docker, and python3. Ctrl-C stops everything it started.

set -euo pipefail

NV="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$NV/demo/kafka"
CCH=""
WORK="${TMPDIR:-/tmp}/niem-live-demo"
PORT=5199
RATE=1.5          # calls per second
EVERY=10          # seconds between ingest cycles
TOKEN="demo-token"
BOOTSTRAP="localhost:${NIEM_KAFKA_PORT:-19092}"
KAFKA_CONTAINER="${NIEM_KAFKA_CONTAINER:-niem-demo-kafka}"
TOPIC="cad.leon.incidents"
# FLINK is how the platform actually runs (spec 5) and is the CLI's own default, so it is the
# default here too -- a demo that quietly ran something else would be showing the wrong thing.
# DIRECT skips cluster startup and is a few seconds quicker per cycle, for iterating.
ENGINE="FLINK"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --cch)   CCH="$2"; shift 2 ;;
        --work)  WORK="$2"; shift 2 ;;
        --port)  PORT="$2"; shift 2 ;;
        --rate)  RATE="$2"; shift 2 ;;
        --every) EVERY="$2"; shift 2 ;;
        --direct) ENGINE="DIRECT"; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$CCH" ]] || { echo "--cch is required: the path to a checkout of the FDLE CCH repository." >&2; exit 2; }

MODULE="$NV/modules/law-enforcement/src/main/resources"
MAPPING="$MODULE/mappings/leon-cad-to-canonical-1.0.0.yaml"
SOURCE="$WORK/leon-so-cad-kafka.yaml"
NIEM="$NV/tools/cli/build/install/niem/bin/niem"
BASE="http://127.0.0.1:$PORT"

note() { printf '  \033[90m%s\033[0m\n' "$1"; }
say()  { printf '\033[36m%s\033[0m\n' "$1"; }

PIDS=()
cleanup() {
    echo
    say "Stopping."
    for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
    # The broker is left running on purpose: it holds the topic, so a second run of this script
    # starts instantly and the backlog is still there to show. `docker compose down` in
    # demo/kafka removes it.
    note "Broker left running. Remove it with: (cd $HERE && docker compose down -v)"
}
trap cleanup EXIT INT TERM

rm -rf "$WORK"; mkdir -p "$WORK/bronze-leon" "$WORK/cch-data"

# The macOS /usr/bin/java is a stub that asks you to install Java. Homebrew's JDK 21 when nothing
# else is named, so the CLI does not fail on its first line on a Mac with only that.
if [[ -z "${JAVA_HOME:-}" ]] && command -v brew >/dev/null && [[ -d "$(brew --prefix openjdk@21 2>/dev/null)" ]]; then
    export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
fi

# The module names a deployment's broker and repository. Copies pointed at this demo's, as
# cch-demo.sh does: the endpoint and the bootstrap address are the only lines that differ (ADR 0034).
sed "s#^  bootstrapServers: .*#  bootstrapServers: $BOOTSTRAP#" \
    "$MODULE/sources/leon-so-cad-kafka.yaml" > "$SOURCE"
sed "s#^  endpoint: .*#  endpoint: $BASE/api/niemvault/submissions#" \
    "$MODULE/exchanges/fdle-cch-incidents-leon-1.0.0.yaml" > "$WORK/exchange-leon.yaml"

# --- the broker -------------------------------------------------------------

say "Starting the broker"
(cd "$HERE" && docker compose up -d) >/dev/null 2>&1

for _ in $(seq 1 40); do
    if docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; then break; fi
    sleep 1
done
docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists --topic "$TOPIC" --partitions 1 --replication-factor 1 >/dev/null 2>&1
note "Broker on $BOOTSTRAP, topic $TOPIC"

# --- the repository ---------------------------------------------------------

say "Starting the repository"
CCH_LOG="$WORK/cch.log"
(cd "$CCH" && dotnet build src/Fdle.Cch.Web/Fdle.Cch.Web.csproj -v q --nologo) > "$CCH_LOG" 2>&1
CCH_DLL="$(find "$CCH/src/Fdle.Cch.Web/bin" -name Fdle.Cch.Web.dll -path '*/net*' | head -1)"
[[ -n "$CCH_DLL" ]] || { echo "No built Fdle.Cch.Web.dll under $CCH. See $CCH_LOG." >&2; exit 1; }

(
    cd "$CCH/src/Fdle.Cch.Web"
    ASPNETCORE_URLS="$BASE" ASPNETCORE_ENVIRONMENT=Development \
    Cch__DataDirectory="$WORK/cch-data" Cch__Persons=300 Cch__Incidents=300 \
    Cch__NiemVault__Enabled=true Cch__NiemVault__Token="$TOKEN" \
    exec dotnet "$CCH_DLL"
) >> "$CCH_LOG" 2>&1 &
PIDS+=($!)

for _ in $(seq 1 90); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health" || true)" == "200" ]] && break
    sleep 1
done
note "Repository on $BASE"

# --- the CAD system ---------------------------------------------------------

say "Dispatching calls"
note "$RATE call(s) per second, into $TOPIC"
(
    python3 "$HERE/cad-simulator.py" --rate "$RATE" \
    | docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server "$BOOTSTRAP" --topic "$TOPIC" >/dev/null 2>&1
) &
PIDS+=($!)

# --- the ingest cycle -------------------------------------------------------

say "Ingesting every ${EVERY}s on $ENGINE"
note "This is the CronJob the chart ships: a job that drains what arrived and exits,"
note "not a service whose health is unrelated to whether any data moved."
echo

export NIEM_CCH_TOKEN="$TOKEN"
(
    while true; do
        started=$(date +%H:%M:%S)
        out=$("$NIEM" run --module "$MODULE" --mapping "$MAPPING" --source "$SOURCE" \
                --bronze "$WORK/bronze-leon" --tenant fl.leon.so \
                --engine "$ENGINE" --exchange "$WORK/exchange-leon.yaml" 2>&1 | grep -v '^{' || true)

        landed=$(sed -n 's/^Landed \([0-9]*\) record.*/\1/p' <<<"$out" | head -1)
        # Canonical output rather than cluster count: on Flink the driver cannot see the
        # resolver's counts at all, and the command says so rather than guessing. Records
        # mapped is reported on both engines.
        mapped=$(sed -n 's/^Mapped \([0-9]*\) canonical record.*/\1/p' <<<"$out" | head -1)
        held=$(curl -s -H "X-NiemVault-Token: $TOKEN" \
                "$BASE/api/niemvault/counts?sourceId=leon-so-cad" || echo '{}')

        printf '  \033[90m%s\033[0m  landed %-5s mapped %-5s  repository %s\n' \
            "$started" "${landed:-0}" "${mapped:-0}" "$held"

        sleep "$EVERY"
    done
) &
PIDS+=($!)

sleep 2
say "Open $BASE  —  the Incident Map fills up as calls come in."
note "Reports that arrived through the platform are ringed in blue."
note "Ctrl-C to stop."
wait
