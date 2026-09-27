package dev.vlad.core.modules.economy;

import dev.vlad.core.VladCore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Балансы всех игроков в памяти + economy.yml.
 * Отдельно от данных игроков, чтобы /baltop не читал сотни файлов.
 * Потокобезопасен: Vault могут дёргать из асинхронных потоков другие плагины.
 */
final class Bank {

    private final VladCore plugin;
    private final File file;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    Bank(VladCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "economy.yml");
    }

    void load() {
        balances.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), yaml.getDouble(key));
            } catch (IllegalArgumentException ignored) {
                // не UUID — мусор в файле, пропускаем
            }
        }
    }

    void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        new HashMap<>(balances).forEach((uuid, balance) -> yaml.set(uuid.toString(), balance));
        try {
            Files.write(file.toPath(), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            dirty = true;
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить economy.yml", e);
        }
    }

    boolean has(UUID uuid) {
        return balances.containsKey(uuid);
    }

    /** Создаёт счёт со стартовым балансом, если его ещё нет. */
    boolean create(UUID uuid) {
        if (balances.putIfAbsent(uuid, startingBalance()) == null) {
            dirty = true;
            return true;
        }
        return false;
    }

    double balance(UUID uuid) {
        return balances.getOrDefault(uuid, startingBalance());
    }

    synchronized void set(UUID uuid, double amount) {
        balances.put(uuid, round(amount));
        dirty = true;
    }

    /** Атомарно меняет баланс на delta. false — если ушли бы в минус. */
    synchronized boolean add(UUID uuid, double delta) {
        double result = balance(uuid) + delta;
        if (result < 0) {
            return false;
        }
        set(uuid, result);
        return true;
    }

    /** Атомарный перевод между игроками. */
    synchronized boolean transfer(UUID from, UUID to, double amount) {
        if (balance(from) < amount) {
            return false;
        }
        add(from, -amount);
        add(to, amount);
        return true;
    }

    List<Map.Entry<UUID, Double>> top() {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(balances.entrySet());
        list.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        return list;
    }

    private double startingBalance() {
        return plugin.getConfig().getDouble("economy.starting-balance", 100);
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
