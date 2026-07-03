package com.github.rfdetoni.worm.api;

/**
 * Compatibility shim.
 *
 * @deprecated Prefer {@link com.github.rfdetoni.worm.ActiveRecord}. This type will be removed in a future major version.
 */
@Deprecated(since = "1.1.0")
public abstract class ActiveRecord<T extends com.github.rfdetoni.worm.ActiveRecord<T, ID>, ID>
        extends com.github.rfdetoni.worm.ActiveRecord<T, ID> {
}

