package dev.vlad.core.storage;

import dev.vlad.core.VladCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Хранилище данных игроков: plugins/VladCore/players/&lt;uuid&gt;.yml.
 * Файлы подгружаются при первом обращении, изменённые сохраняются
 * асинхронно раз в 5 минут, при выходе игрока и при выключении плагина.
 */
public final class PlayerStore implements Listener {

    private static final long AUTOSAVE_TICKS = 20L * 60 * 5;

    private final VladCore plugin;
    private final File folder;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public PlayerStore(VladCore plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "players");
        folder.mkdirs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveDirty, AUTOSAVE_TICKS, AUTOSAVE_TICKS);
    }

    /** Данные игрока (онлайн или офлайн); файл читается один раз и кэшируется. */
    public PlayerData get(UUID uuid) {
        return cache.computeIfAbsent(uuid, id ->
                new PlayerData(id, YamlConfiguration.loadConfiguration(file(id))));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // MONITOR — модули уже записали всё, что хотели, при выходе.
        PlayerData data = cache.remove(event.getPlayer().getUniqueId());
        if (data != null && data.dirty) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> save(data));
        }
    }

    private void saveDirty() {
        cache.values().forEach(data -> {
            if (data.dirty) {
                save(data);
            }
        });
    }

    /** Синхронное сохранение всего — при выключении плагина. */
    public void saveAll() {
        saveDirty();
    }

    private void save(PlayerData data) {
        String text;
        synchronized (data) {
            data.dirty = false;
            text = data.yaml.saveToString();
        }
        try {
            Files.write(file(data.uuid()).toPath(),
                    text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            data.dirty = true;
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить данные игрока " + data.uuid(), e);
        }
    }

    private File file(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }
}
