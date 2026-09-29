#!/usr/bin/env bash
#
# One CAD drop, three shapes of gold.
#
# Leon County SO's export lands as a file drop, is mapped to canonical, and is projected into the
# operational data store (PostgreSQL), the search index (Elasticsearch) and the graph (Neo4j) in the
# same run. Then the same question is asked of each -- who is STONECARROW, and where else do they
# appear -- because each store answers a different half of it.
#
# Usage:
#   demo/stores/demo.sh [--work DIR] [--reset]
#
#   --reset   drop the stores' volumes first. They persist between runs otherwise, and each store is
#             claimed for the tenant that first wrote to it (ADR 0026).
#
# Requires Docker and a JDK 21. The stores come from demo/stores/docker-compose.yml.

set -euo pipefail

NV="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$NV/demo/stores"
WORK="${TMPDIR:-/tmp}/niem-stores-demo"
RESET=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --work)  WORK="$2"; shift 2 ;;
        --reset) RESET=true; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

MODULE="$NV/modules/law-enforcement/src/main/resources"
MAPPING="$MODULE/mappings/leon-cad-to-canonical-1.0.0.yaml"
NIEM="$NV/tools/cli/build/install/niem/bin/niem"
TENANT="fl.leon.so"
COMPOSE=(docker compose -f "$HERE/docker-compose.yml")

# The demo stores' passwords, fixed in docker-compose.yml. The projection files name these
# variables rather than holding the values (ADR 0015).
export NIEM_ODS_PASSWORD=niem-demo
export NIEM_NEO4J_PASSWORD=niem-demo-graph

step() { printf '\n\033[90m%s\033[0m\n \033[36m%s\033[0m\n\033[90m%s\033[0m\n\n' \
    "$(printf '=%.0s' {1..78})" "$1" "$(printf '=%.0s' {1..78})"; }
note() { printf '  \033[90m%s\033[0m\n' "$1"; }

sql()    { docker exec niem-ods psql -U niem -d niem -P pager=off -c "$1"; }
cypher() { docker exec niem-graph cypher-shell -u neo4j -p "$NIEM_NEO4J_PASSWORD" --format plain "$1"; }
search() { curl -fsS -H 'Content-Type: application/json' "http://localhost:19200/$1" -d "$2"; }

run_once() {
    "$NIEM" run --module "$MODULE" --mapping "$MAPPING" --run-id "$1" \
        --drop "$WORK/drop" --bronze "$WORK/bronze" --tenant "$TENANT" --engine DIRECT \
        --projection "$HERE/projections/ods.yaml" \
        --projection "$HERE/projections/search.yaml" \
        --projection "$HERE/projections/graph.yaml"
}

# --- setup ------------------------------------------------------------------

step "0. Start the stores"
if $RESET; then
    note "--reset: dropping the stores' volumes, and with them each store's tenant claim."
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
fi
"${COMPOSE[@]}" up -d --wait
note "PostgreSQL :15432   Elasticsearch :19200   Neo4j :17474 (browser) / :17687 (bolt)"

if [[ -z "${JAVA_HOME:-}" ]] && command -v brew >/dev/null && [[ -d "$(brew --prefix openjdk@21 2>/dev/null)" ]]; then
    export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
fi
note "Building the operator CLI..."
(cd "$NV" && ./gradlew :tools:cli:installDist --console=plain -q)

rm -rf "$WORK"
mkdir -p "$WORK/drop" "$WORK/bronze"
cp "$MODULE/fixtures/leon-incidents.csv" "$WORK/drop/"

# --- 1 ----------------------------------------------------------------------

step "1. Land the drop, and project it three ways"
note "Every projection is opened, connected and claimed for $TENANT before a record"
note "lands. One that cannot be opened stops the run before it starts (ADR 0035)."
echo
run_once leon-day1

# --- 2 ----------------------------------------------------------------------

step "2. The ODS: canonical current state, in SQL"
note "One table per canonical type, keys and foreign keys from the model, NIEM"
note "provenance on every column. Operational work goes in schemas of its own beside it."
echo
sql "SELECT (SELECT count(*) FROM canonical.person)   AS people,
            (SELECT count(*) FROM canonical.incident) AS incidents,
            (SELECT count(*) FROM canonical.person_incident_association) AS associations;"
