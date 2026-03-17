# ActiveRecord Pattern in WORM

## Overview

WORM now supports the **ActiveRecord pattern**, allowing you to make queries directly on entity classes without needing a separate `Finder` or repository class. This is inspired by Ruby on Rails and provides a more intuitive API for simple queries.

## Quick Start

### 1. Extend `ActiveRecord<T, ID>` in Your Entity

```java
@DbTable("appointments")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class Appointment extends ActiveRecord<Appointment, UUID>
    implements Persistable<Appointment>, Deletable<Appointment, UUID> {

    @DbId("id")
    private UUID id;

    @DbColumn("patient_name")
    private String patientName;

    @DbColumn("status")
    private String status;

    @DbColumn("start_time")
    private LocalDateTime startTime;

    // ... other fields
}
```

### 2. Use the Entity Class for Queries

```java
// Get all appointments
List<Appointment> all = Appointment.all();

// Get by ID
Optional<Appointment> apt = Appointment.byId(appointmentId);

// Query with filter
List<Appointment> active = Appointment.all(
    new FilterBuilder().eq("status", "ACTIVE")
);

// Count
long total = Appointment.count();

// Paginate
Slice<Appointment> page = Appointment.all(Pageable.of(0, 20));
```

## Available Methods

When you extend `ActiveRecord<T, ID>`, you get these static methods:

### Query Methods

| Method | Description | Example |
|--------|-------------|---------|
| `all()` | Get all records | `List<T> items = Entity.all();` |
| `all(FilterBuilder)` | Get records with filter | `List<T> items = Entity.all(filter);` |
| `all(Pageable)` | Get paginated records | `Slice<T> page = Entity.all(Pageable.of(0, 20));` |
| `all(FilterBuilder, Pageable)` | Get paginated filtered records | `Slice<T> page = Entity.all(filter, Pageable.of(0, 20));` |
| `byId(ID)` | Get record by primary key | `Optional<T> item = Entity.byId(id);` |
| `one(FilterBuilder)` | Get single record | `Optional<T> item = Entity.one(filter);` |
| `count()` | Count all records | `long total = Entity.count();` |
| `count(FilterBuilder)` | Count with filter | `long count = Entity.count(filter);` |
| `exists()` | Check if any records exist | `boolean has = Entity.exists();` |
| `exists(FilterBuilder)` | Check with filter | `boolean has = Entity.exists(filter);` |

### Aggregation Methods

| Method | Description | Example |
|--------|-------------|---------|
| `sum(column, type)` | Sum numeric column | `Optional<Long> total = Entity.sum("amount", Long.class);` |
| `sum(column, type, filter)` | Sum with filter | `Optional<Long> total = Entity.sum("amount", Long.class, filter);` |
| `min(column, type)` | Find minimum value | `Optional<Long> min = Entity.min("price", Long.class);` |
| `max(column, type)` | Find maximum value | `Optional<Long> max = Entity.max("price", Long.class);` |
| `avg(column)` | Calculate average | `Optional<Double> avg = Entity.avg("rating");` |

### Column Methods

| Method | Description | Example |
|--------|-------------|---------|
| `findColumn(column, type)` | Get all values from column | `List<String> names = Entity.findColumn("name", String.class);` |
| `findColumn(column, type, filter)` | Get column values with filter | `List<String> names = Entity.findColumn("name", String.class, filter);` |
| `findColumnOne(column, type, filter)` | Get single column value | `Optional<String> name = Entity.findColumnOne("name", String.class, filter);` |

## Real-World Examples

### Example 1: Get Professional's Active Appointments

```java
List<Appointment> appointments = Appointment.all(
    new FilterBuilder()
        .eq("professional_id", professionalId)
        .eq("status", "ACTIVE")
        .order("start_time", "ASC")
);
```

### Example 2: Paginated Patient History

```java
Slice<Appointment> history = Appointment.all(
    new FilterBuilder()
        .eq("patient_id", patientId)
        .order("start_time", "DESC"),
    Pageable.of(pageNumber, pageSize)
);

List<Appointment> content = history.getContent();
boolean hasMore = history.hasNext();
```

### Example 3: Find Upcoming Appointments

```java
LocalDateTime now = LocalDateTime.now();
List<Appointment> upcoming = Appointment.all(
    new FilterBuilder()
        .gte("start_time", now)
        .in("status", new String[]{"SCHEDULED", "ACTIVE"})
        .lte("start_time", now.plusDays(7))
        .order("start_time", "ASC")
);
```

### Example 4: Statistics

```java
// Count appointments by status
long scheduled = Appointment.count(
    new FilterBuilder().eq("status", "SCHEDULED")
);
long completed = Appointment.count(
    new FilterBuilder().eq("status", "COMPLETED")
);

// Check if professional has appointments today
boolean hasTodayAppointments = Appointment.exists(
    new FilterBuilder()
        .eq("professional_id", professionalId)
        .gte("start_time", LocalDate.now().atStartOfDay())
        .lt("start_time", LocalDate.now().plusDays(1).atStartOfDay())
);
```

### Example 5: Combined CRUD Operations

