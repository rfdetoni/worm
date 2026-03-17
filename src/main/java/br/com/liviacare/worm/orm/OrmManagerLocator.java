package br.com.liviacare.worm.orm;

public final class OrmManagerLocator {

    private static OrmOperations ormManager;

    private OrmManagerLocator() {}

    public static OrmOperations getOrmManager() {
        if (ormManager == null) throw new IllegalStateException("OrmManager has not been initialized.");
        return ormManager;
    }

    public static void setOrmManager(OrmOperations manager) {
        OrmManagerLocator.ormManager = manager;
    }
}
