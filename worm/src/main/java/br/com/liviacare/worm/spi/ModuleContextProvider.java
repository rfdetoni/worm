package br.com.liviacare.worm.spi;

import java.util.Objects;

public final class ModuleContextProvider {

    private static ModuleContext instance;

    private ModuleContextProvider() {}

    public static void set(ModuleContext context) {
        instance = context;
    }

    public static ModuleContext get() {
        // Return a no-op implementation if not configured
        return Objects.requireNonNullElseGet(instance, () -> new ModuleContext() {
            @Override
            public String getCurrentModule() {
                return null;
            }

            @Override
            public void pushCurrentModule(String module) {
            }

            @Override
            public void clearCurrentModule() {
            }
        });
    }
}
