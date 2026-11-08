# ADR-0002: On-Disk Format - halo-next Format v1

- Status: Accepted
- Date: 2026-11-08
- Deciders: halo-next

## Context

HaloDB 0.5.x serializes every on-disk structure with `java.nio.ByteBuffer` using
its default byte order (BIG-ENDIAN) and computes checksums with
`java.util.zip.CRC32` (the standard, non-Castagnoli polynomial).

The halo-next rules in `.github/copilot-instructions.md` mandate:

- Little-endian only (matches x86 / ARM native order and Agrona defaults).
- All checksums MUST use Agrona `Crc32c` (hardware accelerated, Castagnoli).

These two requirements are in direct conflict with byte-for-byte compatibility
with HaloDB 0.5.x data files. We must choose between preserving binary
compatibility and following the modern hot-path rules.

## Decision

Define a new format, "halo-next Format v1", that is NOT byte-compatible with
HaloDB 0.5.x:

- Byte order: LITTLE-ENDIAN for all multi-byte fields.
- Checksum: Agrona `Crc32c` over the post-checksum header bytes plus the
  key (and value, for data records), matching HaloDB's checksum coverage region
  but with the Castagnoli polynomial.
- Field ORDER, SIZES, and OFFSETS are preserved from HaloDB so the logical
  layout and codec structure carry over unchanged. Only endianness and the
  checksum algorithm differ.

Header layouts (offsets in bytes; all headers little-endian; checksum at offset
0 covers the bytes after the checksum field):

- Record header (data file), size 18:
  - crc32c(0,4), version(4,1), keySize(5,1), valueSize(6,4), sequenceNumber(10,8)
- Index entry header (index file), size 22:
  - crc32c(0,4), version(4,1), keySize(5,1), recordSize(6,4),
    recordOffset(10,4), sequenceNumber(14,8)
- Tombstone entry header (tombstone file), size 14:
  - crc32c(0,4), version(4,1), sequenceNumber(5,8), keySize(13,1)
- DB metadata (META), size 19:
  - crc32c(0,4), version(4,1), open(5,1), sequenceNumber(6,8), ioError(14,1),
    maxFileSize(15,4)

The variable-length key (and value, for records) trail the fixed header.

A single-byte `version` field is retained in every structure. halo-next Format
v1 uses its own version constants; HaloDB files are considered a foreign format
and are not read by halo-next v1.

## Consequences

- halo-next CANNOT open an existing HaloDB 0.5.x directory. Migration, if ever
  needed, requires an explicit offline converter (separate tool, separate ADR).
- The hot path gains hardware-accelerated CRC32C and native-endian reads with no
  byte-swap, satisfying the performance budget (record decode < 100 ns).
- Any future change to field order, size, offset, endianness, or checksum is a
  format version bump and requires a new ADR.

## Alternatives Considered

- Preserve HaloDB byte compatibility (big-endian + CRC32): rejected - it
  violates the mandatory little-endian and Crc32c rules and forgoes the
  hardware-accelerated checksum and zero byte-swap reads.
- Little-endian but keep legacy CRC32: rejected - the rules require Crc32c, and
  mixing gains nothing.
