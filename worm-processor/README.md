# worm-processor (Phase 2.1 Scaffold)

This module contains the first Annotation Processing Tool (APT) scaffold for WORM.

## What it does now

- Scans `@DbTable` entities at compile time.
- Generates one metadata factory per entity implementing:
  - `br.com.liviacare.worm.orm.registry.GeneratedEntityMetadataFactory<T>`
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

