package dev.evoday.crates.crate;

import dev.evoday.crates.util.Messages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CrateRegistry {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Crate> crates = new LinkedHashMap<>();

    public CrateRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "crates.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("crates.yml", false);
        }
        crates.clear();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("crates");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            try {
                crates.put(id.toLowerCase(Locale.ROOT), parse(id.toLowerCase(Locale.ROOT), root.getConfigurationSection(id)));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Crate '" + id + "' skipped: " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded crates: " + crates.size());
    }

    private Crate parse(String id, ConfigurationSection s) {
        Crate.Animation animation = Crate.Animation.valueOf(s.getString("animation", "roulette").toUpperCase(Locale.ROOT));
        List<Reward> rewards = new ArrayList<>();
        ConfigurationSection rs = s.getConfigurationSection("rewards");
        if (rs != null) {
            for (String rid : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(rid);
                ConfigurationSection item = r.getConfigurationSection("item");
                if (item == null) {
                    throw new IllegalArgumentException("reward " + rid + " has no item");
                }
                int weight = r.getInt("weight", 1);
                if (weight <= 0) {
                    continue;
                }
                rewards.add(new Reward(rid, weight, r.getString("rarity", "common"), ItemParser.parse(item),
                        r.getBoolean("give-item", true), r.getStringList("commands"), r.getBoolean("broadcast")));
            }
        }
        if (rewards.isEmpty()) {
            throw new IllegalArgumentException("no rewards");
        }
        return new Crate(id,
                Messages.MM.deserialize(s.getString("name", id)),
                animation,
                s.getStringList("hologram").stream().map(Messages.MM::deserialize).toList(),
                List.copyOf(rewards));
    }

    public Crate get(String id) {
        return id == null ? null : crates.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Crate> all() {
        return crates.values();
    }

    // /crates additem - дописываем предмет из руки прямо в crates.yml
    public String addItem(String crateId, ItemStack item, int weight, String rarity) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String base = "crates." + crateId + ".rewards.";
        int n = 1;
        while (yaml.contains(base + "item" + n)) {
            n++;
        }
        String id = "item" + n;
        yaml.set(base + id + ".weight", weight);
        yaml.set(base + id + ".rarity", rarity);
        yaml.set(base + id + ".item.data", ItemParser.encode(item));
        yaml.save(file);
        return id;
    }
}
