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
- The pulled image is about 400–600 MB on the VM. Disk is fine (48 GB free).
- The VM currently has **2 vCPU and about 3 GB of RAM**. Postgres plus the JVM plus the Maven build plus
  Testcontainers will be tight, so the VM should be raised to at least 4 vCPU and 8 GB.
