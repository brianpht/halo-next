# ADR-0001: Package Name and Module Layout

- Status: Accepted
- Date: 2026-11-08
- Deciders: halo-next

## Context

halo-next is a port of HaloDB (original package `com.oath.halodb`) onto JDK 21
and the Agrona framework. We need a root package and a module layout that match
the workspace structure defined in `.github/copilot-instructions.md` and the
conventions of the sibling `justrade-io` projects (for example ledgerd uses
`io.justrade.ledgerd`).

## Decision

- Root package: `io.justrade.halonext`.
- Gradle group: `io.justrade.halonext`.
- Single-module Gradle project (Kotlin DSL) with the source layout:
  - `src/main/java/io/justrade/halonext/{buffer,index,file,core,compaction,api,config,util}`
  - `src/test/java` - unit and property tests
  - `src/integrationTest/java` - real temp-dir open/write/reopen cycles
  - `src/jmh/java` - JMH micro-benchmarks
- Toolchain: JDK 21 LTS. Build tasks: `build`, `test`, `integrationTest`, `jmh`.
- Quality gate: Spotless (palantir-java-format), Checkstyle (zero violations),
  `-Xlint:all -Werror`.

A single module is chosen over the ledgerd-style multi-module split because the
engine is one cohesive library with internal package boundaries, not a set of
independently deployable services.

## Consequences

- All internal packages live under one artifact; package-private visibility can
  enforce module boundaries without separate Gradle subprojects.
- Matches the workspace map in the Copilot instructions one-to-one.
- If the engine later grows client / tooling artifacts, modules can be split out
  with a follow-up ADR.

## Alternatives Considered

- `com.oath.halodb` (keep original): rejected - this is a distinct project and
  must not imply Oath authorship of new code.
- Multi-module split mirroring ledgerd: rejected for now - unnecessary overhead
  for a single embedded library.
