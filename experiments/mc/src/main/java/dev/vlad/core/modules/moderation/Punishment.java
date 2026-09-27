package dev.vlad.core.modules.moderation;

import dev.vlad.core.storage.PlayerData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Бан или мут в данных игрока: moderation.&lt;type&gt;.{reason, by, at, until}. until = 0 — навсегда. */
final class Punishment {

    final String reason;
    final String by;
    final long at;
    final long until;

    Punishment(String reason, String by, long at, long until) {
        this.reason = reason;
        this.by = by;
        this.at = at;
        this.until = until;
    }

    boolean permanent() {
        return until == 0;
    }

    long remaining() {
        return until - System.currentTimeMillis();
    }

    /** Действующее наказание, либо null. Истёкшее заодно стирается. */
    static Punishment read(PlayerData data, String type) {
        String path = "moderation." + type;
        if (!data.contains(path)) {
            return null;
        }
        Punishment p = new Punishment(data.getString(path + ".reason"), data.getString(path + ".by"),
                data.getLong(path + ".at", 0), data.getLong(path + ".until", 0));
        if (!p.permanent() && p.remaining() <= 0) {
            data.set(path, null);
            return null;
        }
        return p;
    }

    /** Запись в историю наказаний игрока (moderation.history). duration: 0 — навсегда/неприменимо. */
    static void log(PlayerData data, String type, String reason, String by, long duration) {
        List<Map<?, ?>> history = new ArrayList<>(data.getMapList("moderation.history"));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("reason", reason);
        entry.put("by", by);
        entry.put("at", System.currentTimeMillis());
        entry.put("duration", duration);
        history.add(entry);
        data.set("moderation.history", history);
    }

    void write(PlayerData data, String type) {
        String path = "moderation." + type;
        data.set(path + ".reason", reason);
        data.set(path + ".by", by);
        data.set(path + ".at", at);
        data.set(path + ".until", until);
    }
}
