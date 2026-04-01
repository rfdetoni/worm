package br.com.liviacare.worm.dsl;

/**
 * Typed, alias-aware column path.
 *
 * @param <T> path value type
 */
public interface Path<T> extends Expression<T> {

    EntityPath<?> root();

    String column();

    default String qualifiedName() {
        return root().alias() + "." + column();
    }

    default OrderSpecifier asc() {
        return new OrderSpecifier(this, OrderSpecifier.Direction.ASC);
    }

    default OrderSpecifier desc() {
        return new OrderSpecifier(this, OrderSpecifier.Direction.DESC);
    }
}

