package br.com.liviacare.worm;

import br.com.liviacare.worm.api.Deletable;
import br.com.liviacare.worm.api.Finder;
import br.com.liviacare.worm.api.Persistable;
import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.orm.tracking.EntitySnapshot;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Canonical ActiveRecord base for entities.
 * <p>
 * Instance-side (zero boilerplate): entities inherit {@code save()}, {@code update()}, and {@code delete()}.
 * <p>
 * Class-side querying is available through {@link #ar(Class)} / {@link #query(Class)}.
 * For truly terse entity-style calls such as {@code Book.byId(id)}, add tiny static forwarders
 * in the entity that delegate to this base class methods.
 */
public abstract class ActiveRecord<T extends ActiveRecord<T, ID>, ID>
		implements Persistable<T>, Deletable<T, ID>, Finder<T, ID> {

	private static final ConcurrentMap<Class<?>, Class<?>> ENTITY_CLASS_CACHE = new ConcurrentHashMap<>();
	private transient EntitySnapshot wormSnapshot;

	protected static OrmOperations orm() {
		return OrmManagerLocator.getOrmManager();
	}

	public final EntitySnapshot __wormSnapshot() {
		return wormSnapshot;
	}

	public final void __wormSetSnapshot(EntitySnapshot snapshot) {
		this.wormSnapshot = snapshot;
	}

	public final void __wormClearSnapshot() {
		this.wormSnapshot = null;
	}

			@Override
			@SuppressWarnings("unchecked")
			public Class<T> entityClass() {
				Class<?> runtimeType = getClass();
				return (Class<T>) ENTITY_CLASS_CACHE.computeIfAbsent(runtimeType, ActiveRecord::resolveEntityClass);
			}

			private static Class<?> resolveEntityClass(Class<?> runtimeType) {
				Class<?> current = runtimeType;
				while (current != null && current != Object.class) {
					Type genericSuper = current.getGenericSuperclass();
					if (genericSuper instanceof ParameterizedType pt) {
						Type raw = pt.getRawType();
						if (raw instanceof Class<?> rawClass && ActiveRecord.class.isAssignableFrom(rawClass)) {
							Type firstArg = pt.getActualTypeArguments()[0];
							if (firstArg instanceof Class<?> concreteClass) {
								return concreteClass;
							}
							if (firstArg instanceof ParameterizedType pArg && pArg.getRawType() instanceof Class<?> pArgClass) {
								return pArgClass;
							}
						}
					}
					current = current.getSuperclass();
				}
										throw new IllegalStateException("Could not infer entity class for " + String.valueOf(runtimeType)
						+ ". Override entityClass() explicitly.");
			}

	/**
	 * Entity-scoped operations gateway for less verbose class-level calls.
	 */
	public static final class EntityOps<E, PK> {
		private final Class<E> entityClass;

		private EntityOps(Class<E> entityClass) {
			this.entityClass = entityClass;
		}

		public Optional<E> byId(PK id) {
			return orm().findById(entityClass, id);
		}

		public Optional<E> one(FilterBuilder filter) {
			return orm().findOne(entityClass, filter);
		}

		public List<E> all() {
			return orm().findAll(entityClass, FilterBuilder.create());
		}

		public List<E> all(FilterBuilder filter) {
			return orm().findAll(entityClass, filter);
		}

		public Slice<E> all(Pageable pageable) {
			return orm().findAll(entityClass, FilterBuilder.create(), pageable);
		}

		public Slice<E> all(FilterBuilder filter, Pageable pageable) {
			return orm().findAll(entityClass, filter, pageable);
		}

		public long count() {
			return orm().count(entityClass);
		}

		public long count(FilterBuilder filter) {
			return orm().count(entityClass, filter);
		}

		public boolean exists() {
			return exists(FilterBuilder.create());
		}

		public boolean exists(FilterBuilder filter) {
			return orm().exists(entityClass, filter);
		}

		public <N extends Number> Optional<N> sum(String column, Class<N> type) {
			return sum(column, type, FilterBuilder.create());
		}

		public <N extends Number> Optional<N> sum(String column, Class<N> type, FilterBuilder filter) {
			return orm().sum(entityClass, column, type, filter);
		}

		public <N extends Number> Optional<N> min(String column, Class<N> type) {
			return min(column, type, FilterBuilder.create());
		}

		public <N extends Number> Optional<N> min(String column, Class<N> type, FilterBuilder filter) {
			return orm().min(entityClass, column, type, filter);
		}

		public <N extends Number> Optional<N> max(String column, Class<N> type) {
			return max(column, type, FilterBuilder.create());
		}

		public <N extends Number> Optional<N> max(String column, Class<N> type, FilterBuilder filter) {
			return orm().max(entityClass, column, type, filter);
		}

		public Optional<Double> avg(String column) {
			return avg(column, FilterBuilder.create());
		}

		public Optional<Double> avg(String column, FilterBuilder filter) {
			return orm().avg(entityClass, column, filter);
		}

		public <C> List<C> findColumn(String columnName, Class<C> type) {
			return findColumn(columnName, type, FilterBuilder.create());
		}

		public <C> List<C> findColumn(String columnName, Class<C> type, FilterBuilder filter) {
			return orm().findColumn(entityClass, columnName, type, filter);
		}

		public <C> Optional<C> findColumnOne(String columnName, Class<C> type) {
			return findColumnOne(columnName, type, FilterBuilder.create());
		}

		public <C> Optional<C> findColumnOne(String columnName, Class<C> type, FilterBuilder filter) {
			return orm().findColumnOne(entityClass, columnName, type, filter);
		}

		public E save(E entity) {
			orm().save(entity);
			return entity;
		}

		public E update(E entity) {
			orm().update(entity);
			return entity;
		}

		public void deleteById(PK id) {
			orm().deleteById(entityClass, id);
		}
	}

	public static <E, PK> EntityOps<E, PK> ar(Class<E> entityClass) {
		return new EntityOps<>(entityClass);
	}

	/**
	 * Factory for the classic {@code Class.find.<finder-method>()} style with zero boilerplate.
	 *
	 * Example in entity:
	 * <pre>
	 * public static final Finder&lt;Book, UUID&gt; find = ActiveRecord.find(Book.class);
	 * </pre>
	 */
	public static <E, PK> Finder<E, PK> find(Class<E> entityClass) {
		return () -> entityClass;
	}

	/** Alias of {@link #find(Class)}. */
	public static <E, PK> Finder<E, PK> finder(Class<E> entityClass) {
		return find(entityClass);
	}

	/** Alias of {@link #ar(Class)} for readability. */
	public static <E, PK> EntityOps<E, PK> query(Class<E> entityClass) {
		return ar(entityClass);
	}

	/** Alias of {@link #ar(Class)} for readability. */
	public static <E, PK> EntityOps<E, PK> forClass(Class<E> entityClass) {
		return ar(entityClass);
	}

	// Static convenience methods for teams that prefer class-level CRUD.
	public static <E extends Persistable<E>> E save(E entity) {
		return Persistable.save(entity);
	}

	public static <E extends Persistable<E>> E update(E entity) {
		return Persistable.update(entity);
	}

	public static <E extends Persistable<E>> List<E> saveAll(List<E> entities) {
		return Persistable.saveAll(entities);
	}

	public static <E extends Persistable<E>> List<E> updateAll(List<E> entities) {
		return Persistable.updateAll(entities);
	}

	public static <E, PK> void deleteById(Class<E> clazz, PK id) {
		Deletable.deleteById(clazz, id);
	}

	public static void deleteAll(List<? extends Deletable<?, ?>> entities) {
		Deletable.deleteAll(entities);
	}

	// Static convenience query methods.
	public static <E, PK> Optional<E> byId(Class<E> clazz, PK id) { return ar(clazz).byId(id); }
	public static <E> Optional<E> one(Class<E> clazz, FilterBuilder filter) { return ar(clazz).one(filter); }
	public static <E> List<E> all(Class<E> clazz) { return ar(clazz).all(); }
	public static <E> List<E> all(Class<E> clazz, FilterBuilder filter) { return ar(clazz).all(filter); }
	public static <E> Slice<E> all(Class<E> clazz, Pageable pageable) { return ar(clazz).all(pageable); }
	public static <E> Slice<E> all(Class<E> clazz, FilterBuilder filter, Pageable pageable) { return ar(clazz).all(filter, pageable); }
	public static <E> long count(Class<E> clazz) { return ar(clazz).count(); }
	public static <E> long count(Class<E> clazz, FilterBuilder filter) { return ar(clazz).count(filter); }
	public static <E> boolean exists(Class<E> clazz) { return ar(clazz).exists(); }
	public static <E> boolean exists(Class<E> clazz, FilterBuilder filter) { return ar(clazz).exists(filter); }
	public static <E, N extends Number> Optional<N> sum(Class<E> clazz, String column, Class<N> type) { return ar(clazz).sum(column, type); }
	public static <E, N extends Number> Optional<N> sum(Class<E> clazz, String column, Class<N> type, FilterBuilder filter) { return ar(clazz).sum(column, type, filter); }
	public static <E, N extends Number> Optional<N> min(Class<E> clazz, String column, Class<N> type) { return ar(clazz).min(column, type); }
	public static <E, N extends Number> Optional<N> min(Class<E> clazz, String column, Class<N> type, FilterBuilder filter) { return ar(clazz).min(column, type, filter); }
	public static <E, N extends Number> Optional<N> max(Class<E> clazz, String column, Class<N> type) { return ar(clazz).max(column, type); }
	public static <E, N extends Number> Optional<N> max(Class<E> clazz, String column, Class<N> type, FilterBuilder filter) { return ar(clazz).max(column, type, filter); }
	public static <E> Optional<Double> avg(Class<E> clazz, String column) { return ar(clazz).avg(column); }
	public static <E> Optional<Double> avg(Class<E> clazz, String column, FilterBuilder filter) { return ar(clazz).avg(column, filter); }
	public static <E, C> List<C> findColumn(Class<E> clazz, String columnName, Class<C> type) { return ar(clazz).findColumn(columnName, type); }
	public static <E, C> List<C> findColumn(Class<E> clazz, String columnName, Class<C> type, FilterBuilder filter) { return ar(clazz).findColumn(columnName, type, filter); }
	public static <E, C> Optional<C> findColumnOne(Class<E> clazz, String columnName, Class<C> type) { return ar(clazz).findColumnOne(columnName, type); }
	public static <E, C> Optional<C> findColumnOne(Class<E> clazz, String columnName, Class<C> type, FilterBuilder filter) { return ar(clazz).findColumnOne(columnName, type, filter); }

}
