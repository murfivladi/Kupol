package dev.vlad.core.module;

import dev.vlad.core.VladCore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

public final class ModuleManager {

    private final VladCore plugin;
    private final Map<String, Module> modules = new LinkedHashMap<>();

    public ModuleManager(VladCore plugin) {
        this.plugin = plugin;
    }

    public void register(Module module) {
        modules.put(module.id(), module);
    }

    public void enableAll() {
        for (Module module : modules.values()) {
            if (!plugin.getConfig().getBoolean("modules." + module.id(), true)) {
                continue;
            }
            try {
                module.enable();
            } catch (Exception e) {
                // Сломанный модуль не должен ронять весь плагин.
                plugin.getLogger().log(Level.SEVERE, "Не удалось включить модуль " + module.id(), e);
            }
        }
    }

    public void disableAll() {
        for (Module module : modules.values()) {
            if (module.isEnabled()) {
                module.disable();
            }
        }
    }

    public void reloadAll() {
        for (Module module : modules.values()) {
            if (module.isEnabled()) {
                module.reload();
            }
        }
    }

    public Collection<Module> all() {
        return modules.values();
    }

    public long enabledCount() {
        return modules.values().stream().filter(Module::isEnabled).count();
    }
}
