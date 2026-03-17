# WORM Package Organization Guide

## Overview

The WORM ORM package structure has been reorganized by **context and usage** to improve code organization and developer experience. All public APIs remain backward compatible.

## New Package Structure

### 1. Annotations Layer

Annotations are grouped by their responsibility domain:

#### `br.com.liviacare.worm.annotation`
**Root annotation package** - contains core table mapping annotations:
- `@DbTable` - maps class to database table
- `@DbId` - marks primary key
- `@DbColumn` - column mapping

#### `br.com.liviacare.worm.annotation.mapping`
**Entity mapping annotations**:
- `@DbJoin` - relationship/join definition
- `@DbVersion` - optimistic locking
- `@OrderBy` - default sort order

#### `br.com.liviacare.worm.annotation.audit`
**Audit field annotations**:
- `@CreatedAt`, `@UpdatedAt` - timestamps
- `@CreatedBy`, `@UpdatedBy` - user tracking
- `@Active`, `@DeletedAt` - soft delete

#### `br.com.liviacare.worm.annotation.query`
**Query annotations**:
- `@Query` - native SQL query method
- `@QueryParam` - parameter binding
- `@QueryRepository` - repository interface marker
- `@JlfQuery` - legacy query annotation

### 2. Repository Layer

#### `br.com.liviacare.worm.repository`
**Core repository** (unchanged):
- `GenericRepository` - base CRUD implementation
- `LjfRepository` - CRUD interface
- `RepositoryOperations` - minimal interface

#### `br.com.liviacare.worm.repository.crud`
**CRUD abstractions** (future grouping):
- Contains CRUD-specific repository implementations

#### `br.com.liviacare.worm.repository.query`
**Native SQL query repositories**:
- `QueryRepositoryFactory` - runtime proxy factory
- `QueryRepositoryFactoryBean` - Spring integration

### 3. Configuration Layer

#### `br.com.liviacare.worm.config`
**Core config** (unchanged):
- `OrmAutoConfiguration` - ORM setup
- `WormProperties` - ORM properties
- `TransactionConfig` - transaction management

#### `br.com.liviacare.worm.config.query`
**Query repository config**:
- `QueryRepositoriesAutoConfiguration` - auto-discovery
- `QueryRepositoryProperties` - scan configuration

## Migration Guide

### No changes required for existing code

All packages maintain backward compatibility. The reorganization is **internal structure only**.

### If you want to use the new packages explicitly

**Before:**
```java
import br.com.liviacare.worm.annotation.Query;
import br.com.liviacare.worm.annotation.QueryParam;
import br.com.liviacare.worm.repository.QueryRepositoryFactory;
```

**After (optional):**
```java
import br.com.liviacare.worm.annotation.query.Query;
import br.com.liviacare.worm.annotation.query.QueryParam;
import br.com.liviacare.worm.repository.query.QueryRepositoryFactory;
```

## Benefits

### For Users
- **Clear organization**: Find related classes by context, not just alphabetically
- **Reduced cognitive load**: Query-related code grouped together
- **Better IDE navigation**: Package names suggest what they contain

### For Maintainers
- **Easier refactoring**: Changes isolated to context packages
- **Clear boundaries**: Each package has a specific responsibility
- **Documentation**: Each package-info.java explains the purpose

## Package Responsibilities at a Glance

| Package | Purpose | Visibility |
|---------|---------|------------|
| `annotation` | Core table/entity mapping | Public API |
| `annotation.mapping` | Relationship and version tracking | Public API |
| `annotation.audit` | Auto-tracking fields (timestamps, users) | Public API |
| `annotation.query` | Native SQL query execution | Public API |
| `repository` | Generic CRUD base classes | Public API |
| `repository.crud` | CRUD-specific abstractions | Public API |
| `repository.query` | Native query factory and wiring | Public API |
| `config` | ORM core auto-configuration | Internal |
| `config.query` | Query repository auto-configuration | Internal |

## Future Improvements

1. **Phase 2**: Move mapping annotations into subfolders by concern:
   - `annotation.mapping.relationships` - for `@DbJoin`
   - `annotation.mapping.versions` - for `@DbVersion`

2. **Phase 3**: Consolidate CRUD repository implementations:
   - Move `GenericRepository`, `LjfRepository` to `repository.crud`
   - Maintain compatibility facades in `repository`

3. **Phase 4**: Separate test fixtures by context:
   - `src/test/java/br/com/liviacare/worm/{feature}/fixtures`
   - One test fixture set per feature area

## Questions?

Refer to individual `package-info.java` files in each package for detailed documentation.

o