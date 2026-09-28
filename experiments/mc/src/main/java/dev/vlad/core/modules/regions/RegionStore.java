package dev.vlad.core.modules.regions;

import dev.vlad.core.VladCore;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Все приваты + индекс по чанкам: проверка "чей это блок" смотрит только
 * регионы своего чанка, а не все подряд (важно для взрывов и частых событий).
 */
final class RegionStore {

    private final VladCore plugin;
    private final File file;
    private final Map<String, Region> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** мир → ключ чанка → регионы, задевающие этот чанк. */
    private final Map<String, Map<Long, List<Region>>> index = new HashMap<>();

    RegionStore(VladCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "regions.yml");
    }

    void load() {
        byName.clear();
        index.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("regions");
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            List<Integer> min = s.getIntegerList("min");
            List<Integer> max = s.getIntegerList("max");
            if (min.size() != 3 || max.size() != 3) {
                plugin.getLogger().warning("regions.yml: у " + name + " неверные границы, пропускаю");
                continue;
            }
            Region r = new Region(name, s.getString("world"), min.get(0), min.get(1), min.get(2),
                    max.get(0), max.get(1), max.get(2));
            String owner = s.getString("owner");
            r.owner = owner == null ? null : UUID.fromString(owner);
            s.getStringList("members").forEach(m -> r.members.add(UUID.fromString(m)));
            ConfigurationSection flags = s.getConfigurationSection("flags");
            if (flags != null) {
                flags.getKeys(false).forEach(f -> r.flags.put(f, flags.getBoolean(f)));
            }
            r.priority = s.getInt("priority", 0);
            r.created = s.getLong("created", 0);
            add(r);
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Region r : byName.values()) {
            String p = "regions." + r.name + ".";
            yaml.set(p + "world", r.world);
            yaml.set(p + "min", Arrays.asList(r.minX, r.minY, r.minZ));
            yaml.set(p + "max", Arrays.asList(r.maxX, r.maxY, r.maxZ));
            yaml.set(p + "owner", r.owner == null ? null : r.owner.toString());
            List<String> members = new ArrayList<>();
            r.members.forEach(m -> members.add(m.toString()));
            yaml.set(p + "members", members);
            r.flags.forEach((f, v) -> yaml.set(p + "flags." + f, v));
            yaml.set(p + "priority", r.priority);
            yaml.set(p + "created", r.created);
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить regions.yml", e);
        }
    }

    void add(Region r) {
        byName.put(r.name, r);
        Map<Long, List<Region>> chunks = index.computeIfAbsent(r.world, w -> new HashMap<>());
        for (int cx = r.minX >> 4; cx <= r.maxX >> 4; cx++) {
            for (int cz = r.minZ >> 4; cz <= r.maxZ >> 4; cz++) {
                chunks.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(r);
            }
        }
    }

    void remove(Region r) {
        byName.remove(r.name);
        Map<Long, List<Region>> chunks = index.get(r.world);
        if (chunks != null) {
            chunks.values().forEach(list -> list.remove(r));
        }
    }

    Region get(String name) {
        return byName.get(name);
    }

    Collection<Region> all() {
        return Collections.unmodifiableCollection(byName.values());
    }

    /** Главный регион в точке: с наибольшим приоритетом, либо null. */
    Region at(Location l) {
        if (l.getWorld() == null) {
            return null;
        }
        return at(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    Region at(String world, int x, int y, int z) {
        Map<Long, List<Region>> chunks = index.get(world);
        List<Region> list = chunks == null ? null : chunks.get(key(x >> 4, z >> 4));
        if (list == null) {
            return null;
        }
        Region best = null;
        for (Region r : list) {
            if (r.contains(x, y, z) && (best == null || r.priority > best.priority)) {
                best = r;
            }
        }
        return best;
    }

    /** Регионы, пересекающиеся с данным (кроме него самого). */
    List<Region> overlapping(Region r) {
        List<Region> result = new ArrayList<>();
        for (Region o : byName.values()) {
            if (o != r && o.intersects(r)) {
                result.add(o);
            }
        }
        return result;
    }

    List<Region> ownedBy(UUID owner) {
        List<Region> result = new ArrayList<>();
        for (Region r : byName.values()) {
            if (owner.equals(r.owner)) {
                result.add(r);
            }
        }
        return result;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