note "Who recurs across reports -- a join, which is what a relational store is for:"
sql "SELECT p.sur_name, p.given_name, count(*) AS reports,
            string_agg(DISTINCT a.involvement_code, ', ') AS roles
       FROM canonical.person p
       JOIN canonical.person_incident_association a ON a.person_id = p.canonical_id
      GROUP BY p.canonical_id, p.sur_name, p.given_name
     HAVING count(*) > 1
      ORDER BY reports DESC, p.sur_name;"
note "What a column means, from the database itself:"
sql "SELECT column_name, left(col_description('canonical.incident'::regclass, ordinal_position), 100) || '...' AS niem
       FROM information_schema.columns
      WHERE table_schema = 'canonical' AND table_name = 'incident'
        AND column_name IN ('incident_number', 'call_type_code', 'beat');"

# --- 3 ----------------------------------------------------------------------

step "3. Search: the half-remembered name"
note "Full text over every string field, and each person carries its links to"
note "the incidents it is on, so a hit says how involved someone is without a join."
echo
search "niem-$TENANT-person/_search" \
    '{"query":{"match":{"searchText":{"query":"stonecarow","fuzziness":"AUTO"}}},
      "_source":["givenName","surName","birthDate"],
      "script_fields":{"links":{"script":"params._source.links == null ? 0 : params._source.links.size()"}}}' \
    | python3 -c 'import sys,json
r=json.load(sys.stdin)
print("    %d hit(s) for the misspelling \"stonecarow\"" % r["hits"]["total"]["value"])
for h in r["hits"]["hits"]:
    s=h["_source"]; print("    %-12s %-10s born %s  on %d incident(s)" % (s.get("surName"), s.get("givenName"), s.get("birthDate"), h["fields"]["links"][0]))'

# --- 4 ----------------------------------------------------------------------

step "4. The graph: who else was there"
note "The question only a graph answers in one hop: everyone who shares an incident"
note "with STONECARROW, and on how many."
echo
cypher "MATCH (p:Person {surName: 'STONECARROW'})-[:PERSON_INCIDENT_ASSOCIATION]->(i:Incident)
              <-[:PERSON_INCIDENT_ASSOCIATION]-(other:Person)
        RETURN other.surName AS surname, other.givenName AS given, count(i) AS shared
        ORDER BY shared DESC, surname;"

# --- 5 ----------------------------------------------------------------------

step "5. The same drop again"
note "Every projection is idempotent by contract (§4.6), so landing twice must leave"
note "the same records in all three stores -- a doubled corpus would not look broken,"
note "it would look like everyone recurs."
echo
run_once leon-day1-again 2>&1 | grep -E 'Projected|record\(s\)$' || true
echo
note "Every store, every type -- they have to agree, or one of them is wrong (§4.7):"
es_count()  { curl -fsS "http://localhost:19200/niem-$TENANT-$1/_count" | python3 -c 'import sys,json;print(json.load(sys.stdin)["count"])'; }
pg_count()  { docker exec niem-ods psql -U niem -d niem -tAc "SELECT count(*) FROM canonical.$1"; }
neo_count() { cypher "$1" | tail -1; }
printf '    %-28s %6s %8s %7s\n' "" "ODS" "search" "graph"
printf '    %-28s %6s %8s %7s\n' Person \
    "$(pg_count person)" "$(es_count person)" "$(neo_count 'MATCH (n:Person) RETURN count(n);')"
printf '    %-28s %6s %8s %7s\n' Incident \
    "$(pg_count incident)" "$(es_count incident)" "$(neo_count 'MATCH (n:Incident) RETURN count(n);')"
printf '    %-28s %6s %8s %7s\n' PersonIncidentAssociation \
    "$(pg_count person_incident_association)" "$(es_count person-incident-association)" \
    "$(neo_count 'MATCH ()-[r:PERSON_INCIDENT_ASSOCIATION]->() RETURN count(r);')"
echo
note "The ODS ledger records both runs; the stores hold one copy of each record:"
sql "SELECT run_id, operation, canonical_type, records, applied_at::time(0)
       FROM niem_meta.projection_run ORDER BY applied_at, canonical_type;"

step "Done"
note "psql:     docker exec -it niem-ods psql -U niem -d niem"
note "search:   curl 'http://localhost:19200/_cat/aliases/niem-*?v'"
note "graph:    http://localhost:17474  (neo4j / $NIEM_NEO4J_PASSWORD)"
