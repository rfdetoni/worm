# WORM Native DSL (W*) - Design and Migration Notes

## 1. Runtime DSL design

The DSL runtime is implemented in `com.github.rfdetoni.worm.dsl` with a minimal expression model:

- `Expression<T>`
- `Predicate extends Expression<Boolean>`
- `Path<T>`
- `EntityPath<T>`
- `OrderSpecifier`
- immutable predicate nodes:
  - `ComparisonPredicate`
  - `NullCheckPredicate`
  - `BetweenPredicate`
  - `InPredicate`
  - `JunctionPredicate` (AND/OR flattening)
  - `NotPredicate`
  - `LikePredicate`

Typed paths are specialized and reflection-free:

- `StringPath`
- `NumberPath<N extends Number & Comparable<?>>`
- `BooleanPath`
- `DatePath<T>`
- `DateTimePath<T extends Temporal>`
- `EnumPath<E extends Enum<E>>`
- `UuidPath`
- `ComparablePath<T extends Comparable<?>>`

No runtime proxies, no lambda/property decoding, no Criteria-style object graph.

## 2. Generated W* model

`WormMetamodelProcessor` now generates both:

1. existing `{Entity}_` metamodel classes
2. new `W*` query path classes (never `Q*`)

Generated `W*` classes:

- extend `EntityPath<EntityType>`
- include `TABLE` and `COLUMN_*` constants
- expose typed final path fields
- include deterministic table-based default alias (`AliasUtils.defaultMainAlias(TABLE)`)
- avoid class-name-derived SQL aliasing

Type inference mapping at compile-time:

- `String` -> `StringPath`
- primitives/wrappers/`BigDecimal`/`BigInteger` -> `NumberPath`
- `boolean`/`Boolean` -> `BooleanPath`
- `UUID` -> `UuidPath`
- enum -> `EnumPath`
- `LocalDate` -> `DatePath`
- other `Temporal` -> `DateTimePath`
- comparable fallback -> `ComparablePath`

## 3. Serializer and query-shape cache

`SqlRenderer` provides deterministic SQL serialization and bind extraction:

- stable SQL shape rendering with positional `?`
- stable parameter order
- explicit joins only
- branch-light rendering via direct `instanceof` dispatch

`QueryPlanCache` caches rendered SQL plans by `QueryShape`:

- key is structural (projection/join/where/order + limit/offset flags), value-independent
- SQL is rendered once per shape and reused
- hit/miss counters + hit ratio are exposed for benchmark instrumentation

## 4. Fluent API

Entry point: `com.github.rfdetoni.worm.Worm`

- entity query:
  - `Worm.selectFrom(u).where(...).orderBy(...).limit(...).fetch()`
- projection query:
  - `Worm.select(u.id, u.name).from(u).join(o).on(...).fetch()`
  - `fetchInto(MyDto.class)` supported

Execution strategy:

- entity fetch uses `OrmOperations.executeRaw(...)` to preserve current WORM mapper paths
- projection fetch uses explicit JDBC row extraction with `WormRow`
- module routing is preserved using entity metadata/module context

## 5. Benchmarks added

JMH benchmark added:

- `worm/src/test/java/br/com/liviacare/worm/dsl/WormDslQueryShapeJmhBenchmark.java`

Scenarios include:

- select by id shape
- filtered select
- paginated select
- join query
- projection query
- mixed repeated execution for cache hit ratio

Run:

```bash
cd worm
mvn -q -DskipTests test
java -cp target/test-classes:target/classes:<deps> org.openjdk.jmh.Main WormDslQueryShapeJmhBenchmark
```

Use JMH profilers (`-prof gc`, `-prof stack`) to collect alloc/op, bytes/op, and GC pressure.

## 6. Migration from FilterBuilder

### Before

```java
FilterBuilder.create()
  .eq("name", "Alice")
  .like("email", "%@company.com")
  .orderBy("created_at", false);
```

### After

```java
WUser u = WUser.user;

Worm.selectFrom(u)
    .where(u.name.eq("Alice").and(u.email.endsWith("@company.com")))
    .orderBy(u.createdAt.desc())
    .fetch();
```

Recommended migration path:

1. Keep existing `FilterBuilder` and native SQL paths for compatibility.
2. Introduce `W*` DSL for complex filters and explicit joins.
3. Keep native SQL as low-level escape hatch for hand-optimized cases.

## 7. Rejected alternatives (performance grounds)

1. Reflection-based property paths (`"user.name"`/getter parsing): rejected due runtime introspection overhead and weaker type safety.
2. Lambda/method-reference decoding (`User::getName`): rejected due hidden decoding/serialization cost and less predictable performance.
3. Criteria-style generic AST + visitor pipeline: rejected due object churn and branch-heavy serialization.
4. Runtime bytecode/proxy query objects: rejected due startup/runtime complexity and hard-to-profile execution paths.
5. Implicit relationship traversal joins: rejected to keep SQL explicit and predictable.

