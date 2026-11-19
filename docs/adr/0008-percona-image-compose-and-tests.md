# ADR 0008: Percona PostgreSQL 18 image for both docker compose and Testcontainers

- Status: Accepted
- Date: 2026-11-18

## Context
The development VM is **Ubuntu 26.04 on aarch64** (Parallels on an Apple M2 Max). The owner
chose Percona Distribution for PostgreSQL 18. If Percona had no arm64 build, tests would
have needed the official `postgres` image.

## Evidence (checked on Docker Hub, 2026-11-18)
- `percona/percona-distribution-postgresql:18.7.1-1` is a multi-arch manifest with **amd64 and arm64**,
  updated 2026-11-13. The `18`, `18.7` and `-ubi10` tags are multi-arch too.
- The official `postgres:18` image is also multi-arch with arm64, and stays available as a fallback.

## Decision
- **docker compose and Testcontainers both use `percona/percona-distribution-postgresql:18.7.1-1`**,
  pinned by exact tag in the root pom properties (`percona.pg.image`) and in `.env.example` for compose.
  Using the same database engine for tests and runtime avoids "passes in tests, fails in dev" differences.
- Testcontainers uses `PostgreSQLContainer` with
  `DockerImageName.parse(...).asCompatibleSubstituteFor("postgres")`.
- If the Percona image's entrypoint turns out to be incompatible with `PostgreSQLContainer`'s
  wait strategy or init, tests fall back to `postgres:18` (property switch), and this ADR is
  amended with the reason.

## Consequences
- The pulled image is about **1.37 GB** on the VM (corrected 2026-11-19; the earlier 400–600 MB estimate
  was wrong). Disk is fine (45 GB free on 2026-11-19).
- The VM was raised to **6 vCPU and about 11 GB of RAM** (checked 2026-11-19), which covers Postgres,
  the JVM, the Maven build and Testcontainers running together.
