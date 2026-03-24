package br.com.liviacare.worm.orm;

public final class OrmManagerLocator {

    private static OrmOperations ormManager;

    private OrmManagerLocator() {}

    public static OrmOperations getOrmManager() {
        if (ormManager == null) {
            throw new IllegalStateException(
                "OrmManager has not been initialized. " +
                "Ensure your Spring Boot application includes @EnableWorm or has OrmAutoConfiguration in classpath. " +
                "If not using Spring Boot, call OrmManagerLocator.setOrmManager(ormManager) during startup."
            );
        }
        return ormManager;
    }

    public static void setOrmManager(OrmOperations manager) {
        if (manager == null) {
            throw new IllegalArgumentException("OrmManager cannot be null");
        }
        OrmManagerLocator.ormManager = manager;
    }
}
