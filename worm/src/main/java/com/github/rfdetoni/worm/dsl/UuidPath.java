package com.github.rfdetoni.worm.dsl;

import java.util.UUID;

public final class UuidPath extends AbstractPath<UUID> {

    public UuidPath(EntityPath<?> root, String column) {
        super(root, column, UUID.class);
    }
}

