package br.com.liviacare.worm.dsl;

public final class EnumPath<E extends Enum<E>> extends AbstractPath<E> {

    public EnumPath(EntityPath<?> root, String column, Class<E> type) {
        super(root, column, type);
    }
}

