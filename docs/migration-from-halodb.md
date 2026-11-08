# Migrating from HaloDB

halo-next is a re-implementation of HaloDB (`com.oath.halodb`) on JDK 21 and
Agrona. The public API is intentionally close, but the on-disk format is not
compatible.

## API mapping

| HaloDB (`com.oath.halodb`)        | halo-next (`io.justrade.halonext.api`) |
|-----------------------------------|----------------------------------------|
| `HaloDB.open(dir, options)`       | `HaloDB.open(dir, options)`            |
| `byte[] get(byte[])`              | `byte[] get(byte[])`                   |
| `boolean put(byte[], byte[])`     | `boolean put(byte[], byte[])`          |
| `void delete(byte[])`             | `void delete(byte[])`                  |
| `long size()`                     | `long size()`                          |
| `newIterator()`                   | `newIterator()` (returns `Record`)     |
| `newKeyIterator()`                | `newKeyIterator()`                     |
| `stats()` / `resetStats()`        | `stats()` / `resetStats()`             |
| `pauseCompaction()` / `resume...` | `pauseCompaction()` / `resumeCompaction()` |
| `snapshot()` / `clearSnapshot()`  | `snapshot()` / `clearSnapshot()`       |
| `HaloDBOptions` setters           | `HaloDBOptions` setters (subset)       |

Exceptions are reported as `io.justrade.halonext.api.HaloDBException`.

## Options differences

Core knobs carry over: `maxFileSize`, `maxTombstoneFileSize`, `flushDataSizeBytes`,
`syncWrite`, `numberOfRecords`, `compactionThresholdPerFile`, `compactionJobRate`,
`cleanUpInMemoryIndexOnClose`. The fixed-slot memory pool (`useMemoryPool` /
`fixedKeySize`) is not yet implemented (tracked as a follow-up).

## On-disk format

halo-next Format v1 is little-endian and uses CRC32C, while HaloDB uses
big-endian `ByteBuffer` and CRC32. The logical field order and sizes are
preserved, but the bytes differ. See docs/decisions/0002-on-disk-format.md.

Consequence: halo-next cannot open an existing HaloDB directory. There is no
in-place upgrade. If migration is needed, read from HaloDB and write into
halo-next with a one-off program:

```java
try (var source = com.oath.halodb.HaloDB.open(oldDir, oldOpts);
     var target = io.justrade.halonext.api.HaloDB.open(newDir, newOpts)) {
    var it = source.newIterator();
    while (it.hasNext()) {
        var record = it.next();
        target.put(record.getKey(), record.getValue());
    }
}
```

## Runtime differences

- Requires JDK 21. The build passes `--add-opens` for Agrona and CRC32C; embed
  the same flags when running (see the module's JVM arguments).
- The in-memory index and large buffers are off-heap via Agrona `UnsafeApi`;
  JNA is no longer a dependency.
