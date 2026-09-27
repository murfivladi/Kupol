package dev.vlad.core.storage;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Данные одного игрока. Модули пишут в свою секцию ("homes.*", "teleport.*"),
 * любая запись помечает данные для сохранения.
 */
public final class PlayerData {

    private final UUID uuid;
    final YamlConfiguration yaml;
    volatile boolean dirty;

    PlayerData(UUID uuid, YamlConfiguration yaml) {
        this.uuid = uuid;
        this.yaml = yaml;
    }

    public UUID uuid() {
        return uuid;
    }

    /** synchronized — чтобы асинхронное сохранение не читало YAML посреди записи. */
    public synchronized void set(String path, Object value) {
        yaml.set(path, value);
        dirty = true;
    }

    // Чтение тоже synchronized: данные читаются и из асинхронных событий (вход игрока).

    public synchronized Location getLocation(String path) {
        return yaml.getLocation(path);
    }

    public synchronized String getString(String path) {
        return yaml.getString(path);
    }

    public synchronized double getDouble(String path, double def) {
        return yaml.getDouble(path, def);
    }

    public synchronized long getLong(String path, long def) {
        return yaml.getLong(path, def);
    }

    public synchronized boolean contains(String path) {
        return yaml.contains(path);
    }

    public synchronized List<Map<?, ?>> getMapList(String path) {
        return yaml.getMapList(path);
    }

    public synchronized ConfigurationSection section(String path) {
        return yaml.getConfigurationSection(path);
    }
}
