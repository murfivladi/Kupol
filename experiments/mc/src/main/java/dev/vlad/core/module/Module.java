package dev.vlad.core.module;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Модуль — независимый кусок функциональности (дома, варпы, экономика...).
 * Регистрирует свои команды и слушатели через {@link #command} и {@link #listen},
 * ядро само снимает слушатели при выключении.
 */
public abstract class Module {

    protected final VladCore plugin;
    private final String id;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<String> placeholders = new ArrayList<>();
    private boolean enabled;

    protected Module(VladCore plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    /** Вызывается при включении модуля: регистрируйте команды и слушатели. */
    protected abstract void onEnable();

    protected void onDisable() {
    }

    /** Вызывается по /vcore reload. */
    protected void onReload() {
    }

    protected final void command(ModuleCommand command) {
        Bukkit.getCommandMap().register(plugin.getName().toLowerCase(), command);
    }

    protected final void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    /** %vladcore_&lt;name&gt;% — снимается автоматически при выключении модуля. */
    protected final void placeholder(String name, Function<OfflinePlayer, String> value) {
        plugin.placeholders().register(name, value);
        placeholders.add(name);
    }

    /** %vladcore_&lt;prefix&gt;&lt;arg&gt;%, например baltop_name_ + "1". */
    protected final void placeholderPrefix(String prefix, BiFunction<OfflinePlayer, String, String> value) {
        plugin.placeholders().registerPrefix(prefix, value);
        placeholders.add(prefix);
    }

    /** Значение-флаг для плейсхолдеров: тексты "да/нет" из messages.yml. */
    protected final String yesNo(boolean value) {
        return plugin.messages().get(value ? "placeholder.yes" : "placeholder.no");
    }

    final void enable() {
        onEnable();
        enabled = true;
    }

    final void disable() {
        onDisable();
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
        placeholders.forEach(plugin.placeholders()::unregister);
        placeholders.clear();
        enabled = false;
    }

    final void reload() {
        onReload();
    }

    public final String id() {
        return id;
    }

    public final boolean isEnabled() {
        return enabled;
    }
}
