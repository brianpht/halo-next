# halo-next Tuning Guide

All knobs live on `HaloDBOptions`. Defaults favor correctness and durability.
Tune per deployment and record non-default choices under /docs/decisions.

## Sizing the index

- `setNumberOfRecords(n)` - preallocate the off-heap index. Set to about 2x the
  expected live key count to avoid rehashing. The index stores 20 bytes of
  metadata plus the key per entry, off-heap.

## File sizes and rollover

- `setMaxFileSize(bytes)` - size of each data file before rolling. Larger files
  mean fewer open handles and faster compaction accounting but slower open (more
  to replay per file). Typical production value: 1 GiB.
- `setMaxTombstoneFileSize(bytes)` - size of each tombstone file.

## Durability

- `setSyncWrite(true)` - fsync after every append. Strongest durability, lowest
  throughput. Use when a flushed write must survive power loss.
- `setFlushDataSizeBytes(n)` - fsync after n unflushed bytes. `-1` leaves
  flushing to the OS page cache (fastest, survives process crash but not power
  loss). This is the main durability-vs-throughput dial.

Visibility (read-after-write in the same process) does not require a flush; it is
served from the page cache. Flushing controls durability across power loss.

## Compaction

- `setCompactionThresholdPerFile(ratio)` - a file is compacted when
  `staleBytes / totalBytes >= ratio`. Write amplification is approximately
  `1 / ratio`. Lower ratio = less space amplification, more write amplification.
  0.5 is a balanced starting point; 0.75 favors write throughput.
- `setCompactionJobRate(bytesPerSecond)` - caps compaction throughput so it does
  not saturate IO bandwidth. Tune against your disk and the write rate.
- `setCompactionEnabled(false)` - disables background compaction (for example for
  read-only or bulk-load phases).

## Lifecycle

- `setCleanUpInMemoryIndexOnClose(true)` - free native index memory on close.

## Validating performance

Run the JMH suite and the allocation profiler:

```
./gradlew jmh -PquickBench -Pjmh.profilers=gc
./gradlew jmhBudgetCheck -PquickBench
```

The budget gate enforces the targets in docs/decisions/0003-perf-baseline.md.
Use a full run (drop `-PquickBench`) to publish formal numbers.
