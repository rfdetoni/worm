package br.com.liviacare.worm.api;

/**
 * Compatibility shim.
 *
 * @deprecated Prefer {@link br.com.liviacare.worm.ActiveRecord}. This type will be removed in a future major version.
 */
@Deprecated(since = "1.1.0")
public abstract class ActiveRecord<T extends br.com.liviacare.worm.ActiveRecord<T, ID>, ID>
        extends br.com.liviacare.worm.ActiveRecord<T, ID> {
}

