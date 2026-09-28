package dev.vlad.core.modules.clans;

import dev.vlad.core.VladCore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/** Кланы в clans.yml + индекс "игрок → клан". */
final class ClanStore {

    private final VladCore plugin;
    private final File file;
    private final Map<String, Clan> clans = new TreeMap<>();
    private final Map<UUID, Clan> byPlayer = new HashMap<>();

    ClanStore(VladCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "clans.yml");
    }

    void load() {
        clans.clear();
        byPlayer.clear();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("clans");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            Clan clan = new Clan(s.getString("name", id), s.getString("tag", id));
            ConfigurationSection members = s.getConfigurationSection("members");
            if (members != null) {
                for (String uuid : members.getKeys(false)) {
                    try {
                        clan.members.put(UUID.fromString(uuid), Clan.Rank.valueOf(members.getString(uuid, "MEMBER")));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("clans.yml: плохая запись участника " + uuid + " в " + id);
                    }
                }
            }
            clan.home = s.getLocation("home");
            clan.bank = s.getDouble("bank");
            clan.friendlyFire = s.getBoolean("friendly-fire");
            clan.open = s.getBoolean("open");
            clan.motto = s.getString("motto", "");
            clan.icon = s.getString("icon", "WHITE_BANNER");
            clan.created = s.getLong("created");
            add(clan);
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Clan c : clans.values()) {
            String p = "clans." + c.id + ".";
            yaml.set(p + "name", c.name);
            yaml.set(p + "tag", c.tag);
            c.members.forEach((uuid, rank) -> yaml.set(p + "members." + uuid, rank.name()));
            yaml.set(p + "home", c.home);
            yaml.set(p + "bank", c.bank);
            yaml.set(p + "friendly-fire", c.friendlyFire);
            yaml.set(p + "open", c.open);
            yaml.set(p + "motto", c.motto);
            yaml.set(p + "icon", c.icon);
            yaml.set(p + "created", c.created);
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить clans.yml", e);
        }
    }

    void add(Clan clan) {
        clans.put(clan.id, clan);
        clan.members.keySet().forEach(uuid -> byPlayer.put(uuid, clan));
    }

    void remove(Clan clan) {
        clans.remove(clan.id);
        clan.members.keySet().forEach(byPlayer::remove);
    }

    void join(Clan clan, UUID player, Clan.Rank rank) {
        clan.members.put(player, rank);
        byPlayer.put(player, clan);
    }

    void leave(Clan clan, UUID player) {
        clan.members.remove(player);
        byPlayer.remove(player);
    }

    Clan get(String name) {
        return clans.get(name.toLowerCase());
    }

    Clan of(UUID player) {
        return byPlayer.get(player);
    }

    Collection<Clan> all() {
        return clans.values();
    }

    /** Топ: по казне, при равенстве — по числу участников. */
    List<Clan> top() {
        List<Clan> list = new ArrayList<>(clans.values());
        list.sort(Comparator.comparingDouble((Clan c) -> c.bank).reversed()
                .thenComparing(Comparator.comparingInt((Clan c) -> c.members.size()).reversed()));
        return list;
    }
}
