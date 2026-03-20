# Prompt for changes and new implementations in applications that use WORM

Use this prompt whenever you need to adapt, extend, or build features in applications that depend on WORM.

## Purpose

This prompt is designed to guide an AI, architect, or developer when implementing changes in applications that use WORM without violating the framework contract.

It specifically covers:

- modeling with `@DbTable`, `@DbId`, `@DbColumn`, and related annotations;
- usage through `ActiveRecord`, `OrmOperations`, or `Finder`;
- opt-in dirty checking through `@Track`;
- partial updates vs full updates;
- optimistic locking with `@DbVersion`;
- auditing with `@CreatedAt`, `@UpdatedAt`, and `@CreatedBy`;
- soft delete with `@Active` and/or `@DeletedAt`;
- batch and bulk operations;
- configuration through `worm.*` properties.

---

## How to use

1. Copy the prompt below.
2. Replace the placeholders with the real details of the target application.
3. Give the prompt to an AI or use it as a technical implementation checklist.

---

## Base prompt

```md
You are an expert in Java/Spring applications that use WORM as the primary ORM.

Your goal is to analyze the target application and implement the requested changes while respecting WORM behavior and conventions.

## Application context

- Project name: [PROJECT_NAME]
- Main module/package: [MAIN_MODULE_OR_PACKAGE]
- Change type: [FEATURE | BUGFIX | MIGRATION | REFACTOR]
- Request description: [DESCRIBE_THE_REQUEST]
- Affected entities: [LIST_THE_ENTITIES]
- Affected use cases: [LIST_THE_FLOWS]
- Database: [POSTGRESQL | MYSQL | OTHER]
- Current persistence strategy: [ACTIVERECORD | FINDER | ORM_OPERATIONS]

## Mandatory WORM rules

1. Do not assume JPA/Hibernate behavior.
   - WORM is not JPA.
   - Do not introduce concepts such as global dirty checking, session, entity manager, implicit lazy loading, or automatic flush.

2. Respect the WORM entity model.
   - Every persisted entity must have `@DbTable`.
   - The primary key must be correctly annotated with `@DbId`.
   - Persisted fields must follow the WORM conventions already used by the project.

3. Dirty tracking is opt-in.
   - Only use automatic dirty checking for entities annotated with `@Track`.
   - Without `@Track`, updates must remain full updates across all updatable columns.
   - With `@Track`, the expected behavior is:
     - a snapshot is captured on reads and after writes;
     - `update()` only updates changed columns;
     - `updatedAt` must still be considered when present.

4. Do not annotate everything with `@Track` without justification.
   - Apply `@Track` only to entities with real benefit, for example:
     - wide tables;
     - entities with a high rate of partial updates;
     - flows sensitive to concurrency and write volume.
   - Avoid `@Track` on trivial, short-lived, or always-fully-overwritten entities.

5. Preserve optimistic locking.
   - If the entity uses `@DbVersion`, keep optimistic concurrency semantics intact.
   - No change may remove version validation.
   - Partial updates must remain compatible with versioned entities.

6. Preserve auditing.
   - If `@CreatedAt`, `@UpdatedAt`, or `@CreatedBy` exist, keep the expected behavior.
   - Do not replace automatic auditing with manual field assignment unless explicitly required.

7. Preserve soft delete.
   - If the entity uses `@Active` and/or `@DeletedAt`, respect the logical delete flow.
   - Do not convert soft delete into hard delete without an explicit requirement.

8. Batch operations must remain deterministic.
   - For bulk insert/update/delete flows, prefer `saveAll`, `updateAll`, `deleteAll`, or `upsertAll`.
   - Consider that WORM requires a resolvable `DataSource` for real batch execution.
   - Do not silently downgrade to row-by-row execution when the use case requires deterministic batching.

## Correct mental model for implementing changes

### 1. Identify which application pattern is in use

Before changing any code, determine whether the application uses:

- entities that `extend ActiveRecord<...>`;
- `Finder`, `ClassFinder`, or `OrmOperations` directly;
- a custom repository layer on top of WORM;
- internal conventions for packages, DTOs, mappers, and services.

### 2. Analyze entity modeling before changing behavior

For every impacted entity, validate:

- table name (`@DbTable`);
- ID (`@DbId`);
- audit columns;
- version field (`@DbVersion`), if present;
- soft delete columns;
- presence or absence of `@Track`.

### 3. Use `@Track` only when it makes sense

If the request is about reducing unnecessary writes, improving concurrency, or emitting partial `UPDATE` statements, then:

- evaluate whether the entity should receive `@Track`;
- make sure update flows normally load the entity before modifying it;
- add tests covering partial update behavior.

If the entity is not annotated with `@Track`, keep full update behavior.

### 4. Understand snapshot semantics

When an entity uses `@Track`, the expected flow is:

- the entity is loaded from the database;
- WORM captures a snapshot of the loaded state;
- the application changes some fields;
- `update()` compares the current state against the snapshot;
- only changed columns are included in the `UPDATE`;
- after persistence, the snapshot must be refreshed.

Practical implications:

- if an entity was created manually and never loaded, a previous snapshot may not exist;
- in that case, the flow may fall back to a full update depending on the code path;
- to benefit from dirty tracking, prefer the pattern `load -> modify -> update`.

### 5. Adjust services and use cases carefully

When implementing new features:

- prefer loading the current entity before modifying it;
- change only the fields that really need to change;
- avoid rebuilding entire objects when unnecessary;
- preserve domain rules before persistence;
- keep create, update, upsert, and delete semantics explicit.

### 6. Batch and performance

If the request involves volume-sensitive flows:

- use WORM batch operations;
- respect properties such as:
  - `worm.batch-size`
  - `worm.insert-strategy`
  - `worm.bulk-copy-threshold`
  - `worm.bulk-unnest-threshold`
- if the database is PostgreSQL, consider bulk shortcuts already supported by WORM;
- do not invent manual batching outside the project standard if WORM already covers the use case.

## Implementation checklist

For each change, follow this sequence:

1. Map impacted files.
2. Identify the WORM entities involved.
3. Verify whether any entity should receive `@Track`.
4. Ensure compatibility with `@DbVersion`.
5. Ensure compatibility with auditing.
6. Ensure compatibility with soft delete.
7. Verify whether the flow loads the entity before update.
8. Adjust service, repository/finder, DTO, and controller layers when necessary.
9. Create or update unit/integration tests.
10. Validate compilation, tests, and SQL impact.

## Important constraints

- Do not change WORM itself unless the task explicitly requires modifying the library.
- Do not replace WORM with JPA/Hibernate.
- Do not add unnecessary abstractions that hide the project model.
- Do not remove existing annotations without technical justification.
- Do not break public contracts in the application.
- Do not assume every entity should use `@Track`.

## When to apply `@Track`

Apply `@Track` when:

- the entity is frequently updated in only a few fields;
- the table has many columns;
- there is a real benefit in partial `UPDATE` statements;
- the business flow normally loads the entity before changing it.

Avoid `@Track` when:

- the entity is almost always overwritten as a whole;
- the entity is simple and the gain is negligible;
- the flow creates disconnected instances without a prior read and the team does not want to depend on snapshots.

## Example architectural decision

If the feature is “update only order status and notes”, the preferred path is:

1. load the current order from the database;
2. validate business rules;
3. change only `status` and `notes`;
4. call `update()`;
5. if `Order` has `@Track`, expect a partial `UPDATE`;
6. if it does not, accept a full `UPDATE`.

## Configuration to review in the application

Check whether the project contains something like:

```yaml
worm:
  batch-size: 500
  insert-strategy: UPSERT
  transaction-enabled: true
  bulk-copy-threshold: 20
  bulk-unnest-threshold: 10
