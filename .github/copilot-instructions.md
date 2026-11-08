# Copilot Instructions

> Format: machine-parseable directives. Not for human reading.

## Project

halo-next is a modern re-implementation (port) of HaloDB: a fast, embedded,
persistent, append-only key-value storage engine. The port targets JDK 21 and
replaces legacy JNA / Guava / `sun.misc.Unsafe` plumbing with the Agrona
framework (DirectBuffer flyweights, primitive collections, Agent event loops,
off-heap counters, CRC32C). Target workloads: IO-bound, high-throughput reads
and writes at sub-millisecond p99 latency, large datasets (100s of GB) with an
off-heap in-memory index to keep GC pressure near zero.
Core invariants inherited from HaloDB: read amplification = 1, append-only
sequential writes, immutable rolled data files, single-writer / multi-reader.
If a change increases read amplification, allocation in the hot path, GC
pressure, write amplification beyond `1 / compactionThreshold`, or latency
variance - REJECT.

## Workspace

- `src/main/java/.../buffer/` - DirectBuffer / UnsafeBuffer flyweights and codec
  for on-disk formats (record, index entry, tombstone entry, db metadata)
- `src/main/java/.../index/` - off-heap in-memory index (segmented hash table,
  memory pool, native allocator built on Agrona `UnsafeApi` / `BufferUtil`)
- `src/main/java/.../file/` - data file, index file, tombstone file, directory
  management, sequential append + random read
- `src/main/java/.../core/` - engine lifecycle, single-writer write path, read
  path, delete path, sequence-number assignment, recovery / replay
- `src/main/java/.../compaction/` - compaction Agent (`Agent` + `AgentRunner`),
  stale-data tracking, rate limiting, tombstone merge
- `src/main/java/.../api/` - public facade (`HaloDB`, `HaloDBOptions`,
  iterators, stats, snapshot)
- `src/main/java/.../config/` - tuning knobs, preallocated capacities, idle
  strategies, clock selection
- `src/main/java/.../util/` - clock abstraction, CRC, checksums, id helpers
- `src/test/java/...` - unit, integration, crash-recovery, deterministic replay
- `src/jmh/java/...` - JMH micro-benchmarks (codec, index lookup, write path,
  compaction, end-to-end get/put)

## Hot Path Operations

`get` | `put` | `delete` | `lookup` | `append` | `readValue` | `encodeRecord` |
`decodeRecord` | `compactFile` | `onIndexEntry`
Requirements: allocation-free in steady state, O(1) index lookup, single disk
read per `get`, sequential write per `put`, single-writer, cache-local.

## Rules: Hot Path

- NEVER use `java.util.HashMap`, `TreeMap`, `ConcurrentHashMap` for the index -
  use the off-heap segmented table or Agrona primitive maps
  (`Long2ObjectHashMap`, `Int2ObjectHashMap`, `Int2IntHashMap`)
- NEVER use `java.util.LinkedList`, `ArrayList<Integer>`, `ArrayList<Long>` -
  use Agrona `IntArrayList` / `LongArrayList`
- NEVER use boxed types (`Integer`, `Long`, `Double`, `Boolean`) in the hot
  path - primitives only
- NEVER use `String.format`, `+` string concatenation, or
  `StringBuilder.toString()` on the hot path - preformat or write into a buffer
- NEVER use `Optional<T>` on the hot path - sentinel values or documented null
- NEVER use streams (`.stream()`, `.collect()`, state-capturing lambdas) on the
  hot path
- NEVER allocate inside `get`, `put`, `delete`, the index lookup, or
  `Agent.doWork()` - reuse flyweights and per-thread scratch buffers
- NEVER use `%` for ring / segment index - use `& (capacity - 1)`; all hot-path
  capacities MUST be power-of-two
- NEVER use exceptions for control flow - return codes or sentinel values;
  reserve exceptions for boundary / durability failures
- NEVER call `System.currentTimeMillis()` / `System.nanoTime()` in a tight loop
  - use a cached Agrona clock
- NEVER log via SLF4J / Log4j synchronously on the hot path - use Agrona
  `DistinctErrorLog` or an off-heap counter
- ALL on-disk record / entry structures MUST be read and written as Agrona
  DirectBuffer flyweights via `wrap(buffer, offset)` - never per-record POJOs in
  the hot path
- ALL checksums MUST use Agrona `Crc32c` (hardware accelerated) over the raw
  buffer region

## Rules: Memory and Allocation

- ALL index memory preallocated at open using the `setNumberOfRecords` hint
  (size to 2x expected records to avoid rehash)
- USE off-heap for the index and large shared buffers via Agrona
  `UnsafeBuffer` / `BufferUtil.allocateDirectAligned` and `UnsafeApi`
