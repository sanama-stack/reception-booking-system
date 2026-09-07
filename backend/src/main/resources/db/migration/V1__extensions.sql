-- Phase 01 — extensions only. No domain concept exists yet.
--
-- citext      : case-insensitive email uniqueness on users (phase 02).
-- btree_gist  : the EXCLUDE constraint that makes double-booking structurally impossible
--               (phase 06, ADR-0002). This single feature is why the project runs PostgreSQL
--               and why the tests run Testcontainers rather than H2.
--
-- Both are also created by infra/postgres/init.sql at cluster initialisation, where the
-- superuser is available. This migration is idempotent and confirms they are present, so a
-- database provisioned by any other route still fails loudly here rather than at phase 06.

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;
