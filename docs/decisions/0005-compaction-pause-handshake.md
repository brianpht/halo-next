# ADR-0005: Compaction Pause/Resume Handshake for Consistent Snapshots

- Status: Accepted
- Date: 2026-11-08
- Deciders: halo-next

## Context

`snapshot()` captures a consistent on-disk image by hard-linking (or copying) the
database files. For the image to be internally consistent it must observe a
quiescent compaction state: no background compaction may be mutating the file set
(writing migrated records, retiring and deleting old data/index files, or calling
`index.replace`) while the directory is being linked.

The previous handshake used two independent `volatile` flags:

- `pauseCompaction()` set `compactionPaused = true`, then spun on `while (compacting)`.
- The agent loop ran `pollFileId()` -> `rateLimiter.acquire()` -> `compact()`, and
  `compacting` was only set to true inside `compact()`.

This left a gap. Between `pollFileId()` committing to a file (removing it from the
queue) and `compact()` finally setting `compacting = true`, the agent passes through
`RateLimiter.acquire()`, which can `Thread.sleep` for a long time when compaction is
rate-limited. A pause arriving in that window observes `compacting == false`,
concludes compaction is quiescent, and lets `snapshot()` proceed concurrently with a
compaction that is about to start. Because `compact()` does not hold the write lock
for its whole duration, the snapshot can then capture a torn state: a file deleted by
`retireFile` mid-link, an index entry relocated to a compaction file not yet present,
or a half-migrated record set. Per the project priority order (Correctness >
Durability > Tail Latency), an inconsistent image of a flushed database is a
correctness failure, so this is the most serious of the known compaction races.

Constraints and invariants that shape the fix:

- Single-writer principle; prefer a single-writer redesign over contended CAS.
- Compaction runs on an Agrona `Agent` + `AgentRunner` event loop.
- `pause`/`resume`/`snapshot` are cold/admin paths, so locks and condition variables
  are acceptable there; the hot-path zero-allocation and no-lock rules do not apply.
- Immutable rolled data files; append-only; sequence-number version resolution.

## Decision

Adopt an actor-style pause handshake owned by the single compaction thread
(option C). The compaction work unit is bracketed explicitly:

- The `CompactionTarget` contract becomes `beginCompaction()` ... `compact()` ...
  `endCompaction()`, with `isPausePending()` for the rate-limit abort fast path.
- `beginCompaction()` runs under a dedicated `pauseLock`: if a pause is requested it
  returns -1 without polling; otherwise it polls the next file id and sets
  `compactionActive = true` in the same critical section. Deciding not-to-pause and
  marking the unit active are therefore atomic, so a pause can never slip in between.
- The agent runs `rateLimiter.acquire(size, target::isPausePending)` then
  `compact(fileId)`, and always calls `endCompaction()` in a `finally`, which clears
  `compactionActive` and signals the pause condition.
- `pauseCompaction()` takes `pauseLock`, sets `pauseRequested = true`, and awaits
  `compactionActive == false`. Because `compactionActive` brackets the entire unit
  (including the rate-limit sleep), the pause always waits for an in-flight unit and,
  while `pauseRequested` stays set, no new unit can start.
- `RateLimiter.acquire` sleeps in bounded slices and returns early when the abort
  supplier reports a pending pause, so a pause is not delayed by a long throttle.
- `snapshot()` additionally flushes the compaction write/index files (now quiescent
  under the write lock) so the linked image is durable, not just the current files.

Lock ordering is always `pauseLock` -> `writeLock` (never the reverse), so no
deadlock is introduced; `pauseCompaction` releases `pauseLock` inside `await`, letting
the agent's `endCompaction` acquire it to signal.

## Consequences

Positive:

- Closes the pause/snapshot race: a pause issued during a rate-limited compaction
  deterministically waits for the in-flight unit, then blocks new units.
- Aligns with the single-writer and Agrona-agent rules; removes the two-flag
  (Dekker-style) reasoning in favor of one lock plus a condition variable.
- Pause latency is bounded by the abortable rate-limit wait instead of the full
  deficit sleep.
- Snapshots are now durable with respect to compaction output as well.

Negative / neutral:

- Adds a cold-path lock and condition variable and widens the `CompactionTarget`
  contract (`beginCompaction`/`endCompaction`/`isPausePending`). No hot-path impact:
  `get`/`put`/`delete` do not touch `pauseLock`.
- Compaction still fully stops during a snapshot. Eliminating the stop-the-world is
  deferred to option D (below); this ADR does not regress any performance-budget
  metric and does not change the on-disk format.

## Amendment: reference-counted pause holders

The handshake as first shipped kept `pauseRequested` as a single binary flag. That
is correct for one holder but wrong when two pause holders overlap. A `HaloDBIterator`
holds a pause for its whole lifetime (its `RecordScanner` constructor calls
`pauseCompaction()`); if a `snapshot()` runs concurrently it calls
`pauseCompaction()`/`resumeCompaction()` around its link step. With a binary flag the
snapshot's `resumeCompaction()` clears the shared flag, so compaction resumes while
the iterator is still scanning. A file can then be retired under the cursor, and the
scan reads a closed channel (`ClosedChannelException` surfaced as
`UncheckedIOException`) or silently skips records. This is a correctness failure by
the project priority order.

Fix: `pauseRequested` becomes a reference count (`pauseCount`) guarded by `pauseLock`.
Each `pauseCompaction()` increments it and each `resumeCompaction()` decrements it;
compaction resumes only when the count returns to zero. A `volatile boolean
pauseRequested` mirrors `pauseCount > 0` so the rate-limit abort supplier
(`isPausePending()`) can still read it lock-free. An unbalanced extra `resume` is
clamped at zero rather than underflowing, so the failure mode is "stay paused" rather
than "resume early" - the safe direction. Overlapping holders now nest correctly; no
on-disk format or hot-path impact. This is the minimal correctness fix and remains
subsumed by option D, after which `snapshot()` would hold a pinned version instead of
pausing at all.

## Alternatives Considered

### Option D - Version pinning / reference counting (no pause at all)

Maintain a reference-counted set of live file ids (a VersionSet/MANIFEST) and defer
file deletion until no reader or snapshot references a file. `snapshot()` would pin
the current version and link an immutable set while compaction proceeds concurrently;
`retireFile` would drop a refcount instead of deleting immediately. This is the
approach used by LevelDB/RocksDB (VersionSet), Cassandra (SSTable refcounting with
hard-linked snapshots), and LMDB (deferred page reclamation).

- Pro: fully concurrent snapshot and compaction; no stop-the-world; matches the
  immutable-data-file invariant and is the dominant design in high-performance stores.
- Con: largest change - introduces refcounting, deferred deletion, and version
  bookkeeping, plus a rewrite of `retireFile`'s delete-now behavior and a new leak
  risk if refcounts are mismanaged.

Rejected for now as too large for the immediate correctness fix. Option C is the
correct, minimal, single-writer-aligned fix and is independently useful as the admin
"pause all background work" primitive (as in RocksDB `PauseBackgroundWork`). Option D
remains the recommended long-term direction and can supersede this ADR; when adopted,
`snapshot()` would no longer call `pauseCompaction()`.

### Local two-flag fixes (set the busy flag earlier, or re-check under a lock)

Setting `compacting` earlier still needs a symmetric Dekker re-check across two
`volatile` flags, and re-checking the pause flag under `writeLock` risks a
lock-ordering deadlock with `retireFile`. Both converge on the lock + condition
variable design chosen above, so they are folded into option C rather than kept as
separate shortcuts.
