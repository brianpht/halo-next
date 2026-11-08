# halo-next

A fast, embedded, persistent key-value store for the JVM. halo-next is a JDK 21
re-implementation of [HaloDB](https://github.com/yahoo/HaloDB) built on the
[Agrona](https://github.com/aeron-io/agrona) framework.

- Append-only, log-structured storage with immutable rolled files.
- Off-heap in-memory index: all keys and metadata off the Java heap, near-zero
  GC pressure.
- Read amplification = 1 (a single disk read per `get`).
- Single-writer, multi-reader; background compaction as an Agrona agent.
- On-disk Format v1: little-endian, CRC32C-checked.

See [docs/architecture.md](docs/architecture.md),
[docs/tuning.md](docs/tuning.md), and
[docs/migration-from-halodb.md](docs/migration-from-halodb.md).

## Usage

```java
import io.justrade.halonext.api.HaloDB;
import io.justrade.halonext.config.HaloDBOptions;

HaloDBOptions options = new HaloDBOptions()
        .setMaxFileSize(1024 * 1024 * 1024)
        .setCompactionThresholdPerFile(0.5)
        .setNumberOfRecords(100_000_000);

try (HaloDB db = HaloDB.open("/path/to/db", options)) {
    db.put("key".getBytes(), "value".getBytes());
    byte[] value = db.get("key".getBytes());
    db.delete("key".getBytes());
}
```

Keys are 1 to 255 bytes. Run on JDK 21 with the Agrona / CRC32C module opens (see
the build JVM arguments).

## Build

```
./gradlew build                      # library + benchmarks + tests
./gradlew test integrationTest       # unit + integration tests
./gradlew jmh                        # JMH benchmarks
./gradlew jmhBudgetCheck -PquickBench  # hot-path latency gate
./gradlew spotlessApply              # auto-format
```

## License

Apache 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Derived from HaloDB
(Copyright 2018, Oath Inc) and built on Agrona (Copyright Real Logic Limited).
