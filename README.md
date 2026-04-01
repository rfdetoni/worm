# WORM ORM - Lightweight Java ORM Framework
[![Build Status](https://img.shields.io/badge/build-success-brightgreen)]()
[![Java Version](https://img.shields.io/badge/java-25+-blue)]()
[![License](https://img.shields.io/badge/license-MIT-green)]()

**WORM** (Write-Optimize Relational Mapper) is a lightweight, zero-dependency ORM framework for Java with Spring Boot support. It's designed to be **fast, flexible, and JPA/Hibernate-free**.

## Table of Contents

- [Key Features](#key-features)
- [Quick Start](#quick-start)
- [Relational Example](#relational-example)
- [Practical Examples](#practical-examples)
- [Native Query Repositories](#native-query-repositories)
- [Static Metamodel](#static-metamodel)
- [Configuration](#configuration)
- [SQL Dialects](#sql-dialects)
- [Performance Guide](#performance-guide)
- [Architecture](#architecture)
- [Module Routing & Multi-Tenancy](#module-routing--multi-tenancy)
- [Limitations & Non-Features](#limitations--non-features)
- [Build & Testing](#build--testing)
- [Publishing](#publishing)
- [Contributing](#contributing)

## Key Features

- ✅ **Zero JPA/Hibernate Dependency** - Pure JDBC-based implementation
- ✅ **Spring Boot Integration** - Auto-configuration and transaction management
- ✅ **Java 25+ Support** - Modern language features (records, sealed classes, virtual threads ready)
- ✅ **Type-Safe Queries** - Fluent API with compile-time safety
- ✅ **Soft Delete Support** - Built-in soft delete with @DeletedAt/@Active annotations
- ✅ **Optimistic Locking** - Version column support with @DbVersion
- ✅ **Audit Fields** - Automatic @CreatedAt, @UpdatedAt, @CreatedBy tracking
- ✅ **JSON/JSONB Support** - Native database JSON column support (PostgreSQL, MySQL)
- ✅ **Multiple Database Dialects** - PostgreSQL and MySQL support with extensible architecture
- ✅ **Batch Operations** - Efficient batch insert/update/delete
- ✅ **Joins & Projections** - @DbJoin for complex queries and record projections
- ✅ **Micrometer Metrics** - Optional performance monitoring integration
- ✅ **Modularity** - SPI for module routing and multi-tenancy support
- ✅ **Compiled Query Plan Cache** - SQL strings are built once per query shape and reused (zero string allocation on repeat calls)
- ✅ **Zero-Allocation Row Mapping** - `MethodHandle.asSpreader` eliminates intermediate `Object[]` copies during record construction
- ✅ **Smart Batch-Fetch for Collections** - `@DbJoin(fetchMode = BATCH)` replaces Cartesian-product JOINs with a two-query `IN`-clause strategy, avoiding result-set explosion
- ✅ **Static Metamodel Generator** - APT processor generates `{Entity}_.java` companion classes with typed `WormAttribute` column constants for IDE auto-complete and compile-time safety
- ✅ **Universal Bulk SPI** - `BulkWriter` interface decouples bulk-write logic from the ORM core; `PostgresDialect` supplies a `PostgresBulkWriter` with `COPY`, `unnest`, and `ON CONFLICT DO UPDATE` strategies

## Quick Start

1. Add WORM to your `pom.xml` dependencies (example shown in the Installation section).
2. Annotate your entity with `@DbTable`, `@DbId`, `@DbColumn`, etc. to make it known to the ORM.
3. Inject `Finder.of(Entity.class)` (or extend `Persistable`) and start querying with `FilterBuilder`, `Slice`, or native `@Query` methods.

### 1. Installation

Add the library to your Maven project:

```xml
<dependency>
  <groupId>br.com.liviacare</groupId>
  <artifactId>worm</artifactId>
  <version>1.0.6</version>
</dependency>
```

### 2. Define an entity

#### Option A: ActiveRecord Pattern (Recommended)

```java
@DbTable("users")
public class User extends ActiveRecord<User, UUID> implements Persistable<User> {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;

    @DbColumn("email")
    private String email;

    @CreatedAt
    private Instant createdAt;

    @UpdatedAt
    private Instant updatedAt;

    @DbVersion
    private long version;

    // Getters/setters...
}
```

#### Option B: Traditional Finder Pattern

```java
@DbTable("users")
public class User implements Persistable<User> {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;

    @DbColumn("email")
    private String email;

    @CreatedAt
    private Instant createdAt;

    @UpdatedAt
    private Instant updatedAt;

    @DbVersion
    private long version;

    // Getters/setters...
}
```

### 3. ORM operations

#### Using ActiveRecord Pattern (extends ActiveRecord<T, ID>)

```java
// Create and save
User user = new User();
user.setId(UUID.randomUUID());
user.setName("John Doe");
user.save(); // Uses Persistable.save()

// Finder defaults available from the object (ActiveRecord implements Finder)
Optional<User> foundFromObject = user.byId(userId);
List<User> activeFromObject = user.all(FilterBuilder.create().eq("status", "active"));

// Class-level gateway (no per-entity static boilerplate)
ActiveRecord.EntityOps<User, UUID> users = ActiveRecord.ar(User.class);
Optional<User> found = users.byId(userId);
List<User> all = users.all();

// Query with filter
FilterBuilder filter = FilterBuilder.create()
        .eq("status", "active")
        .order("createdAt", "DESC");
List<User> active = users.all(filter);

// Pagination
Slice<User> page = users.all(Pageable.of(0, 20));
Slice<User> filtered = users.all(filter, Pageable.of(0, 20));

// Count
long total = users.count();
long activeCount = users.count(filter);

// Existence check
boolean exists = users.exists();
boolean hasActive = users.exists(filter);

// Static shortcuts also exist
List<User> all2 = ActiveRecord.all(User.class);
Optional<User> one2 = ActiveRecord.byId(User.class, userId);
```

### Relational Example

For a complete example covering related entities with `@DbJoin` (including `localColumn`, `targetColumn`, `mappedBy`, `fetchMode`, and `on`) and full ActiveRecord flow (`Entity.save()`, query, `update()`, `delete()`), see:

- `WORM_USAGE_GUIDE.md` -> `28. @DbJoin relationships with ActiveRecord`

If you prefer the exact style `User.byId(id)` / `User.all()`, add tiny static forwarders in the entity:

```java
@DbTable("users")
public class User extends ActiveRecord<User, UUID> {
    // ...fields...

    private static final EntityOps<User, UUID> AR = ActiveRecord.ar(User.class);

    public static Optional<User> byId(UUID id) { return AR.byId(id); }
    public static List<User> all() { return AR.all(); }
    public static List<User> all(FilterBuilder filter) { return AR.all(filter); }
    public static Slice<User> all(Pageable pageable) { return AR.all(pageable); }
    public static Slice<User> all(FilterBuilder filter, Pageable pageable) { return AR.all(filter, pageable); }
    public static long count() { return AR.count(); }
    public static long count(FilterBuilder filter) { return AR.count(filter); }
    public static boolean exists() { return AR.exists(); }
    public static boolean exists(FilterBuilder filter) { return AR.exists(filter); }
}
```

If you prefer the classic and even less verbose style `User.find.byId(id)` / `User.find.all()`, use the built-in factory once:

```java
@DbTable("users")
public class User extends ActiveRecord<User, UUID> {
    // ...fields...

    public static final Finder<User, UUID> find = ActiveRecord.find(User.class);
}

// Usage
Optional<User> one = User.find.byId(userId);
List<User> all = User.find.all();
List<User> active = User.find.all(FilterBuilder.create().eq("status", "active"));
```

#### Using Traditional Finder Pattern

```java
// Create and save (same for both patterns)
User user = new User();
user.setId(UUID.randomUUID());
user.setName("John Doe");
user.save();

// Query using Finder static methods
Optional<User> found = Finder.byId(User.class, userId);

FilterBuilder filter = new FilterBuilder()
        .eq("status", "active")
        .order("createdAt", "DESC");
List<User> active = Finder.all(User.class, filter);

Slice<User> page = Finder.all(User.class, new FilterBuilder(), Pageable.of(0, 20));
```

### 4. CLI & lifecycle

```bash
./mvnw clean package           # build the library and run generators
./mvnw test                    # execute the test suite
```

### 5. Explicit enable (optional)

WORM is auto-configured when on the classpath. If you prefer explicit opt-in, annotate your application:

```java
import br.com.liviacare.worm.annotation.EnableWorm;

@SpringBootApplication
@EnableWorm
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

## Practical Examples

### FilterBuilder in action

Use `FilterBuilder` to express complex `WHERE` clauses without string concatenation. Chain predicates, joins, and orderings, and then hand the filter to `Finder` or `OrmOperations`:

```java
FilterBuilder filter = new FilterBuilder()
        .eq("status", "active")
        .gt("age", 18)
        .jsonPathExists("metadata", "$.[?(@.vip == true)]")
        .orderBy(Pageable.Sort.desc("created_at"));

List<User> users = Finder.all(User.class, filter);
```

### Finder & ClassFinder

`Finder<T, ID>` exposes static helpers (`Finder.byId`, `Finder.all`) and instance methods when injected. `ClassFinder` lets you reuse a single alias when building filters.

```java
Finder<User, UUID> finder = Finder.of(User.class);
Slice<User> slice = finder.all(Pageable.of(0, 20));
```

### Pagination and slices

Call `Finder.all` or `OrmOperations.findAll` with a `Pageable` to get a `Slice`, which tracks `hasNext` without counting the total. WORM trims the extra row itself for efficiency.

```java
Slice<User> page = Finder.all(User.class, new FilterBuilder().eq("active", true), Pageable.of(2, 25));
if (page.hasNext()) {
    // continue reading next slice
}
```

### Less verbose @DbJoin (JPA-like)

`@DbJoin` now supports convention-based inference so you do not need to write raw `ON` clauses for common cases.

```java
@DbTable("users")
public class User {
    @DbId("id")
    private UUID id;

    // infers table from Department.@DbTable("departments")
    // infers alias = "department"
    // infers ON: department.id = a.department_id
    @DbJoin
    private Department department;

    // collection join using mappedBy shortcut
    // infers ON: orders.owner_id = a.id
    @DbJoin(mappedBy = "owner_id")
    private List<Order> orders;

    // explicit FK column shortcut (without writing full ON SQL)
    @DbJoin(localColumn = "manager_id")
    private User manager;

    // BATCH fetch: parent + one child query instead of a cartesian JOIN
    @DbJoin(mappedBy = "user_id", fetchMode = DbJoin.FetchMode.BATCH)
    private List<Tag> tags;
}
```

Supported join shortcuts:

- `@DbJoin` (no args): infer table, alias, and `ON` by convention.
- `@DbJoin(localColumn = "...")`: infer `ON` as `<alias>.id = a.<localColumn>`.
- `@DbJoin(localColumn = "...", targetColumn = "...")`: same as above, but joining against a non-`id` column in the target table.
- `@DbJoin(mappedBy = "...")`: infer collection `ON` as `<alias>.<mappedBy> = a.id`.
- `@DbJoin` on collections without `mappedBy`: tries to infer from a back-reference field in the child entity (e.g. `user -> user_id`), then falls back to `<singular_main_table>_id`.
- `@DbJoin(on = "...")`: full manual control when needed.

#### `fetchMode` — controlling how collection joins are loaded

| `fetchMode` value | Strategy | When to use |
|---|---|---|
| `FetchMode.JOIN` *(default)* | Single `LEFT JOIN` query; rows de-duplicated in memory | Small collections, reporting queries, existing behaviour |
| `FetchMode.BATCH` | Two queries: parent `SELECT` then child `WHERE fk IN (ids)` in chunks of ≤ 1 000 | Large collections where the Cartesian product would be expensive |

```java
// Default: single LEFT JOIN, all rows fetched in one query
@DbJoin(mappedBy = "order_id")
private List<OrderItem> items;

// BATCH: parent rows first, then child rows fetched separately
@DbJoin(mappedBy = "order_id", fetchMode = DbJoin.FetchMode.BATCH)
private List<OrderItem> items;
```

> **Note** `fetchMode = BATCH` is only effective on `List` / `Collection` fields. On scalar join fields it is silently ignored and the default `JOIN` strategy is used.

## Core Concepts

### Annotations

| Annotation | Purpose |
|-----------|---------|
| `@DbTable` | Maps class to database table |
| `@DbId` | Marks primary key field |
| `@DbColumn` | Maps field to column (with optional SQL expressions) |
| `@DbJoin` | Defines table join for related entities |
| `@DbJoin(fetchMode = FetchMode.BATCH)` | Two-query batch strategy for collection joins (avoids Cartesian explosion) |
| `@DbVersion` | Optimistic locking version column |
| `@CreatedAt` | Auto-set creation timestamp |
| `@UpdatedAt` | Auto-set update timestamp |
| `@CreatedBy` | Track entity creator |
| `@UpdatedBy` | Track entity updater |
| `@Active` | Soft delete active flag column |
| `@DeletedAt` | Soft delete timestamp column |
| `@OrderBy` | Default sort order |

### Interfaces

- **`Persistable<T>`** - Provides `save()` and `update()` methods
- **`Deletable<T, ID>`** - Provides `delete()` method
- **`Finder<T, ID>`** - Query interface with static and instance methods
- **`iBaseEntity`** - Base interface for audit field tracking
- **`BulkWriter`** - SPI for driver-level bulk insert/update/delete/upsert operations

### FilterBuilder

The `FilterBuilder` API lets you build predicates declaratively, with predicates described in the [Practical Examples](#filterbuilder-in-action) section. Use it with `Finder`, `OrmOperations`, or native queries to supply the `WHERE` clause and bind parameters automatically.

All comparison methods accept either raw string column names **or** typed `WormAttribute` descriptors generated by the static metamodel (see [Static Metamodel](#static-metamodel)):

```java
// String-based (classic)
FilterBuilder.create().eq("first_name", "Alice").gt("age", 18);

// Type-safe with generated metamodel (compile-time verified)
FilterBuilder.create().eq(User_.firstName, "Alice").gt(User_.age, 18);
```

New in this release:

- `rawWhere(String rawClause, List<Object> params)` — append a hand-written SQL fragment with bound parameters when the built-in predicates are not expressive enough.

## Native Query Repositories

You can map repository interfaces directly to native SQL statements with `@Query`. The factory below builds a proxy that executes the SQL and maps rows to entities, projections or DTOs:

```java
public interface UserRepository {

    @Query("select * from users")
    List<User> findAll();

    @Query("select * from users where active = :active")
    List<User> findAllActive(@QueryParam("active") Boolean active);

    @Query("select * from users order by created_at desc")
    Slice<User> findRecent(Pageable pageable);
}

UserRepository repo = QueryRepositoryFactory.create(UserRepository.class);
List<User> active = repo.findAllActive(true);
Slice<User> page = repo.findRecent(Pageable.of(0, 25));
```

Parameters bind by name via `@QueryParam` (or by compiling with `-parameters`). Supported return types are `List`, `Optional` and `Slice` (slice methods must accept a `Pageable`).

Named parameters declared as `:name` are converted to `?` placeholders at runtime. The proxy prefers `@QueryParam` but falls back to compiler-provided parameter names when you build with `-parameters` (already enabled by `maven.compiler.parameters`).

| Return type | Behavior |
|-------------|----------|
| `List<T>` | Maps all rows to `T` via `OrmOperations.executeRaw`. |
| `Optional<T>` | Returns the first row wrapped in `Optional`, or `Optional.empty()` when none. |
| `Slice<T>` | Appends `LIMIT pageSize + 1` and `OFFSET pageNumber * pageSize` so callers can detect `hasNext`. |

The library exposes `QueryRepositoryFactory.create(...)` for manual wiring plus the auto-configured `QueryRepositoryFactoryBean` described below.

### Autoconfiguration

If you include WORM in a Spring Boot app, repositories annotated with `@QueryRepository` are automatically picked up. Control the scan with the `worm.query.repository.base-packages` property (defaults to `br.com.liviacare`):

```yaml
worm:
  query:
    repository:
      base-packages:
        - com.myapp.query
        - br.com.liviacare.custom
```

The auto-configuration creates a `QueryRepositoryFactoryBean` for each interface, injects the shared `OrmOperations`, and exposes the proxy as a Spring bean.

## Static Metamodel

The annotation processor `WormMetamodelProcessor` generates a companion `{Entity}_.java` class for every `@DbTable` entity at compile time. Each generated class lives in the same package as the entity and contains:

- `public static final String COLUMN_<UPPER_SNAKE>` — raw column name constant.
- `public static final WormAttribute<Entity, FieldType> <fieldName>` — typed column descriptor.

### Generated example

Given:

```java
@DbTable("users")
public class User {
    @DbId("id")
    private UUID id;

    @DbColumn("first_name")
    private String firstName;

    @DbColumn("age")
    private int age;
}
```

The processor generates:

```java
// Generated — do not edit
public final class User_ {

    /** DB column for {@code id}. */
    public static final String COLUMN_ID = "id";
    public static final WormAttribute<User, java.util.UUID> id =
        new WormAttribute<>("id", java.util.UUID.class);

    /** DB column for {@code firstName}. */
    public static final String COLUMN_FIRST_NAME = "first_name";
    public static final WormAttribute<User, String> firstName =
        new WormAttribute<>("first_name", String.class);

    /** DB column for {@code age}. */
    public static final String COLUMN_AGE = "age";
    public static final WormAttribute<User, java.lang.Integer> age =
        new WormAttribute<>("age", java.lang.Integer.class);
}
```

### Type-safe FilterBuilder usage

```java
import static com.example.User_;

List<User> result = Finder.all(User.class, FilterBuilder.create()
    .eq(User_.firstName, "Alice")     // compile-time type check: String
    .gte(User_.age, 18)               // compile-time type check: Integer
    .orderBy(User_.firstName));        // compile-time column reference
```

Renaming a Java field and its `@DbColumn` value is automatically reflected in the metamodel on the next compilation — no string literals to hunt down.

### Supported typed overloads in FilterBuilder

All comparison predicates accept a `WormAttribute<E, V>` as the first argument:

| Method | Example |
|--------|---------|
| `eq(attr, value)` | `.eq(User_.status, "ACTIVE")` |
| `neq(attr, value)` | `.neq(User_.status, "DELETED")` |
| `gt(attr, value)` | `.gt(User_.age, 18)` |
| `lt(attr, value)` | `.lt(User_.age, 65)` |
| `gte(attr, value)` | `.gte(User_.createdAt, start)` |
| `lte(attr, value)` | `.lte(User_.createdAt, end)` |
| `like(attr, pattern)` | `.like(User_.firstName, "%Ali%")` |
| `in(attr, collection)` | `.in(User_.role, List.of("ADMIN", "MANAGER"))` |
| `isNull(attr)` | `.isNull(User_.deletedAt)` |
| `isNotNull(attr)` | `.isNotNull(User_.email)` |
| `orderBy(attr)` | `.orderBy(User_.createdAt)` |
| `orderByDesc(attr)` | `.orderByDesc(User_.createdAt)` |

### Setup

The processor is part of the `worm-processor` module. It runs automatically when `worm-processor` is on the annotation-processor classpath. With the Maven build provided, no extra configuration is needed — the generated sources land in `target/generated-sources/annotations`.

If you use the library as a dependency, add the processor explicitly:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>br.com.liviacare</groupId>
        <artifactId>worm-processor</artifactId>
        <version>1.0.6</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

---

## Configuration

WORM exposes the `WormProperties` bean for adjusting batch size, schema validation, and other core behaviors. In YAML it looks like:

```yaml
worm:
  batch-size: 1000
  enable-schema-validation: true
```

You can also provide the bean manually if you need programmatic control:

```java
@Bean
public WormProperties wormProperties() {
    WormProperties props = new WormProperties();
    props.setBatchSize(1000);
    return props;
}
```

The native query registrar honors `QueryRepositoryProperties`. Override `worm.query.repository.base-packages` (defaults to `br.com.liviacare`) to point to your `@QueryRepository` interfaces:

```yaml
worm:
  query:
    repository:
      base-packages:
        - com.acme.repositories
        - br.com.liviacare.custom
```

## SQL Dialects

WORM provides optimized SQL generation for different databases:

- **PostgreSQL** (default) - Uses `RETURNING`, `ILIKE`, `::jsonb` casting
- **MySQL** - Uses `LIMIT/OFFSET`, `LIKE` with `LOWER()`, JSON functions

### Creating a Custom Dialect

```java
public class H2Dialect implements SqlDialect {
    @Override
    public String applyPagination(String sql, int limit, int offset) {
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }
    // ... other methods
}

@Bean
public SqlDialect sqlDialect() {
    return new H2Dialect();
}
```

## Performance Guide

WORM 1.0.6 ships five performance modules that activate automatically. Understanding how they work lets you tune them effectively.

### 1. Compiled Query Plan Cache

Every unique query *shape* (entity class + WHERE template + joins + ordering + pagination + flags) is compiled into a SQL string exactly once and stored in a static `ConcurrentHashMap`. Subsequent calls with the same shape return the cached string with no allocations.

**This means:** calling `Finder.all(User.class, filter)` in a hot loop costs only parameter binding, not SQL generation.

The cache is bounded by the number of distinct shapes in your application (typically dozens, not millions). Call `QueryPlanCache.clear()` if you need to flush it (e.g. in tests).

### 2. Zero-Allocation Row Mapping (MethodHandle spreaders)

When WORM builds `EntityMetadata` for a Java record, it pre-compiles the constructor as a `MethodHandle.asSpreader`. During row mapping, this eliminates the internal copy that `invokeWithArguments` performs internally.

**Result:** record construction from a `ResultSet` requires zero additional `Object[]` heap allocations beyond the single array built by the `EntityMapper`.

This is entirely automatic — no annotation or configuration required.

### 3. Smart Batch-Fetch for Collection Joins

By default, `@DbJoin` on a `List` field uses a `LEFT JOIN` that produces a Cartesian product when a parent has many children. For large collections this can inflate the result set significantly.

Use `fetchMode = DbJoin.FetchMode.BATCH` to switch to the two-query strategy:

```java
// Instead of one JOIN query with N×M rows:
@DbJoin(mappedBy = "order_id", fetchMode = DbJoin.FetchMode.BATCH)
private List<OrderItem> items;
```

WORM will:
1. Execute the parent `SELECT` with no collection join.
2. Collect the parent IDs.
3. Execute `SELECT * FROM order_items WHERE order_id IN (?, …)` in chunks of ≤ 1 000 IDs.
4. Group children by FK in memory and inject them into the parent records.

Choose `BATCH` when a parent typically has > 5–10 children, or when query plans for the JOIN are doing a hash join over a large intermediate result.

### 4. Static Metamodel Generator

See the [Static Metamodel](#static-metamodel) section. The metamodel reduces typos in column names at compile time and gives IDEs full auto-complete for every mapped column.

### 5. Universal Bulk SPI (`BulkWriter`)

All bulk operations now route through the `BulkWriter` interface obtained from `SqlDialect.createBulkWriter(dataSource, copyThreshold, unnestThreshold)`:

| Method | PostgreSQL strategy |
|--------|---------------------|
| `bulkInsert` | `COPY` above copy threshold, JDBC batch below |
| `bulkUpdate` | `unnest`-array UPDATE above unnest threshold |
| `bulkDelete` | `unnest`-array DELETE above unnest threshold |
| `bulkUpsert` | `INSERT … SELECT FROM unnest(…) ON CONFLICT (id) DO UPDATE` |

Other dialects return `null` from `createBulkWriter` (the ORM falls back to standard JDBC batch automatically).

#### Implementing a custom BulkWriter

```java
public class H2BulkWriter implements BulkWriter {
    @Override
    public <T> int[] bulkInsert(List<T> entities, EntityMetadata<T> meta) {
        // e.g. multi-row VALUES INSERT
        return null; // return null to signal "not applicable" → ORM uses JDBC batch
    }
    // bulkUpdate, bulkDelete, bulkUpsert ...
}

// Register it in your dialect:
public class H2Dialect implements SqlDialect {
    @Override
    public BulkWriter createBulkWriter(DataSource ds, int copyThreshold, int unnestThreshold) {
        return new H2BulkWriter(ds);
    }
}
```

### Performance configuration

```yaml
worm:
  bulk-copy-threshold: 20      # row count above which COPY is used for inserts (PostgreSQL)
  bulk-unnest-threshold: 10    # row count above which unnest is used for updates/deletes (PostgreSQL)
  batch-size: 1000             # chunk size for batch operations
```

## Architecture

### Key Components

- **OrmManager** - Core persistence engine
- **EntityMetadata** - Cached reflection metadata using MethodHandles (includes pre-compiled spreaders)
- **QueryBuilder** - Parameterized SQL query construction (results cached by `QueryPlanCache`)
- **QueryPlanCache** - Static `ConcurrentHashMap` keyed by query shape; bounds SQL generation to once per shape
- **EntityMapper** - ResultSet to entity conversion using zero-allocation spreader invocation
- **EntityPersister** - Parameter binding for INSERT/UPDATE
- **SqlExecutor** - JDBC execution with metrics
- **BatchFetchExecutor** - Two-query batch strategy for `@DbJoin(fetchMode = BATCH)` collection fields
- **BulkWriter** - SPI interface for driver-level bulk insert/update/delete/upsert
- **PostgresBulkWriter** - PostgreSQL implementation using `COPY`, `unnest` arrays, and `ON CONFLICT DO UPDATE`
- **WormAttribute** - Typed column descriptor record used by the metamodel and `FilterBuilder`
- **WormMetamodelProcessor** - APT processor generating `{Entity}_.java` static metamodel classes

### Design Principles

1. **Type Safety** - Compile-time checked queries via static metamodel
2. **Zero Reflection** - Uses MethodHandles with pre-compiled spreaders for maximum performance
3. **SQL Control** - Generate readable, auditable SQL
4. **Spring Integration** - Seamless transaction and bean management
5. **Extensibility** - SPI for custom behaviors (dialects, bulk writers)

## Module Routing & Multi-Tenancy

For module-level data isolation:

```java
@DbTable(value = "users", module = "tenant_a")
public class User { ... }

// OrmManager routes to correct DataSource based on module
```

See the SPI package for ModuleContext integration details.

## Limitations & Non-Features

WORM intentionally does **not** provide:

- ❌ Complex relationship loading (N+1 query problems are app-level)
- ❌ Lazy loading proxies (all data is loaded eagerly)
- ❌ Criteria API (use FilterBuilder instead)
- ❌ HQL/JPQL (use native SQL with FilterBuilder)
- ❌ Entity change tracking (explicit save/update calls required)

## Build & Testing

```bash
# Clone the repository
git clone https://github.com/liviacare/worm.git
cd worm

# Build with Maven
./mvnw clean package

# Install locally
./mvnw install
```

### Testing

```bash
# Run all tests
./mvnw test

# Skip tests during build
./mvnw clean package -DskipTests
```

### Distribution Artifacts

When you build the project, you get:

- `worm-1.0.6.jar` - Main library (149 KB)
- `worm-1.0.6-sources.jar` - Source code (79 KB)
- `worm-1.0.6-javadoc.jar` - API documentation (4.4 MB)

All artifacts are self-contained with no JPA/Hibernate dependencies.

## Contributing

Contributions are welcome! Please:

1. Follow the existing code style
2. Maintain method names and business rules as-is
3. Add unit tests for new features
4. Update documentation

## License

MIT License - See LICENSE file for details

## Publishing

WORM is published as a Maven library on **GitHub Packages**.

### Installation

Add the repository and dependency to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/rfdetoni/worm</url>
  </repository>
</repositories>

<dependency>
  <groupId>br.com.liviacare</groupId>
  <artifactId>worm</artifactId>
  <version>1.0.6</version>
</dependency>
```

### Authentication

Update your `~/.m2/settings.xml` to authenticate with GitHub Packages:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_PERSONAL_ACCESS_TOKEN</password>
    </server>
  </servers>
</settings>
```

📖 **[See full publishing guide →](./PUBLISHING.md)**

## Support & Documentation

- 📖 [Full API Documentation](./target/worm-1.0.6-javadoc.jar)
- 🐛 [Issue Tracker](https://github.com/liviacare/worm/issues)
- 💬 [Discussions](https://github.com/liviacare/worm/discussions)

## Changelog

### Version 1.0.2 (2026-03-23)
- ✅ Compiled Query Plan Cache — SQL built once per shape, reused on all subsequent calls
- ✅ Zero-Allocation Row Mapping — `MethodHandle.asSpreader` eliminates intermediate `Object[]` copies for records
- ✅ Smart Batch-Fetch — `@DbJoin(fetchMode = FetchMode.BATCH)` avoids Cartesian-product JOINs for large collections
- ✅ Static Metamodel Generator — `WormMetamodelProcessor` generates `{Entity}_.java` with typed `WormAttribute` constants
- ✅ Universal Bulk SPI — `BulkWriter` interface + `SqlDialect.createBulkWriter()` factory; PostgreSQL ships `COPY / unnest / ON CONFLICT DO UPDATE`
- ✅ `FilterBuilder` typed overloads — all comparison predicates accept `WormAttribute<E,V>` for compile-time safety
- ✅ `FilterBuilder.rawWhere(String, List<Object>)` — escape hatch for custom SQL fragments

### Version 1.0.1 (2026-03-23)
- ✅ Initial release
- ✅ Core ORM functionality
- ✅ Spring Boot auto-configuration
- ✅ PostgreSQL and MySQL support
- ✅ Comprehensive annotation support
- ✅ Soft delete and optimistic locking
- ✅ JSON column support
- ✅ Batch operations
- ✅ Module routing SPI

---

**Built with ❤️ by Livia Care**

