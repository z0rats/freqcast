# ADR-0006: Every schema change ships a real `Migration` and a schema snapshot

Status: Accepted · Area: data

**Decision.** `AppDatabase` has `exportSchema = true` (JSONs in `app/schemas/`). Each schema bump ships a hand-written `Migration` and its snapshot. Templates: `MIGRATION_3_4` (plain `ALTER TABLE ADD COLUMN`), `MIGRATION_2_3` (dedupe-then-constrain: delete pre-existing `name`/`streamUrl` collisions keeping the oldest row, then create the unique indices), `MIGRATION_7_8` (full create/copy/drop/rename rebuild — needed to *drop* a column, since `DROP COLUMN` depends on the bundled SQLite version).

**Accepting.** `fallbackToDestructiveMigrationFrom` exists only for pre-2.0 installs jumping from v1 (no v1 snapshot exists).

**Rejected.**
- Destructive fallback for any other version — wipes users' station lists on upgrade.
