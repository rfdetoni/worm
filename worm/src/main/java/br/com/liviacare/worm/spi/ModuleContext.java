package br.com.liviacare.worm.spi;

import java.util.function.Supplier;

/**
 * Service Provider Interface (SPI) for module context management.
 * The core framework provides the implementation.
 */
public interface ModuleContext {
    String getCurrentModule();
    void pushCurrentModule(String module);
    void clearCurrentModule();

    default <T> T withModule(String module, Supplier<T> action) {
        pushCurrentModule(module);
        try {
            return action.get();
        } finally {
            clearCurrentModule();
        }
    }

    default void withModuleVoid(String module, Runnable action) {
        withModule(module, () -> {
            action.run();
            return null;
        });
    }
}
