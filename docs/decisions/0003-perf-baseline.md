# ADR-0003: Performance Baseline

- Status: Accepted
- Date: 2026-11-08
- Deciders: halo-next

## Context

The project mandates a hot-path performance budget and zero steady-state
allocation. We need a recorded baseline to gate regressions (CI step 5) and to
anchor the `jmhBudgetCheck` task.

## Method

- Toolchain: JDK 21 LTS, ZGC not required for these micro-benchmarks.
- Command: `./gradlew jmhBudgetCheck -PquickBench -Pjmh.profilers=gc`.
- `-PquickBench` uses 1 warmup + 1 measurement iteration, 1 fork. These numbers
  are indicative smoke values, not publication-grade; the budget has wide margin
  so the quick run is a reliable gate. A full run (default forks/iterations) is
  used when publishing a formal number.
- Allocation measured with the JMH `gc` profiler (`gc.alloc.rate.norm`, B/op).

## Baseline (quickBench, indicative)

| Benchmark                         | Score      | Alloc (B/op) | Budget   | Status |
|-----------------------------------|------------|--------------|----------|--------|
| RecordDecode.decodeHeader         | ~2.2 ns    | ~0 (1e-5)    | < 100 ns | pass   |
| RecordDecode.decodeAndVerify      | ~6.2 ns    | ~0 (1e-4)    | < 100 ns | pass   |
| CodecEncode.encodeRecord          | ~16.0 ns   | ~0 (1e-4)    | < 100 ns | pass   |
| CodecEncode.encodeIndexEntry      | ~8.7 ns    | ~0 (1e-4)    | < 100 ns | pass   |
| CodecEncode.encodeTombstone       | ~7.9 ns    | ~0 (1e-4)    | < 100 ns | pass   |
| IndexLookup.lookupHit             | ~59 ns     | ~0 (1e-3)    | < 200 ns | pass   |
| IndexLookup.lookupMiss            | ~75 ns     | ~0 (1e-3)    | < 200 ns | pass   |
| IndexLookup.indexInsert           | ~72 ns     | ~0 (1e-3)    | < 300 ns | pass   |
| IndexLookup.indexReplace          | ~56 ns     | ~0 (1e-3)    | < 300 ns | pass   |
| Engine.get (index hit + 1 read)   | ~0.46 us   | ~120         | < 10 us  | pass   |
| Engine.getMiss (index miss)       | ~0.02 us   | ~0 (2e-3)    | < 1 us   | pass   |
| Engine.put (append + index, buf)  | ~1.36 us   | ~0.13        | < 5 us   | pass   |
| Engine.delete (tombstone + remove)| ~0.67 us   | ~0.19        | < 5 us   | pass   |

## Analysis

- Record decode and encode, index lookup / miss / insert / replace, `put`,
  `getMiss`, and `delete` are effectively allocation-free in steady state
  (sub-byte B/op from measurement noise). The reused flyweights and append
  buffer hold allocation to zero on the write path.
- The codec encode benchmarks rewrite the header and store the CRC32C over a
  pre-written key and value, mirroring the `decodeAndVerify` read counterpart.
- `getMiss` short-circuits on an index miss with no disk read, so it is an order
  of magnitude faster than a hit and allocation-free.
- `delete` re-inserts a live key in untimed per-invocation setup (delete returns
  early on an absent key); the measured cost is the tombstone append plus index
  remove.
- `get` allocates ~120 B/op: the returned value array. This is inherent to a
  `byte[] get(byte[])` API - the caller needs the bytes. The index lookup itself
  is allocation-free (out-parameter `RecordLocation`, which the JIT scalar-
  replaces). A future zero-copy read API (read into a caller buffer) can remove
  even this allocation if required.
- All hot-path benchmarks are well within budget with large margin.

## Decision

Adopt these budgets in `jmhBudgetCheck` (absolute, unit-normalized to ns):
`decodeHeader < 100`, `encodeRecord < 100`, `encodeIndexEntry < 100`,
`encodeTombstone < 100`, `lookupHit < 200`, `lookupMiss < 200`,
`indexInsert < 300`, `indexReplace < 300`, `get < 10000`, `getMiss < 1000`,
`put < 5000`, `delete < 5000`.
A regression that breaches a budget by more than 10 percent must be rolled back
or justified with a new ADR.

## Consequences

- CI step 5 runs `jmhBudgetCheck -PquickBench`; breaches fail the build.
- The baseline table is indicative; publish a full-run number in the PR when a
  hot-path change lands.
- File-layer benchmarks (`FileBenchmark.append`, `FileBenchmark.readValue`) and
  the Tier 2 macro harness (`benchmarkRun`) are I/O bound and are NOT gated by
  `jmhBudgetCheck`. On a WSL / overlay / shared dev box their numbers are not
  representative; capture authoritative file-layer and macro baselines on a
  dedicated Linux host.
