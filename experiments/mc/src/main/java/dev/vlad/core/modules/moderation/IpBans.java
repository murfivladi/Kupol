package dev.vlad.core.modules.moderation;

import dev.vlad.core.VladCore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Баны по IP в ipbans.yml. Хранятся списком: в IP есть точки,
 * а точка в YAML-пути Bukkit — разделитель секций.
 */
final class IpBans {

    private final VladCore plugin;
    private final File file;
    private final Map<String, Punishment> bans = new ConcurrentHashMap<>();

    IpBans(VladCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ipbans.yml");
    }

    void load() {
        bans.clear();
        for (Map<?, ?> m : YamlConfiguration.loadConfiguration(file).getMapList("bans")) {
            Object ip = m.get("ip");
            if (ip != null) {
                bans.put(ip.toString(), new Punishment(String.valueOf(m.get("reason")), String.valueOf(m.get("by")),
                        number(m.get("at")), number(m.get("until"))));
            }
        }
    }

    private static long number(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    private void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        bans.forEach((ip, p) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", ip);
            m.put("reason", p.reason);
            m.put("by", p.by);
            m.put("at", p.at);
            m.put("until", p.until);
            list.add(m);
        });
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("bans", list);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить ipbans.yml", e);
        }
    }

    /** Действующий бан IP или null; истёкший удаляется. */
    Punishment get(String ip) {
        Punishment p = bans.get(ip);
        if (p != null && !p.permanent() && p.remaining() <= 0) {
            bans.remove(ip);
            save();
            return null;
        }
        return p;
    }

    void ban(String ip, Punishment p) {
        bans.put(ip, p);
        save();
    }

    boolean unban(String ip) {
        if (bans.remove(ip) == null) {
            return false;
        }
        save();
        return true;
    }
}
