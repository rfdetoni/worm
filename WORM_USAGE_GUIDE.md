# WORM Usage Guide

A comprehensive English guide to modeling, querying, and persisting data with WORM.

## Table of Contents

- [1. What WORM is](#1-what-worm-is)
- [2. Installation and bootstrapping](#2-installation-and-bootstrapping)
- [3. The main ways to use WORM](#3-the-main-ways-to-use-worm)
- [4. Entity modeling](#4-entity-modeling)
- [5. CRUD operations](#5-crud-operations)
- [6. Querying with Finder and ClassFinder](#6-querying-with-finder-and-classfinder)
- [7. Querying with OrmOperations](#7-querying-with-ormoperations)
- [8. Filtering with FilterBuilder](#8-filtering-with-filterbuilder)
- [9. Pagination, slices, and pages](#9-pagination-slices-and-pages)
- [10. Projections and column queries](#10-projections-and-column-queries)
- [11. Joins with `@DbJoin`](#11-joins-with-dbjoin)
- [12. Native SQL and raw execution](#12-native-sql-and-raw-execution)
- [13. JSON and JSONPath queries](#13-json-and-jsonpath-queries)
- [14. Query repositories](#14-query-repositories)
- [15. Repository wrappers with `GenericRepository`](#15-repository-wrappers-with-genericrepository)
- [16. Batch and bulk operations](#16-batch-and-bulk-operations)
- [17. Dirty tracking with `@Track`](#17-dirty-tracking-with-track)
- [18. Auditing and `iBaseEntity`](#18-auditing-and-ibaseentity)
- [19. Soft delete](#19-soft-delete)
- [20. Optimistic locking](#20-optimistic-locking)
- [21. Multi-module and routing support](#21-multi-module-and-routing-support)
- [22. Configuration](#22-configuration)
- [23. Best practices](#23-best-practices)
- [24. Common patterns](#24-common-patterns)
- [25. Quick API map](#25-quick-api-map)
- [26. Static metamodel and type-safe filters](#26-static-metamodel-and-type-safe-filters)
- [27. Performance guide](#27-performance-guide)
- [28. `@DbJoin` relationships with ActiveRecord](#28-dbjoin-relationships-with-activerecord)

---

## 1. What WORM is

WORM is a lightweight Java ORM focused on:

- explicit SQL-oriented persistence;
- Spring Boot integration without JPA/Hibernate;
- type-safe entity mapping;
- fast metadata access through cached handles;
- practical querying through `FilterBuilder`;
- batch and bulk write support;
- projections, joins, JSON helpers, and native SQL.

WORM is **not** JPA and does **not** try to emulate JPA behavior.

That means you should **not** assume:

- session-based dirty checking;
- persistence context magic;
- lazy-loading proxies;
- implicit flushes;
- JPQL/HQL-style abstractions.

Instead, WORM is explicit:

- you model entities with annotations;
- you call `save()`, `update()`, `delete()` yourself;
- you query through `Finder`, `ActiveRecord`, `OrmOperations`, repositories, or native SQL.

---

## 2. Installation and bootstrapping

### Maven dependency

```xml
<dependency>
  <groupId>br.com.liviacare</groupId>
  <artifactId>worm</artifactId>
  <version>1.0.6</version>
</dependency>
```

### Spring Boot auto-configuration

WORM auto-configures itself when present on the classpath.

### Optional explicit enablement

If you prefer explicit opt-in:

```java
import br.com.liviacare.worm.annotation.EnableWorm;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableWorm
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

---

## 3. The main ways to use WORM

WORM supports multiple usage styles. You can use one consistently or mix them when it makes sense.

### 3.1 ActiveRecord style

Best when you want minimal boilerplate and entity-centric operations.

```java
@DbTable("users")
public class User extends ActiveRecord<User, UUID> {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;

    @DbColumn("email")
    private String email;

    @Override
    public UUID getId() {
        return id;
    }

    // getters/setters
}
```

Usage:

```java
User user = new User();
user.setId(UUID.randomUUID());
user.setName("Alice");
user.setEmail("alice@example.com");
user.save();

user.setName("Alice Doe");
user.update();
user.delete();
```

### 3.2 Finder style

Best when you want query helpers without putting everything on the entity itself.

```java
Optional<User> found = Finder.byId(User.class, id);
List<User> all = Finder.all(User.class);
Slice<User> page = Finder.all(User.class, FilterBuilder.create().eq("active", true), Pageable.of(0, 20));
```

### 3.3 ClassFinder style

Best when you want a fluent class-scoped gateway with alias support.

```java
ClassFinder<User> users = Finder.of(User.class).alias("u");
Optional<User> one = users.byId(id);
List<User> active = users.all(FilterBuilder.create().eq("u.active", true));
```

### 3.4 `OrmOperations` style

Best when you want the full framework surface in services or infrastructure code.

```java
@Service
public class UserService {
    private final OrmOperations orm;

    public UserService(OrmOperations orm) {
        this.orm = orm;
    }

    public Optional<User> findById(UUID id) {
        return orm.findById(User.class, id);
    }
}
```

### 3.5 Repository style

Best when your team prefers explicit repository classes or interfaces.

Options:

- `GenericRepository<T, ID>` as a wrapper around WORM;
- query repositories with `@QueryRepository` + `@Query`;
- your own service/repository layer delegating to `OrmOperations`.

---

## 4. Entity modeling

### 4.1 Minimum entity

Every persisted entity needs:

- `@DbTable` on the class;
- `@DbId` on the identifier field.

```java
@DbTable("users")
public class User {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;
}
```

### 4.2 Mapping annotations

Common annotations:

- `@DbTable` — maps a class to a table;
- `@DbId` — marks the primary key;
- `@DbColumn` — maps a field to a column;
- `@DbJoin` — declares joined entities/collections;
- `@DbVersion` — enables optimistic locking;
- `@Track` — opt-in dirty tracking and partial updates;
- `@OrderBy` — default ordering;
- `@CreatedAt`, `@UpdatedAt`, `@CreatedBy`, `@UpdatedBy` — auditing;
- `@Active`, `@DeletedAt` — soft delete.

### 4.3 Regular classes vs records

WORM supports both regular classes and records.

#### Regular class example

```java
@DbTable("departments")
public class Department {
    @DbId("id")
    private Long id;

    @DbColumn("name")
    private String name;

    public Department() {
    }

    // getters/setters
}
```

#### Record example

```java
@DbTable("departments")
public record DepartmentRecord(
    @DbId("id") Long id,
    @DbColumn("name") String name
) {}
```

### 4.4 Column expressions and JSON columns

`@DbColumn` supports expressions and JSON markers.

```java
@DbTable("profiles")
public class Profile {
    @DbId("id")
    private UUID id;

    @DbColumn(value = "state", expr = "address->>'state'")
    private String state;

    @DbColumn(value = "preferences", json = true)
    private Map<String, Object> preferences;
}
```

### 4.5 Table schema and module routing

`@DbTable` also supports schema and module.

```java
@DbTable(value = "users", schema = "public", module = "tenant_a")
public class TenantUser {
    @DbId("id")
    private UUID id;
}
```

---

## 5. CRUD operations

### 5.1 Using `Persistable`

If your entity implements or inherits `Persistable`, you get instance methods.

```java
public class User extends ActiveRecord<User, UUID> {
    // ...
}

user.save();
user.update();
```

Static helpers also exist:

```java
Persistable.save(user);
Persistable.update(user);
Persistable.saveAll(users);
Persistable.updateAll(users);
```

### 5.2 Using `Deletable`

If your entity implements or inherits `Deletable`, you get delete support.

```java
user.delete();
Deletable.deleteById(User.class, id);
Deletable.deleteAll(users);
```

### 5.3 Using `ActiveRecord` static helpers

```java
ActiveRecord.save(user);
ActiveRecord.update(user);
ActiveRecord.saveAll(users);
ActiveRecord.updateAll(users);
ActiveRecord.deleteById(User.class, id);
```

### 5.4 Using `OrmOperations`

```java
orm.save(user);
orm.update(user);
orm.delete(user);
orm.deleteById(User.class, id);
```

---

## 6. Querying with Finder and ClassFinder

### 6.1 Finder static helpers

```java
Optional<User> one = Finder.byId(User.class, id);
List<User> all = Finder.all(User.class);
List<User> filtered = Finder.all(User.class, FilterBuilder.create().eq("active", true));
Slice<User> slice = Finder.all(User.class, FilterBuilder.create().eq("active", true), Pageable.of(0, 20));
```

### 6.2 Finder as an entity mixin

If the entity extends `ActiveRecord`, it already implements `Finder`.

```java
User probe = new User();
Optional<User> one = probe.byId(id);
List<User> users = probe.all(FilterBuilder.create().like("name", "%Ali%"));
long count = probe.count();
boolean exists = probe.exists();
```

### 6.3 Class-level gateway with `ActiveRecord.ar(...)`

```java
ActiveRecord.EntityOps<User, UUID> users = ActiveRecord.ar(User.class);
Optional<User> one = users.byId(id);
List<User> all = users.all();
Slice<User> page = users.all(Pageable.of(0, 20));
```

### 6.4 Classic `find` style

```java
@DbTable("users")
public class User extends ActiveRecord<User, UUID> {
    public static final Finder<User, UUID> find = ActiveRecord.find(User.class);

    @DbId("id")
    private UUID id;

    @Override
    public UUID getId() {
        return id;
    }
}

Optional<User> one = User.find.byId(id);
List<User> all = User.find.all();
```

### 6.5 `ClassFinder` with alias support

```java
ClassFinder<User> users = Finder.of(User.class).alias("u");

List<User> result = users.all(
    FilterBuilder.create()
        .eq("u.active", true)
        .orderBy("u.created_at", false)
);
```

---

## 7. Querying with OrmOperations

`OrmOperations` exposes the full read/write/query API.

### 7.1 Basic reads

```java
Optional<User> byId = orm.findById(User.class, id);
Optional<User> first = orm.findOne(User.class, FilterBuilder.create().eq("email", "alice@example.com"));
List<User> all = orm.findAll(User.class, FilterBuilder.create().eq("active", true));
```

### 7.2 Aggregates and existence

```java
boolean exists = orm.exists(User.class, FilterBuilder.create().eq("active", true));
long count = orm.count(User.class, FilterBuilder.create().eq("active", true));
Optional<Long> total = orm.sum(Order.class, "amount", Long.class, FilterBuilder.create());
Optional<Double> avg = orm.avg(Order.class, "amount", FilterBuilder.create());
```

### 7.3 Page API

```java
Page<User> page = orm.findPage(
    User.class,
    FilterBuilder.create().eq("active", true),
    Pageable.of(0, 20)
);
```

### 7.4 CTE-based queries

```java
FilterBuilder filter = FilterBuilder.create()
    .withCte("recent_users", "select * from users where created_at >= now() - interval '7 days'")
    .eq("active", true);

List<User> users = orm.findAllWithCte(User.class, filter);
```

---

## 8. Filtering with FilterBuilder

`FilterBuilder` is the core fluent query builder in WORM.

### 8.1 Creating a filter

```java
FilterBuilder filter = FilterBuilder.create()
    .eq("status", "ACTIVE")
    .gte("created_at", start)
    .orderBy("created_at", false);
```

Equivalent factories:

```java
FilterBuilder a = new FilterBuilder();
FilterBuilder b = FilterBuilder.create();
FilterBuilder c = FilterBuilder.where();
FilterBuilder d = FilterBuilder.empty();
```

### 8.2 Comparison predicates

Available methods include:

- `eq`, `eqIfNotNull`
- `neq`, `neqIfNotNull`
- `gt`, `gtIfNotNull`
- `lt`, `ltIfNotNull`
- `gte`, `gteIfNotNull`
- `lte`, `lteIfNotNull`
- `like`, `likeIfNotBlank`
- `in`, `inIfNotEmpty`
- `isNull`, `isNotNull`

All of the above accept either a plain `String` column name (classic) **or** a typed `WormAttribute<E,V>` descriptor from the static metamodel (see [Section 26](#26-static-metamodel-and-type-safe-filters)):

```java
// Classic — string column names
FilterBuilder filter = FilterBuilder.create()
    .eq("active", true)
    .gte("age", 18)
    .lte("age", 65)
    .like("name", "%Ali%")
    .in("role", List.of("ADMIN", "MANAGER"));

// Type-safe — WormAttribute constants generated at compile time
FilterBuilder filter = FilterBuilder.create()
    .eq(User_.active, true)          // compile-time type: Boolean
    .gte(User_.age, 18)              // compile-time type: Integer
    .lte(User_.age, 65)
    .like(User_.name, "%Ali%")       // compile-time type: String
    .in(User_.role, List.of("ADMIN", "MANAGER"));
```

### 8.3 OR groups and parentheses

```java
FilterBuilder filter = FilterBuilder.create()
    .openParen()
        .eq("status", "PENDING")
        .or()
        .eq("status", "RETRY")
    .closeParen()
    .eq("active", true);
```

### 8.4 Ordering and grouping

```java
FilterBuilder filter = FilterBuilder.create()
    .eq("active", true)
    .groupBy("department_id")
    .orderBy("created_at", false);
```

Also available:

- `orderBy(String column)`
- `orderByDesc(String column)`
- `orderBy(WormAttribute<?,?> attr)` — type-safe, uses generated column name
- `orderByDesc(WormAttribute<?,?> attr)` — type-safe descending
- `orderBy(Pageable.Sort sort)`
- `orderByRaw(String clause)`
- `groupBy(String... columns)`
- `groupByRaw(String clause)`

### 8.5 Ignore soft delete filters

If you need to bypass automatic soft-delete filtering:

```java
FilterBuilder filter = FilterBuilder.create()
    .ignoreSoftDelete()
    .eq("id", id);
```

### 8.6 Main alias and join control

```java
FilterBuilder filter = FilterBuilder.create()
    .alias("u")
    .eq("u.active", true)
    .orderBy("u.created_at", false);
```

To suppress joins:

```java
FilterBuilder filter = FilterBuilder.create().notJoin();
```

### 8.7 Ad-hoc joins in filters

```java
FilterBuilder filter = FilterBuilder.create()
    .leftJoin("departments", "d", "d.id = a.department_id")
    .eq("d.name", "Engineering");
```

### 8.8 CTEs and window functions

```java
FilterBuilder filter = FilterBuilder.create()
    .withCte("active_users", "select * from users where active = true")
    .addWindowFunction("row_number() over (partition by department_id order by created_at desc)", "rn");
```

### 8.9 Raw WHERE fragments

When no built-in predicate fits, append a hand-written SQL clause and its bind parameters:

```java
FilterBuilder filter = FilterBuilder.create()
    .rawWhere("age BETWEEN ? AND ?", List.of(18, 65))
    .rawWhere("lower(email) LIKE lower(?)", List.of("%@example.com"));
```

The fragment is appended as-is with an implicit `AND` to any preceding conditions. Bind values are appended in the same order as the `?` placeholders.

> **Use sparingly.** Prefer typed predicates when possible — `rawWhere` bypasses column-name validation.

---

## 9. Pagination, slices, and pages

### 9.1 `Pageable`

```java
Pageable firstPage = Pageable.of(0, 20);
Pageable sorted = Pageable.of(0, 20, Pageable.Sort.desc("created_at"));
```

### 9.2 `Slice`

Use a `Slice` when you need page-like navigation without a total count.

```java
Slice<User> slice = orm.findAll(
    User.class,
    FilterBuilder.create().eq("active", true),
    Pageable.of(0, 20)
);

List<User> content = slice.content();
boolean hasNext = slice.hasNext();
```

### 9.3 `Page`

Use `findPage` when you need totals.

```java
Page<User> page = orm.findPage(
    User.class,
    FilterBuilder.create().eq("active", true),
    Pageable.of(0, 20)
);

long total = page.totalElements();
int pages = page.totalPages();
```

---

## 10. Projections and column queries

### 10.1 Projection records

WORM supports projection classes, especially records.

```java
public record UserSummary(UUID id, String name, String email) {}

List<UserSummary> summaries = orm.findAll(
    User.class,
    FilterBuilder.create().eq("active", true),
    UserSummary.class
);

Optional<UserSummary> first = orm.findOne(
    User.class,
    FilterBuilder.create().eq("email", "alice@example.com"),
    UserSummary.class
);
```

### 10.2 Projection by ID

```java
Optional<UserSummary> one = orm.findById(User.class, id, UserSummary.class);
```

### 10.3 Single-column queries

With `OrmOperations`:

```java
List<String> names = orm.findColumn(User.class, "name", String.class, FilterBuilder.create().eq("active", true));
Optional<String> firstName = orm.findColumnOne(User.class, "name", String.class, FilterBuilder.create().eq("id", id));
```

With `Finder` / `ActiveRecord` / `ClassFinder`:

```java
List<String> names = Finder.findColumn(User.class, "name", String.class);
Optional<String> oneName = ActiveRecord.findColumnOne(User.class, "name", String.class);
```

---

## 11. Joins with `@DbJoin`

WORM supports convention-based and explicit joins.

### 11.1 Convention-based join

```java
@DbTable("users")
public class User {
    @DbId("id")
    private UUID id;

    @DbJoin
    private Department department;
}
```

### 11.2 Join with local foreign key

```java
@DbJoin(localColumn = "department_id")
private Department department;
```

### 11.3 Join with custom target column

```java
@DbJoin(localColumn = "department_code", targetColumn = "code")
private Department department;
```

### 11.4 Collection join with `mappedBy`

```java
@DbJoin(mappedBy = "owner_id")
private List<Order> orders;
```

### 11.5 Fully explicit join

```java
@DbJoin(
    table = "departments",
    alias = "d",
    on = "d.id = a.department_id",
    type = DbJoin.Type.LEFT
)
private Department department;
```

### 11.6 Filter-level joins

Even if the entity has `@DbJoin`, you can also add ad-hoc joins through `FilterBuilder`.

```java
FilterBuilder filter = FilterBuilder.create()
    .leftJoin("departments", "d", "d.id = a.department_id")
    .eq("d.active", true);
```

### 11.7 Controlling collection fetch strategy with `fetchMode`

By default, a collection `@DbJoin` generates a `LEFT JOIN` that produces one row per (parent, child) combination. For parents with large collections this inflates the result set significantly ("Cartesian explosion").

Use `fetchMode = DbJoin.FetchMode.BATCH` to replace the JOIN with two targeted queries:

```java
// Default JOIN strategy — one query, Cartesian result set
@DbJoin(mappedBy = "order_id")
private List<OrderItem> items;

// BATCH strategy — two queries, no Cartesian product
@DbJoin(mappedBy = "order_id", fetchMode = DbJoin.FetchMode.BATCH)
private List<OrderItem> items;
```

**How BATCH works internally:**

1. The parent query runs with the collection join suppressed.
2. All parent IDs are collected.
3. WORM executes `SELECT * FROM order_items WHERE order_id IN (?, …)` chunked to ≤ 1 000 IDs per round-trip.
4. Children are grouped by FK in memory and injected into each parent.

**When to use BATCH:**

| Scenario | Recommended strategy |
|----------|---------------------|
| Collection has ≤ 5 items on average | `JOIN` (default) |
| Collection has > 5–10 items on average | `BATCH` |
| Reporting query that needs a single SQL plan | `JOIN` |
| Hot-path API loading a list of parents with deep children | `BATCH` |

**Available `FetchMode` values:**

| Value | Description |
|-------|-------------|
| `FetchMode.JOIN` | Standard `LEFT JOIN` + in-memory merge (default). |
| `FetchMode.BATCH` | Two-query `IN`-clause batch. Only effective on `List` / `Collection` fields. |

> **Note** `fetchMode = BATCH` on a non-collection (scalar) field is silently ignored — the default `JOIN` strategy is used instead.

---

## 12. Native SQL and raw execution

### 12.1 Raw execution mapped to an entity

```java
List<User> users = orm.executeRaw(
    "select * from users where active = ?",
    User.class,
    true
);
```

### 12.2 Raw execution mapped to a DTO or record

```java
public record UserDto(UUID id, String name) {}

List<UserDto> users = orm.executeRaw(
    "select id, name from users where active = ?",
    UserDto.class,
    true
);
```

### 12.3 Finder native query helpers

```java
List<UserDto> users = Finder.nativeQueryList(
    "select id, name from users where active = ?",
    UserDto.class,
    true
);
```

---

## 13. JSON and JSONPath queries

WORM provides JSON helpers both in `FilterBuilder` and `OrmOperations` / `Finder` / `ClassFinder`.

### 13.1 JSON filter helpers

Available methods include:

- `jsonTextEq`
- `jsonTextEqPath`
- `jsonHasKey`
- `jsonHasAny`
- `jsonContains`
- `jsonPathExists`
- `jsonPathExistsWithVars`

Example:

```java
FilterBuilder filter = FilterBuilder.create()
    .jsonTextEq("preferences", "theme", "dark")
    .jsonHasKey("preferences", "language")
    .jsonPathExists("preferences", "$.notifications ? (@ == true)");
```

### 13.2 JSON path read helpers

```java
Optional<String> firstValue = orm.jsonPathQueryFirst(
    User.class,
    "preferences",
    "$.theme",
    FilterBuilder.create().eq("id", id)
);

Optional<String> arrayResult = orm.jsonPathQueryArray(
    User.class,
    "preferences",
    "$.tags[*]",
    FilterBuilder.create().eq("id", id)
);
```

With variables:

```java
Optional<String> value = orm.jsonPathQueryFirstWithVars(
    User.class,
    "preferences",
    "$.settings ? (@.locale == $locale)",
    "{\"locale\":\"en_US\"}",
    FilterBuilder.create().eq("id", id)
);
```

---

## 14. Query repositories

Query repositories let you define native SQL directly on interfaces.

### 14.1 Manual creation

```java
public interface UserQueryRepository {
    @Query("select * from users where active = :active")
    List<User> findAllActive(@QueryParam("active") Boolean active);

    @Query("select * from users where id = :id")
    Optional<User> findById(@QueryParam("id") UUID id);

    @Query("select * from users order by created_at desc")
    Slice<User> findRecent(Pageable pageable);
}
```

```java
UserQueryRepository repo = QueryRepositoryFactory.create(UserQueryRepository.class);
List<User> active = repo.findAllActive(true);
```

### 14.2 Spring auto-detection

```java
@QueryRepository
public interface UserQueryRepository {
    @Query("select * from users where active = :active")
    List<User> findAllActive(@QueryParam("active") Boolean active);
}
```

Configuration:

```yaml
worm:
  query:
    repository:
      base-packages:
        - com.myapp.query
        - br.com.liviacare.custom
```

### 14.3 Supported return types

Common return types:

- `List<T>`
- `Optional<T>`
- `Slice<T>`

---

## 15. Repository wrappers with `GenericRepository`

If your team prefers class-based repositories:

```java
@Repository
public class UserRepository extends GenericRepository<User, UUID> {
    public UserRepository(OrmOperations orm) {
        super(User.class, orm);
    }
}
```

Usage:

```java
userRepository.save(user);
userRepository.update(user);
userRepository.delete(user);
Optional<User> one = userRepository.findById(id);
List<User> active = userRepository.findAll(FilterBuilder.create().eq("active", true));
```

Column helpers:

```java
List<String> names = userRepository.findColumn("name", String.class);
Optional<String> firstName = userRepository.findColumnOne("name", String.class);
```

Batch save helper:

```java
userRepository.saveAll(users);
userRepository.saveAll(users, 1000);
```

---

## 16. Batch and bulk operations

WORM supports batch-oriented APIs at the `OrmOperations` level.

### 16.1 Batch write methods

```java
orm.saveAll(users);
orm.saveAllBatch(users);
orm.updateAll(users);
orm.updateAllBatch(users);
orm.deleteAll(users);
orm.deleteAllBatch(users);
orm.upsertAll(users);
orm.upsertAllBatch(users);
```

### 16.2 When to use them

Use batch methods when:

- you already have a list of entities;
- you want fewer round trips;
- you want chunked execution under WORM control;
- you want to take advantage of PostgreSQL bulk paths when configured.

### 16.3 PostgreSQL bulk paths (BulkWriter SPI)

All bulk operations route through the `BulkWriter` interface, obtained at startup via
`SqlDialect.createBulkWriter(dataSource, copyThreshold, unnestThreshold)`.

**PostgreSQL strategies:**

| Operation | Strategy | Activation threshold |
|-----------|----------|----------------------|
| `saveAllBatch` / `bulkInsert` | PostgreSQL `COPY` protocol | `worm.bulk-copy-threshold` (default 20) |
| `updateAllBatch` / `bulkUpdate` | `unnest`-array `UPDATE` | `worm.bulk-unnest-threshold` (default 10) |
| `deleteAllBatch` / `bulkDelete` | `unnest`-array `DELETE` | `worm.bulk-unnest-threshold` |
| `upsertAllBatch` / `bulkUpsert` | `INSERT … SELECT FROM unnest(…) ON CONFLICT (id) DO UPDATE` | `worm.bulk-copy-threshold` |

Below the threshold WORM falls back to a standard JDBC batch. Other dialects return `null` from `createBulkWriter` and always use JDBC batch.

### 16.4 Implementing a custom BulkWriter

Provide a custom bulk strategy by implementing `BulkWriter` and registering it in a custom `SqlDialect`:

```java
public class MyBulkWriter implements BulkWriter {

    @Override
    public <T> int[] bulkInsert(List<T> entities, EntityMetadata<T> meta) {
        // driver-specific fast path, or return null to fall back to JDBC batch
        return null;
    }

    @Override
    public <T> int[] bulkUpdate(List<T> entities, EntityMetadata<T> meta) { return null; }

    @Override
    public <T> int[] bulkDelete(List<T> entities, EntityMetadata<T> meta) { return null; }

    @Override
    public <T> int[] bulkUpsert(List<T> entities, EntityMetadata<T> meta) { return null; }
}

public class MyDialect implements SqlDialect {

    @Override
    public BulkWriter createBulkWriter(DataSource ds, int copyThreshold, int unnestThreshold) {
        return new MyBulkWriter(ds);
    }

    // ... other dialect methods
}

@Bean
public SqlDialect sqlDialect() {
    return new MyDialect();
}
```

Returning `null` from any `BulkWriter` method signals "strategy not applicable" — WORM will fall back to standard JDBC batch for that operation automatically.

### 16.5 Important note

Real batch execution depends on a resolvable `DataSource`.
WORM is designed to fail instead of silently degrading when a true batch path is required.

---

## 17. Dirty tracking with `@Track`

Dirty tracking is **opt-in**, not global.

### 17.1 What `@Track` does

When an entity has `@Track`:

- WORM captures a snapshot on reads;
- WORM refreshes the snapshot after writes;
- `update()` can emit a partial `UPDATE` containing only changed columns;
- `updatedAt` is still included when relevant.

### 17.2 Example tracked entity

```java
@Track
@DbTable("users")
public class User extends ActiveRecord<User, UUID> {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;

    @DbColumn("email")
    private String email;

    @UpdatedAt
    private Instant updatedAt;

    @Override
    public UUID getId() {
        return id;
    }
}
```

### 17.3 Best usage pattern

To benefit from tracking, prefer:

```java
User user = orm.findById(User.class, id).orElseThrow();
user.setName("New Name");
user.update();
```

Recommended mental model:

- `load -> modify -> update`

### 17.4 When not to use `@Track`

Avoid it when:

- the entity is usually rewritten as a whole;
- the entity is trivial;
- the flow mostly builds disconnected instances without prior reads.

---

## 18. Auditing and `iBaseEntity`

### 18.1 Audit annotations

You can model audit columns with:

- `@CreatedAt`
- `@UpdatedAt`
- `@CreatedBy`
- `@UpdatedBy`

Example:

```java
@DbTable("users")
public class User {
    @DbId("id")
    private UUID id;

    @CreatedAt
    private Instant createdAt;

    @UpdatedAt
    private Instant updatedAt;

    @CreatedBy
    private String createdBy;

    @UpdatedBy
    private String updatedBy;
}
```

### 18.2 `iBaseEntity` lifecycle hooks

If you want explicit hook callbacks during persistence operations, implement `iBaseEntity`.

```java
@DbTable("users")
public class User extends ActiveRecord<User, UUID> implements iBaseEntity {
    @DbId("id")
    private UUID id;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    @Override
    public void created() {
        this.createdAt = Instant.now();
    }

    @Override
    public void updated() {
        this.updatedAt = Instant.now();
    }

    @Override
    public void deleted() {
        this.deletedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }
}
```

---

## 19. Soft delete

WORM supports logical deletes through:

- `@Active`
- `@DeletedAt`

### 19.1 Active-flag soft delete

```java
@DbTable("users")
public class User {
    @DbId("id")
    private UUID id;

    @Active
    private Boolean active;
}
```

### 19.2 Timestamp soft delete

```java
@DbTable("users")
public class User {
    @DbId("id")
    private UUID id;

    @DeletedAt
    private Instant deletedAt;
}
```

### 19.3 Behavior

When soft delete is configured, `delete()` does not physically remove the row. Instead, WORM updates the active/timestamp column.

If you need to query deleted rows too, use:

```java
FilterBuilder.create().ignoreSoftDelete();
```

---

## 20. Optimistic locking

Use `@DbVersion` to protect updates against concurrent writes.

```java
@DbTable("users")
public class User {
    @DbId("id")
    private UUID id;

    @DbVersion
    private long version;
}
```

Behavior:

- WORM includes the version in update checks;
- if no row is updated, an optimistic locking exception is raised;
- partial updates with `@Track` still preserve version semantics.

---

## 21. Multi-module and routing support

You can route persistence by module using `@DbTable(module = "...")`.

```java
@DbTable(value = "users", module = "tenant_a")
public class TenantUser {
    @DbId("id")
    private UUID id;
}
```

This is useful for:

- tenant-based routing;
- bounded contexts with separate data sources;
- module-aware persistence.

---

## 22. Configuration

WORM uses `worm.*` configuration properties.

### 22.1 Example YAML

```yaml
worm:
  batch-size: 500
  enable-schema-validation: false
  save-try-update-first: true
  transaction-enabled: true
  insert-strategy: UPSERT
  bulk-copy-threshold: 20
  bulk-unnest-threshold: 10
```

### 22.2 Key properties

- `worm.batch-size` — chunk size for batch operations;
- `worm.enable-schema-validation` — validate schema at startup;
- `worm.save-try-update-first` — behavior for `save()` with IDs;
- `worm.transaction-enabled` — wraps single-row writes in a transaction template when available;
- `worm.insert-strategy` — `UPSERT`, `TRY_UPDATE`, or `INSERT_ONLY`;
- `worm.bulk-copy-threshold` — PostgreSQL bulk insert threshold;
- `worm.bulk-unnest-threshold` — PostgreSQL bulk update/delete threshold.

### 22.3 Programmatic configuration

```java
@Bean
public WormProperties wormProperties() {
    WormProperties props = new WormProperties();
    props.setBatchSize(1000);
    props.setInsertStrategy(WormProperties.InsertStrategy.UPSERT);
    return props;
}
```

---

## 23. Best practices

### 23.1 Choose one main style per module

You can mix APIs, but teams usually benefit from a dominant style:

- `ActiveRecord` for simple app/domain layers;
- `OrmOperations` for services and advanced querying;
- repositories for strict layering.

### 23.2 Use `@Track` selectively

Apply it where partial updates bring real value.
Do not treat it as a global default.

### 23.3 Prefer explicit read-modify-write for tracked entities

```java
User user = orm.findById(User.class, id).orElseThrow();
user.setEmail("new@example.com");
orm.update(user);
```

### 23.4 Keep filters readable

Good:

```java
FilterBuilder.create()
    .eq("active", true)
    .gte("created_at", start)
    .orderBy("created_at", false);
```

Avoid huge unreadable chains when they should be split into variables.

### 23.5 Use projections for read-heavy queries

If a screen only needs a few columns, prefer projection records over loading the full entity graph.

### 23.6 Use batch APIs for volume

Prefer WORM batch methods before writing manual JDBC loops.

### 23.7 Preserve framework semantics

Do not reintroduce JPA assumptions into WORM code.

---

## 24. Common patterns

### Pattern A — Simple CRUD with ActiveRecord

```java
@DbTable("users")
public class User extends ActiveRecord<User, UUID> {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;

    @Override
    public UUID getId() {
        return id;
    }
}

User user = new User();
user.setId(UUID.randomUUID());
user.setName("Alice");
user.save();
```

### Pattern B — Service-layer querying with OrmOperations

```java
@Service
public class UserQueryService {
    private final OrmOperations orm;

    public UserQueryService(OrmOperations orm) {
        this.orm = orm;
    }

    public List<User> findActiveUsers() {
        return orm.findAll(User.class, FilterBuilder.create().eq("active", true));
    }
}
```

### Pattern C — Repository wrapper

```java
@Repository
public class UserRepository extends GenericRepository<User, UUID> {
    public UserRepository(OrmOperations orm) {
        super(User.class, orm);
    }
}
```

### Pattern D — Native SQL repository interface

```java
@QueryRepository
public interface UserQueryRepository {
    @Query("select id, name from users where active = :active")
    List<UserSummary> findSummaries(@QueryParam("active") Boolean active);
}
```

### Pattern E — Tracked partial updates

```java
@Track
@DbTable("users")
public class User extends ActiveRecord<User, UUID> {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;

    @UpdatedAt
    private Instant updatedAt;

    @Override
    public UUID getId() {
        return id;
    }
}

User user = orm.findById(User.class, id).orElseThrow();
user.setName("Alice Doe");
user.update();
```

---

## 25. Quick API map

### Entity-side APIs

- `save()`
- `update()`
- `delete()`
- `byId(...)`
- `all(...)`
- `count(...)`
- `exists(...)`

### Static helper APIs

- `Finder.byId(...)`
- `Finder.all(...)`
- `ActiveRecord.byId(...)`
- `ActiveRecord.all(...)`
- `Persistable.save(...)`
- `Deletable.deleteById(...)`

### Full ORM APIs

- `OrmOperations.save(...)`
- `OrmOperations.update(...)`
- `OrmOperations.updatePartial(...)`
- `OrmOperations.findById(...)`
- `OrmOperations.findAll(...)`
- `OrmOperations.findPage(...)`
- `OrmOperations.findAll(..., projectionClass)`
- `OrmOperations.queryList(...)`
- `OrmOperations.executeRaw(...)`
- `OrmOperations.jsonPathQueryFirst(...)`
- `OrmOperations.saveAll/updateAll/deleteAll/upsertAll(...)`
- `OrmOperations.saveAllBatch/updateAllBatch/deleteAllBatch/upsertAllBatch(...)` — routes to `BulkWriter` fast paths

### FilterBuilder predicate APIs

- String-column predicates: `eq`, `neq`, `gt`, `lt`, `gte`, `lte`, `like`, `in`, `isNull`, `isNotNull`
- Typed predicates (same names, first arg `WormAttribute<E,V>`): `eq(attr, value)`, `orderBy(attr)`, `orderByDesc(attr)`, etc.
- `rawWhere(String rawSql, List<Object> params)` — raw SQL fragment with bound parameters
- `openParen()` / `closeParen()` / `or()` — grouping and OR logic
- `leftJoin(table, alias, on)` — ad-hoc join
- `withCte(name, sql)` / `addWindowFunction(expr, alias)` — CTEs and windows
- `ignoreSoftDelete()` — bypass automatic soft-delete filter
- `notJoin()` — suppress metadata-defined joins

### Metamodel APIs

- `{Entity}_.COLUMN_<UPPER_SNAKE>` — raw column name string constant
- `{Entity}_.<fieldName>` — `WormAttribute<Entity, FieldType>` typed descriptor
- `WormAttribute.of(columnName, type)` — manual attribute descriptor factory

### Bulk SPI APIs

- `BulkWriter.bulkInsert(entities, meta)`
- `BulkWriter.bulkUpdate(entities, meta)`
- `BulkWriter.bulkDelete(entities, meta)`
- `BulkWriter.bulkUpsert(entities, meta)`
- `SqlDialect.createBulkWriter(dataSource, copyThreshold, unnestThreshold)` — factory method
- `QueryPlanCache.clear()` — flush the compiled query plan cache

### Repository APIs

- `GenericRepository<T, ID>`
- `@QueryRepository`
- `@Query`
- `@QueryParam`

---

This guide is intended to be practical first: start with the style that best matches your team, then add joins, projections, repositories, tracking, and batch support only where they provide clear value.

---

## 26. Static metamodel and type-safe filters

### 26.1 What the metamodel is

`WormMetamodelProcessor` is an annotation processor that runs at compile time and generates a companion class `{Entity}_.java` for every class annotated with `@DbTable`.

Each generated class contains:

- `public static final String COLUMN_<UPPER_SNAKE>` — the raw DB column name as a constant string.
- `public static final WormAttribute<EntityType, FieldType> <fieldName>` — a typed descriptor that carries both the column name and the Java type of its value.

`@DbJoin` fields are excluded from the metamodel — only mapped columns appear.

### 26.2 Generated output

Given the entity:

```java
@DbTable("orders")
public class Order {
    @DbId("id")
    private UUID id;

    @DbColumn("status")
    private String status;

    @DbColumn("total_amount")
    private BigDecimal totalAmount;

    @CreatedAt
    private Instant createdAt;
}
```

The processor generates:

```java
// Generated — do not edit
@Generated("br.com.liviacare.worm.processor.WormMetamodelProcessor")
public final class Order_ {

    private Order_() {}

    /** DB column for {@code id}. */
    public static final String COLUMN_ID = "id";
    public static final WormAttribute<Order, java.util.UUID> id =
        new WormAttribute<>("id", java.util.UUID.class);

    /** DB column for {@code status}. */
    public static final String COLUMN_STATUS = "status";
    public static final WormAttribute<Order, String> status =
        new WormAttribute<>("status", String.class);

    /** DB column for {@code totalAmount}. */
    public static final String COLUMN_TOTAL_AMOUNT = "total_amount";
    public static final WormAttribute<Order, java.math.BigDecimal> totalAmount =
        new WormAttribute<>("total_amount", java.math.BigDecimal.class);

    /** DB column for {@code createdAt}. */
    public static final String COLUMN_CREATED_AT = "created_at";
    public static final WormAttribute<Order, java.time.Instant> createdAt =
        new WormAttribute<>("created_at", java.time.Instant.class);
}
```

Generated sources land in `target/generated-sources/annotations` and are compiled alongside your application code.

### 26.3 Using WormAttribute in FilterBuilder

Every comparison predicate in `FilterBuilder` has a typed overload accepting a `WormAttribute<E,V>`:

```java
List<Order> result = orm.findAll(Order.class, FilterBuilder.create()
    .eq(Order_.status, "PAID")                            // String — checked at compile time
    .gte(Order_.totalAmount, new BigDecimal("100.00"))     // BigDecimal — checked at compile time
    .lte(Order_.createdAt, Instant.now())
    .orderByDesc(Order_.createdAt));
```

Supported typed overloads:

| Predicate | Typed signature |
|-----------|----------------|
| `eq` | `eq(WormAttribute<E,V> attr, V value)` |
| `neq` | `neq(WormAttribute<E,V> attr, V value)` |
| `gt` | `gt(WormAttribute<E,V> attr, V value)` |
| `lt` | `lt(WormAttribute<E,V> attr, V value)` |
| `gte` | `gte(WormAttribute<E,V> attr, V value)` |
| `lte` | `lte(WormAttribute<E,V> attr, V value)` |
| `like` | `like(WormAttribute<E,V> attr, V value)` |
| `in` | `in(WormAttribute<E,V> attr, Collection<V> values)` |
| `isNull` | `isNull(WormAttribute<E,?> attr)` |
| `isNotNull` | `isNotNull(WormAttribute<E,?> attr)` |
| `orderBy` | `orderBy(WormAttribute<E,?> attr)` |
| `orderByDesc` | `orderByDesc(WormAttribute<E,?> attr)` |

### 26.4 Using COLUMN_* constants in raw SQL

When you only need the column name string (e.g. in a `rawWhere` call or a native `@Query`):

```java
// Raw WHERE using the string constant to avoid a hard-coded literal
FilterBuilder.create()
    .rawWhere(Order_.COLUMN_STATUS + " IN (?, ?)", List.of("PAID", "REFUNDED"));
```

### 26.5 Setup

The processor is included in `worm-processor` and is registered via `META-INF/services/javax.annotation.processing.Processor`. It runs automatically as part of the standard Maven compile lifecycle.

When consuming WORM as a library dependency, add the processor to the compiler plugin:

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

### 26.6 Limitations

- Only `FIELD`-level elements are inspected. Record components are picked up by the existing `WormEntityProcessor`; `WormMetamodelProcessor` currently processes regular class fields only.
- Inner and anonymous classes are skipped.
- Primitive types are auto-boxed in the generated `WormAttribute` (e.g. `int` → `java.lang.Integer`).

---

## 27. Performance guide

### 27.1 Compiled Query Plan Cache

Every unique query *shape* — the combination of entity class, WHERE clause template (with `?` placeholders), join set, ordering, pagination parameters, and boolean flags — is compiled into a SQL string exactly once.

The result is stored in a static `ConcurrentHashMap<QueryPlanKey, String>`. Subsequent calls with the same shape return the cached string directly, skipping all `StringBuilder` allocation and logic inside `QueryBuilder`.

**Practical impact:** a `Finder.all(User.class, filter)` call in a hot endpoint loop pays only the cost of parameter binding on the second and subsequent calls, not SQL generation.

**Cache size** is bounded by the number of distinct query shapes in the application — typically a few dozen. To flush it (e.g. between tests), call:

```java
QueryPlanCache.clear();
```

### 27.2 Zero-allocation row mapping

When `EntityMetadata` is built for a Java record, WORM pre-compiles the canonical constructor as a `MethodHandle.asSpreader(Object[].class, paramCount)`. At row-mapping time, this spreader is invoked with the `Object[]` array that the mapper already holds, without any additional copy.

The original `invokeWithArguments` path (which copies the list internally) is used only as a fallback for constructors that are not compatible with the spreader (e.g. vararg constructors).

This is entirely transparent — no annotation or configuration is required.

### 27.3 Smart Batch-Fetch for collections (`fetchMode = BATCH`)

See [Section 11.7](#117-controlling-collection-fetch-strategy-with-fetchmode) for the full explanation and decision table.

Short summary: use `@DbJoin(fetchMode = FetchMode.BATCH)` when a parent entity has > 5–10 child rows on average. Below that threshold the `LEFT JOIN` + in-memory merge (default) is cheaper because it avoids a second round-trip.

### 27.4 Bulk write thresholds

Configure the thresholds at which WORM switches from standard JDBC batch to the driver-optimised path:

```yaml
worm:
  bulk-copy-threshold: 20      # rows — use PostgreSQL COPY above this for inserts/upserts
  bulk-unnest-threshold: 10    # rows — use unnest arrays above this for updates/deletes
  batch-size: 1000             # chunk size for all batch operations
```

Lowering `bulk-copy-threshold` makes `COPY` kick in earlier (good for write-heavy pipelines). Raising it keeps standard JDBC for small writes that don't benefit from `COPY` overhead.

### 27.5 Choosing the right fetch style

| Scenario | Recommended approach |
|----------|---------------------|
| Single entity by ID | `orm.findById` / `Finder.byId` — benefits from plan cache |
| List of entities, same filter shape repeated | `Finder.all` with a reused `FilterBuilder` shape — plan cache hit |
| Entity with 1:1 or small 1:N joins | `@DbJoin` default (`FetchMode.JOIN`) |
| Entity with large 1:N or N:M collections | `@DbJoin(fetchMode = FetchMode.BATCH)` |
| Bulk insert ≥ 20 rows (PostgreSQL) | `orm.saveAllBatch` — routes to `COPY` |
| Bulk update/delete ≥ 10 rows (PostgreSQL) | `orm.updateAllBatch` / `deleteAllBatch` — routes to unnest |
| Insert-or-update ≥ 20 rows (PostgreSQL) | `orm.upsertAllBatch` — routes to unnest `ON CONFLICT DO UPDATE` |
| Read-only projection of large tables | Java records as projection types — benefits from spreader mapping |

---

## 28. `@DbJoin` relationships with ActiveRecord

This section provides a complete flow with:

- related entities using `@DbJoin`;
- new options (`localColumn`, `referencedColumn`, `targetColumn`, `mappedBy`, `fetchMode`, `on`, `type`);
- query, `save`, `update`, and `delete` examples using the `Entity.save()` style.

### 28.1 Related entity model

```java
@DbTable("departments")
public class Department {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;
}

@DbTable("customers")
public class Customer extends ActiveRecord<Customer, UUID> {
    @DbId("id")
    private UUID id;

    @DbColumn("name")
    private String name;

    @DbColumn("department_id")
    private UUID departmentId;

    @DbColumn("manager_id")
    private UUID managerId;

    @DbColumn("external_code")
    private String externalCode;

    // 1) Convention-based join: infer table/alias/ON
    @DbJoin
    private Department department;

    // 2) Explicit local FK column
    @DbJoin(localColumn = "manager_id")
    private User manager;

    // 3) Join against a non-id target column
    @DbJoin(localColumn = "external_code", targetColumn = "code")
    private CustomerAccount account;

    // 4) Equivalent variant using referencedColumn
    @DbJoin(localColumn = "department_id", referencedColumn = "id")
    private Department canonicalDepartment;

    // 5) One-to-many with BATCH to avoid Cartesian explosion
    @DbJoin(mappedBy = "customer_id", fetchMode = DbJoin.FetchMode.BATCH, type = DbJoin.Type.LEFT)
    private List<Order> orders;

    // 6) Full control over ON
    @DbJoin(
        table = "customer_flags",
        alias = "flags",
        type = DbJoin.Type.LEFT,
        on = "flags.customer_id = a.id and flags.enabled = true"
    )
    private List<CustomerFlag> activeFlags;

    @Override
    public UUID getId() {
        return id;
    }

    // getters/setters
}
```

### 28.2 Queries with ActiveRecord

```java
UUID customerId = UUID.fromString("11111111-1111-1111-1111-111111111111");

// Class-level gateway (recommended for querying)
ActiveRecord.EntityOps<Customer, UUID> customers = ActiveRecord.ar(Customer.class);

Optional<Customer> byId = customers.byId(customerId);

List<Customer> vip = customers.all(
    FilterBuilder.create()
        .eq("segment", "VIP")
        .eq("active", true)
        .orderBy("created_at", false)
);

Slice<Customer> firstPage = customers.all(
    FilterBuilder.create().eq("active", true),
    Pageable.of(0, 20)
);

long totalVip = customers.count(FilterBuilder.create().eq("segment", "VIP"));
```

### 28.3 `save`, `update`, and `delete` using `Entity.save()` style

```java
Customer c = new Customer();
c.setId(UUID.randomUUID());
c.setName("ACME");
c.setDepartmentId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

// INSERT
c.save();

// UPDATE
c.setName("ACME Corp");
c.update();

// DELETE (hard delete or soft delete depending on metadata)
c.delete();
```

### 28.4 Equivalent ActiveRecord static variants

```java
Customer c = new Customer();
// ...set fields

ActiveRecord.save(c);
ActiveRecord.update(c);
ActiveRecord.deleteById(Customer.class, c.getId());
```

### 28.5 Quick `@DbJoin` guidance

- Use `@DbJoin` without `on` for standard cases with lower boilerplate.
- For large collections (`List` / `Collection`), prefer `fetchMode = DbJoin.FetchMode.BATCH`.
- Use `targetColumn` (or `referencedColumn`) when the relation does not target `id`.
- Use `on` only when you need explicit SQL join logic (extra filters, functions, etc.).