```java
// Create
Appointment apt = Appointment.builder()
    .id(UUID.randomUUID())
    .patientName("John Doe")
    .status("SCHEDULED")
    .startTime(LocalDateTime.now().plusDays(1))
    .build();
apt.save();

// Read
Optional<Appointment> found = Appointment.byId(apt.getId());

// Update
if (found.isPresent()) {
    Appointment a = found.get();
    a.setStatus("ACTIVE");
    a.save();
}

// Delete (soft delete if @DeletedAt is present)
if (found.isPresent()) {
    found.get().delete();
}

// List with filter
List<Appointment> all = Appointment.all(
    new FilterBuilder().eq("status", "ACTIVE")
);
```

## Combining ActiveRecord with Other WORM Features

### With @DbJoin

```java
@DbTable("appointments")
public class Appointment extends ActiveRecord<Appointment, UUID> {

    @DbColumn("professional_id")
    private UUID professionalId;

    // LEFT JOIN to professional_preferences
    @DbJoin(
        table = "professional_preferences",
        alias = "pp",
        on = "pp.professional_id = a.professional_id",
        type = DbJoin.Type.LEFT
    )
    private ProfessionalPreference professionalPreference;
}

// Query with join
List<Appointment> withPrefs = Appointment.all(
    new FilterBuilder().eq("status", "ACTIVE")
);
// The professional_preference field will be populated automatically
```

### With Soft Delete (@DeletedAt)

```java
@DbTable("appointments")
public class Appointment extends ActiveRecord<Appointment, UUID>
    implements Deletable<Appointment, UUID> {

    // ... fields ...

    @DeletedAt
    private LocalDateTime deletedAt;

    @Active
    private boolean active;
}

// Query only non-deleted records (automatically filtered)
List<Appointment> active = Appointment.all();

// Delete (soft delete)
Appointment apt = Appointment.byId(id).orElseThrow();
apt.delete();  // Sets deletedAt timestamp
```

### With Audit Fields (@CreatedAt, @UpdatedAt)

```java
@DbTable("appointments")
public class Appointment extends ActiveRecord<Appointment, UUID>
    implements Persistable<Appointment> {

    // ... fields ...

    @CreatedAt
    private LocalDateTime createdAt;

    @UpdatedAt
    private LocalDateTime updatedAt;
}

// Audit fields are automatically set on save/update
Appointment apt = new Appointment();
apt.setPatientName("Jane Doe");
apt.save();  // createdAt and updatedAt are auto-set
```

### With Optimistic Locking (@DbVersion)

```java
@DbTable("appointments")
public class Appointment extends ActiveRecord<Appointment, UUID>
    implements Persistable<Appointment> {

    // ... fields ...

    @DbVersion
    private long version;
}

// Version is automatically incremented on update
Appointment apt = Appointment.byId(id).orElseThrow();
apt.setStatus("COMPLETED");
apt.save();  // version is incremented, concurrent updates are detected
```

## Comparison: ActiveRecord vs Finder Pattern

| Feature | ActiveRecord | Finder |
|---------|--------------|--------|
| **Pattern** | `Entity.all()` | `Finder.all(Entity.class, ...)` |
| **Verbosity** | Less verbose | More explicit |
| **Type Safety** | Fully type-safe | Fully type-safe |
| **IDE Support** | Excellent | Good |
| **Multi-Tenancy** | ✅ Supported | ✅ Supported |
| **Performance** | Same | Same |
| **Flexibility** | Good for standard queries | Better for complex dynamic queries |
| **Repository Pattern** | Direct entity methods | Finder + ORM operations |
| **Dependency Injection** | None needed | Can inject Finder |

## Advanced: Custom Query Helpers

If you need additional custom methods, you can add them to your entity:

```java
@DbTable("appointments")
public class Appointment extends ActiveRecord<Appointment, UUID> {

    // ... standard fields and methods ...

    /**
     * Custom query helper: get active appointments for a professional
     */
    public static List<Appointment> getActiveProfessionalAppointments(UUID professionalId) {
        return all(
            new FilterBuilder()
                .eq("professional_id", professionalId)
                .eq("status", "ACTIVE")
                .order("start_time", "ASC")
        );
    }

    /**
     * Custom query helper: get overdue appointments
     */
    public static List<Appointment> getOverdueAppointments() {
        return all(
            new FilterBuilder()
                .lt("start_time", LocalDateTime.now())
                .eq("status", "SCHEDULED")
        );
    }

    /**
     * Custom aggregation: count today's appointments for a professional
     */
    public static long countTodayAppointments(UUID professionalId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().plusDays(1).atStartOfDay();
        return count(
            new FilterBuilder()
                .eq("professional_id", professionalId)
                .gte("start_time", startOfDay)
                .lt("start_time", endOfDay)
        );
    }
}

// Usage
List<Appointment> active = Appointment.getActiveProfessionalAppointments(profId);
long todayCount = Appointment.countTodayAppointments(profId);
```

## Migration from Finder Pattern

If you're currently using the traditional `Finder` pattern:

```java
// Before (Finder pattern)
List<User> users = Finder.all(User.class, new FilterBuilder().eq("active", true));

// After (ActiveRecord pattern)
public class User extends ActiveRecord<User, UUID> {
    // ... fields ...
}

// Now:
List<User> users = User.all(new FilterBuilder().eq("active", true));
```

Simply extend `ActiveRecord<T, ID>` in your entity classes, and all the `Finder` static methods become available as instance methods on the entity class itself.

