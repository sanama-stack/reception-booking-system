#!/usr/bin/env bash
#
# Builds the performance database from nothing: create, MIGRATE, load, analyze.
#
# `make migrate` against a database named here, not `pg_dump --schema-only` out of the dev one.
# The distinction is the whole reason this script exists. The scratch database three sessions
# measured against was a schema clone taken before V8 and V9, so it was missing the three CHECK
# constraints the bounded queries depend on — and its flyway_schema_history existed and was empty,
# which is worse than absent, because Flyway would have treated a fully populated database as
# unmigrated and tried to run V1 against it. A fixture that cannot enforce the invariant the fast
# query assumes will happily return a fast, wrong answer.
#
#   backend/tools/perf-dataset/generate.sh            # build it
#   backend/tools/perf-dataset/generate.sh --drop     # remove it
#
# See README.md for the shape, and for how to take a number off it without measuring your own
# DELETE (T29) or a plan the application never gets (T30).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PERF_DB="${PERF_DB:-reception_perf}"

cd "$ROOT"

if [[ ! -f .env ]]; then
    echo "No .env. Run 'make up' first." >&2
    exit 1
fi

# The same gesture `make migrate` makes: the Flyway task runs on the host and reads DB_* from the
# process environment rather than from compose.
set -a
# shellcheck disable=SC1091
. ./.env
set +a

: "${DB_USER:?DB_USER is not set in .env}"
: "${POSTGRES_DB:=reception}"

psql_admin() {
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$POSTGRES_DB" "$@"
}

psql_perf() {
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$PERF_DB" "$@"
}

if [[ "${1:-}" == "--drop" ]]; then
    echo "Dropping ${PERF_DB}."
    psql_admin -c "DROP DATABASE IF EXISTS ${PERF_DB} WITH (FORCE);"
    echo "Gone. Nothing else was touched."
    exit 0
fi

if [[ "$PERF_DB" != *perf* ]]; then
    echo "PERF_DB must contain 'perf'; refusing to build '${PERF_DB}'." >&2
    exit 1
fi

docker compose up -d postgres >/dev/null

echo "1/4  Recreating ${PERF_DB}."
psql_admin -c "DROP DATABASE IF EXISTS ${PERF_DB} WITH (FORCE);"
psql_admin -c "CREATE DATABASE ${PERF_DB};"

echo "2/4  Migrating it with this repository's own Flyway migrations."
( cd backend && DB_NAME="$PERF_DB" ./gradlew flywayMigrate --console=plain -q )

# The point of migrating rather than cloning, asserted rather than assumed. If a future migration
# renames one of these, this fails here instead of in a measurement nobody re-reads.
echo "3/4  Checking the ceilings the bounded queries depend on are present."
missing=$(psql_perf -Atc "
    select string_agg(want, ', ')
      from (values ('appointments_max_length'),
                   ('appointments_buffer_before_max'),
                   ('appointments_buffer_after_max')) as w(want)
     where not exists (
        select 1 from pg_constraint
         where conrelid = 'appointments'::regclass and conname = w.want)")
if [[ -n "$missing" ]]; then
    echo "The migrated schema is missing: ${missing}" >&2
    exit 1
fi

echo "4/4  Loading the dataset, then VACUUM (ANALYZE)."
psql_perf < "$ROOT/backend/tools/perf-dataset/dataset.sql"
# Without this, every number taken afterwards is a measurement of the loader's own dead tuples and
# of statistics PostgreSQL has not collected yet (T29).
psql_perf -c "VACUUM (ANALYZE);"

echo
psql_perf -c "
    select b.name                                                as tenant,
           count(*)                                              as appointments,
           count(*) filter (where a.status = 'COMPLETED')        as completed,
           count(*) filter (where a.status = 'CONFIRMED')        as confirmed,
           count(*) filter (where a.status = 'CANCELLED')        as cancelled,
           count(*) filter (where a.status = 'NO_SHOW')          as no_show,
           min(a.starts_at)::date                                as first,
           max(a.starts_at)::date                                as last
      from appointments a join businesses b on b.id = a.business_id
     group by rollup (b.name)
     order by b.name nulls last;"

echo "${PERF_DB} is ready. Connect with:"
echo "  docker compose exec -T postgres psql -U ${DB_USER} -d ${PERF_DB}"