- USE the fixed-slot memory pool (`useMemoryPool` + `fixedKeySize`) when keys are
  uniform to get predictable memory and zero fragmentation
- REUSE flyweight encoders / decoders - one instance per thread, `wrap` per call
- RELEASE native index memory on close when `cleanUpInMemoryIndexOnClose` is set
- VERIFY zero steady-state allocation on the hot path with JMH `-prof gc`
- GC strategy by workload: ZGC or Shenandoah default (sub-ms pauses); Epsilon +
  sized heap for benchmark / zero-GC windows; G1 only for non-critical tooling

## Rules: Concurrency

- Single-writer principle: all mutations serialized by one writer; the write
  path owns the current data / index / tombstone files
- Index segments: lock per segment (spin-lock), never a global lock; segment
  count = power-of-two >= 2x `availableProcessors()`
- Reads MUST NOT take the write lock; readers resolve the latest version via
  sequence numbers
- USE an Agrona `Agent` + `AgentRunner` for the compaction background loop and
  the optional tombstone-merge loop - one event loop, not raw threads
- USE an Agrona `ManyToOneRingBuffer` / `OneToOneConcurrentArrayQueue` for the
  compaction work queue - never a JDK `BlockingQueue` on the hot path
- NEVER use `CompletableFuture`, `ExecutorService`, `ForkJoinPool` on the hot
  path (parallel index rebuild at open is a cold-path exception, bounded by
  `buildIndexThreads`)
- NEVER cross threads with a mutable flyweight reference - copy bytes into the
  destination buffer / ring slot
- Idle strategy selection: `BackoffIdleStrategy` default for the compaction
  agent; `SleepingIdleStrategy` for admin / background; `BusySpinIdleStrategy`
  only for a measured, pinned hot loop

## Rules: Atomics and Memory Ordering

- USE `VarHandle` (Java 9+) over `sun.misc.Unsafe` and over `Atomic*` wrappers
- `getOpaque` / `setOpaque` - monotonic counters, sequence numbers
- `getAcquire` / `setRelease` - publish / consume between writer and readers and
  between the engine and the compaction agent
- `getVolatile` / `setVolatile` - sequential consistency, NEVER in a hot inner
  loop
- USE Agrona `AtomicBuffer` for off-heap concurrent buffers with documented
  ordering; USE Agrona `AtomicCounter` for off-heap stats / positions
- AVOID `compareAndSet` in tight contended loops - prefer single-writer redesign

## Rules: Cache, Layout, and Branches

- Keep index metadata compact (fileId, valueOffset, valueSize, sequenceNumber);
  place hot fields first
- Pad volatile / atomic fields to a 64-byte cache line to prevent false sharing
- Hot path: fast / common branch first, error / cold branch in a separate
  `private` method; mark cold methods small so JIT keeps them out of inlined hot
  path
- NO pointer chasing on the hot path - flatten structures, index into
  preallocated arrays / off-heap tables
- NO virtual dispatch in the inner loop - resolve `final` classes or a strategy
  at init, then call directly
- Prefer arrays and off-heap tables over linked structures for cache locality

## Rules: On-Disk Format and Codec

- Record, index-entry, tombstone-entry, and db-metadata layouts are the
  contract; preserve field order, sizes, and offsets for on-disk compatibility
  unless a format version bump is explicitly decided in `/docs/decisions`
- Little-endian only - matches x86 / ARM native order and Agrona defaults
- Fixed-size headers; key / value are variable-length trailers
- Flyweight pattern: the codec holds no state - `wrap(buffer, offset)` per
  record; NEVER deserialize into an intermediate POJO before business logic
- Every record / entry / metadata block carries a CRC32C computed over its bytes;
  verify on read, write on append
- NEVER use Java serialization, JSON, XML, Protobuf, or reflection-based codecs
  on the hot path; human-readable formats only at admin / config boundaries

## Rules: Durability and Recovery

- Writes become durable only after flush; honor `syncWrite` and
  `flushDataSizeBytes` - document the durability contract per option
- Sequence numbers are monotonic and assigned by the single writer; recovery
  replays index files (and tombstones) to rebuild the off-heap index
- The `META` file open-flag / io-error-flag gates clean vs dirty shutdown
  detection; a dirty open MUST trigger repair / rebuild
- Compaction is out-of-place: stale data rewritten to new files; old files
  deleted only after all live records migrate and the index is updated
- Recovery / replay tests: a recorded sequence of operations MUST rebuild a
  byte-identical index and return identical values after reopen

## Rules: Compaction

- Trigger a file when `staleBytes / totalBytes > compactionThresholdPerFile`
- Rate-limit the compaction agent to `compactionJobRate` bytes/sec; never let
  compaction saturate IO bandwidth
