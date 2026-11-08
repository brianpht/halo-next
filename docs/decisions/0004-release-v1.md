# ADR-0004: 1.0 Release Scope

- Status: Accepted
- Date: 2026-11-08
- Deciders: halo-next

## Context

Milestones M0 through M3 are complete. This ADR fixes what the 1.0 tag includes
and what is explicitly deferred.

## Decision

Tag `v1.0.0` locally (no remote push) with the following scope.

### Included

- On-disk codec: flyweights for record, index entry, tombstone, and META with
  CRC32C (Format v1, little-endian).
- Off-heap segmented hash index on Agrona `UnsafeApi` (non-pool strategy), with
  per-segment spin-locks and stored-hash rehash.
- Append-only data/index/tombstone files with rollover, flush policy, and
  CRC-validated replay that stops at torn/corrupt tails.
- Single-writer engine: put, get (read amplification = 1), delete, recovery,
  clean/dirty shutdown detection.
- Background compaction as an Agrona `Agent` with a rate limiter and a
  `ManyToOneRingBuffer` work queue; out-of-place migration with a sequence-number
  guard; reader retry across the retire race.
- Public `HaloDB` facade: CRUD, iterators, stats, snapshot, pause/resume
  compaction.
- Tests: unit, property (jqwik), integration (real temp dir), deterministic
  replay, crash-injection, and soak. JMH suite with a budget gate.
- Docs: architecture, tuning, migration, and ADRs 0001-0003.

### Deferred (post-1.0, tracked in the plan)

- Fixed-slot memory-pool index strategy (`useMemoryPool` / `fixedKeySize`).
- Allocation-free compaction (off-heap key lookup so migration is buffer-to-
  buffer rather than allocating key/value arrays per migrated record).
- Zero-copy read API (read a value into a caller-provided buffer to remove the
  one remaining hot-path allocation in `get`).
- Tombstone-merge agent for open-time cleanup.
- Power-loss durability certification beyond the documented `syncWrite` contract.

## Consequences

- 1.0 is functionally complete for the core HaloDB use case on JDK 21 + Agrona,
  within the documented performance budget.
- Deferred items are optimizations or extensions; none change the on-disk Format
  v1 contract.

## Release steps

1. `./gradlew spotlessApply checkstyleMain checkstyleTest compileJava`
2. `./gradlew test integrationTest`
3. `./gradlew jmhBudgetCheck -PquickBench`
4. `git tag -a v1.0.0` (local only; do not push)
