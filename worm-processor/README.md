# worm-processor (Phase 2.1 Scaffold)

This module contains the first Annotation Processing Tool (APT) scaffold for WORM.

## What it does now

- Scans `@DbTable` entities at compile time.
- Generates one metadata factory per entity implementing:
  - `registry.orm.com.github.rfdetoni.worm.GeneratedEntityMetadataFactory<T>`
 - Generates static metamodel companions (`{Entity}_`) with `WormAttribute` descriptors.
- Generates WORM-native query path companions (`W{Entity}`), for example:
  - `WUser`
  - `WOrder`
  - `WBook`
  - with typed path fields (`StringPath`, `NumberPath`, `BooleanPath`, `UuidPath`, `EnumPath`, `DatePath`, `DateTimePath`, `ComparablePath`)
- Generates a service file so runtime discovery can happen through `ServiceLoader`.
- The generated factory currently delegates to `EntityMetadata.of(...)`.

This keeps behavior stable while establishing the compile-time generation contract.

## Build processor artifact

```bash
cd /home/toni/IdeaProjects/Livia/worm/worm-processor
mvn -q -DskipTests install
```

## Use processor in `worm`

```bash
cd /home/toni/IdeaProjects/Livia/worm
./mvnw -q -Pworm-apt -DskipTests clean compile
```

Generated sources are emitted by Maven under the standard annotation processing output directory.

## Next iterations

- Emit fully static metadata assembly (no reflection in generated `create(...)`).
- Generate direct row mappers per entity.
- Add compile-time validation diagnostics for missing/ambiguous joins.