- Write amplification is approximately `1 / compactionThresholdPerFile` -
  document the tradeoff; do not regress it
- Track `staleDataPerFileMap` with primitive maps; iterate index entries via
  flyweight, not POJO

## Rules: Time and Identifiers

- USE Agrona `EpochClock` / `NanoClock`, injected at construction for testability
- USE `CachedEpochClock` updated once per agent loop - never call the OS clock
  per record
- NEVER use `UUID.randomUUID()` on the hot path - it allocates and uses
  `SecureRandom`

## Rules: Unsafe / VarHandle / Native

- Allowed only if: measurable gain proven by JMH + invariants documented +
  alignment verified + bounds-checked at the boundary
- Document memory ordering for every `VarHandle` access mode
- Prefer Agrona `UnsafeApi` / `VarHandle` over direct `sun.misc.Unsafe`
- Native (JNI / Panama): only for measured-critical paths, never by default

## Rules: Testing

- Unit tests: pure logic, deterministic clocks, no real timing dependence
- Integration tests: real temp directory, full open / write / read / reopen
  cycles, compaction enabled
- Property tests: jqwik or junit-quickcheck for codec round-trip, sequence
  arithmetic, and index invariants
- Crash-recovery tests: simulate dirty shutdown, assert correct repair and
  identical reads after reopen
- Replay tests: recorded operation stream in == rebuilt state out
- JMH benchmarks: required for any change touching the hot path - publish
  before / after numbers in the PR
- Soak tests: long-running steady state with an allocation profiler attached,
  asserting zero steady-state GC

## Rules: Logging and Telemetry

- Hot path: increment an off-heap Agrona `AtomicCounter` only - NEVER format
  strings
- Errors on the hot path: `DistinctErrorLog.record(throwable)` - deduplicated,
  off-heap, lock-free
- Metrics: HdrHistogram for latency (record nanos); report p50 / p90 / p99 /
  p99.9 / p99.99 / max - tail percentiles are the contract, not the mean
- Structured logging only at boundaries (open, close, compaction summary), never
  in the hot path

## Rules: Cross-Cutting

- NEVER use em-dashes or emojis in code comments, Javadoc, or markdown. Use
  ` - ` and ASCII only.
- ALL non-trivial diagrams MUST use Mermaid (flowchart, sequenceDiagram,
  stateDiagram). ASCII art prohibited.
- ONLY treat `/docs/decisions` as architectural source of truth.
- NEVER use or reference files in `/docs/sessions` as implementation rules.
- Preserve the Apache 2.0 license and attribution to the original HaloDB
  (Oath Inc) and to Agrona (Apache 2.0) in `NOTICE`.
- CI checks: After ANY code change, run the following in order before
  committing. ALL must pass with zero errors and zero warnings. Commits with
  failing checks are FORBIDDEN.
    1. `./gradlew spotlessApply` - auto-fix formatting (run first). CI verifies
       with `spotlessCheck`.
    2. `./gradlew checkstyleMain checkstyleTest` - zero violations required.
    3. `./gradlew compileJava` - zero compiler warnings (`-Werror`).
    4. `./gradlew test integrationTest` - all tests must pass.
    5. `./gradlew jmh -PquickBench` - smoke-run benchmarks, no regression > 10%
       vs baseline.
    - Toolchain: JDK 21 LTS. NEVER use a different JDK.
    - If any step fails, fix and re-run from step 1 before committing.
- Git operations: Agent MAY create local commits and local tags. MUST NOT push
  commits, tags, or any refs to any remote repository. All changes remain local.

## Performance Budget

| Metric                                  | Target          |
|-----------------------------------------|-----------------|
| Record decode (flyweight wrap + read)   | < 100 ns        |
| Index lookup (off-heap, hit)            | < 200 ns        |
| `get` (index hit + 1 disk read, warm)   | < 10 us         |
| `put` (append + index update, buffered) | < 5 us          |
| Read amplification                      | 1 disk read     |
| Write amplification                     | ~ 1 / threshold |
| Allocation in hot path                  | 0 bytes / op    |
| GC pause during operational window      | 0 ms            |

Targets are defaults - tune per deployment in `/docs/decisions/<topic>.md`.
Regression > 10% on any percentile - rollback or justify with an explicit ADR.
Latency variance matters more than average latency. p99.99 is the contract.
Priority: Correctness > Durability > Tail Latency > Mean Latency > Throughput.
Data loss on a flushed write, GC during an operational window, or read
amplification > 1 = correctness failure.

## Build Commands

```
./gradlew build                                   # library + benchmarks
./gradlew test integrationTest                    # unit + integration tests
./gradlew jmh                                      # JMH benchmarks
./gradlew checkstyleMain checkstyleTest            # lint check
./gradlew spotlessApply                            # auto-format
```