```

Only change these values when there is a real need related to throughput, concurrency, or transaction behavior.

## Expected deliverables

At the end, provide:

1. a technical summary of what changed;
2. a list of modified files;
3. a justification for using or not using `@Track` on each entity;
4. impacts on update, delete, batch, and auditing behavior;
5. tests added or adjusted;
6. commands executed for validation;
7. risks and next steps.

## Expected response format

Respond in this order:

1. request understanding;
2. diagnosis of current WORM usage in the application;
3. step-by-step change plan;
4. proposed implementation;
5. required tests;
6. risks, trade-offs, and final validation.

If context is missing, investigate the project first and only then propose the implementation.
```

---

## Short version of the prompt

```md
Analyze this Java/Spring application that uses WORM and implement the requested change while respecting the framework contract.

Rules:
- do not assume JPA/Hibernate behavior;
- respect `@DbTable`, `@DbId`, auditing, soft delete, and `@DbVersion`;
- use `@Track` only as opt-in, never globally;
- with `@Track`, consider snapshot-based partial updates;
- without `@Track`, keep full update behavior;
- preserve real batch execution and avoid silent degradation when determinism is required;
- prefer the `load -> modify -> update` flow for tracked entities.

Deliver:
- diagnosis of the current state;
- files to be changed;
- proposed implementation;
- tests;
- risks;
- final validation.
```

---

## Notes for teams using WORM

- `@Track` is a behavioral optimization, not a mandatory annotation.
- The biggest gains appear when the application performs granular updates on previously loaded entities.
- For new features, prefer services that load the current entity before applying changes.
- Always validate the impact on `updatedAt`, `@DbVersion`, and soft delete behavior.
- For high-volume flows, prefer WORM native batch paths before building parallel solutions.
