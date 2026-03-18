# WORM ORM - Lightweight Java ORM Framework
[![Build Status](https://img.shields.io/badge/build-success-brightgreen)]()
[![Java Version](https://img.shields.io/badge/java-25+-blue)]()
[![License](https://img.shields.io/badge/license-MIT-green)]()

**WORM** (Write-Optimize Relational Mapper) is a lightweight, zero-dependency ORM framework for Java with Spring Boot support. It's designed to be **fast, flexible, and JPA/Hibernate-free**.

## Table of Contents

- [Key Features](#key-features)
- [Quick Start](#quick-start)
- [Practical Examples](#practical-examples)
- [Native Query Repositories](#native-query-repositories)
- [Configuration](#configuration)
- [SQL Dialects](#sql-dialects)
- [Performance Tips](#performance-tips)
- [Architecture](#architecture)
- [Module Routing & Multi-Tenancy](#module-routing--multi-tenancy)
- [Limitations & Non-Features](#limitations--non-features)
- [Build & Testing](#build--testing)
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
    <version>1.0.0</version>
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
}
```

Supported join shortcuts:

- `@DbJoin` (no args): infer table, alias, and `ON` by convention.
- `@DbJoin(localColumn = "...")`: infer `ON` as `<alias>.id = a.<localColumn>`.
- `@DbJoin(localColumn = "...", targetColumn = "...")`: same as above, but joining against a non-`id` column in the target table.
- `@DbJoin(mappedBy = "...")`: infer collection `ON` as `<alias>.<mappedBy> = a.id`.
- `@DbJoin` on collections without `mappedBy`: tries to infer from a back-reference field in the child entity (e.g. `user -> user_id`), then falls back to `<singular_main_table>_id`.
- `@DbJoin(on = "...")`: full manual control when needed.

## Core Concepts

### Annotations

| Annotation | Purpose |
|-----------|---------|
| `@DbTable` | Maps class to database table |
| `@DbId` | Marks primary key field |
| `@DbColumn` | Maps field to column (with optional SQL expressions) |
| `@DbJoin` | Defines table join for related entities |
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

### FilterBuilder

The `FilterBuilder` API lets you build predicates declaratively, with predicates described in the [Practical Examples](#filterbuilder-in-action) section. Use it with `Finder`, `OrmOperations`, or native queries to supply the `WHERE` clause and bind parameters automatically.

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

## Performance Tips

1. **Use Batch Operations** - `saveAll()` and `updateAll()` for multiple entities
2. **Projection Records** - Use Java records for read-only queries
3. **Soft Deletes** - Enable soft deletes with @DeletedAt for logical data integrity
4. **Indexes** - Create database indexes on frequently queried columns
5. **Connection Pooling** - Configure HikariCP for optimal connection management

## Architecture

### Key Components

- **OrmManager** - Core persistence engine
- **EntityMetadata** - Cached reflection metadata using MethodHandles
- **QueryBuilder** - Parameterized SQL query construction
- **EntityMapper** - ResultSet to entity conversion
- **EntityPersister** - Parameter binding for INSERT/UPDATE
- **SqlExecutor** - JDBC execution with metrics

### Design Principles

1. **Type Safety** - Compile-time checked queries
2. **Zero Reflection** - Uses MethodHandles for maximum performance
3. **SQL Control** - Generate readable, auditable SQL
4. **Spring Integration** - Seamless transaction and bean management
5. **Extensibility** - SPI for custom behaviors

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

- `worm-1.0.0.jar` - Main library (149 KB)
- `worm-1.0.0-sources.jar` - Source code (79 KB)
- `worm-1.0.0-javadoc.jar` - API documentation (4.4 MB)

All artifacts are self-contained with no JPA/Hibernate dependencies.

## Contributing

Contributions are welcome! Please:

1. Follow the existing code style
2. Maintain method names and business rules as-is
3. Add unit tests for new features
4. Update documentation

## License

MIT License - See LICENSE file for details

## Support & Documentation

- 📖 [Full API Documentation](./target/worm-1.0.0-javadoc.jar)
- 🐛 [Issue Tracker](https://github.com/liviacare/worm/issues)
- 💬 [Discussions](https://github.com/liviacare/worm/discussions)

## Changelog

### Version 1.0.0 (2026-03-17)
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

