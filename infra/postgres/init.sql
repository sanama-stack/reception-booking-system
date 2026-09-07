-- Extensions only. All schema lives in Flyway migrations (backend/src/main/resources/db/migration).
--
-- These are created here as well as in V1__extensions.sql because CREATE EXTENSION requires
-- privileges the application role should not need to hold; V1 is idempotent and simply confirms
-- they exist. See docs/06-security.md §12.

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;
