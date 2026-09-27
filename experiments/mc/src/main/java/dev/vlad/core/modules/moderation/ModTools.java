package dev.vlad.core.modules.moderation;

import dev.vlad.core.VladCore;
import dev.vlad.core.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

/** Общие хелперы модулей модерации (moderation, jail). */
final class ModTools {

    static final String EXEMPT = "vcore.moderation.exempt";
    static final String NOTIFY = "vcore.moderation.notify";

    private final VladCore plugin;

    ModTools(VladCore plugin) {
        this.plugin = plugin;
    }

    /** Онлайн-игрок или заходивший раньше; null с сообщением, если не найден. */
    OfflinePlayer find(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached;
        }
        plugin.messages().send(sender, "player-not-found", "player", name);
        return null;
    }

    /** Нельзя наказывать защищённых (кроме как из консоли). Офлайн-защита — только для операторов. */
    boolean exempt(CommandSender sender, OfflinePlayer target) {
        if (!(sender instanceof Player)) {
            return false;
        }
        boolean isExempt = target.getPlayer() != null ? target.getPlayer().hasPermission(EXEMPT) : target.isOp();
        if (isExempt) {
            plugin.messages().send(sender, "moderation.exempt", "player", String.valueOf(target.getName()));
        }
        return isExempt;
    }

    String reason(String[] args, int from) {
        if (args.length <= from) {
            return plugin.messages().get("moderation.no-reason");
        }
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    String time(Punishment p) {
        return p.permanent() ? plugin.messages().get("moderation.forever") : Durations.format(p.remaining());
    }

    /** "perm"/"навсегда" → 0, иначе длительность; -1 — не распознано. */
    long parseTime(String arg) {
        String a = arg.toLowerCase();
        if (a.equals("perm") || a.equals("forever") || a.equals("навсегда")) {
            return 0;
        }
        return Durations.parse(a);
    }

    /** Оповещение: всем, либо только отправителю и держателям vcore.moderation.notify. */
    void announce(CommandSender sender, String key, String... placeholders) {
        String text = plugin.messages().get("prefix") + plugin.messages().get(key, placeholders);
        if (plugin.getConfig().getBoolean("moderation.broadcast", true)) {
            Bukkit.broadcastMessage(text);
            return;
        }
        Bukkit.getConsoleSender().sendMessage(text);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == sender || p.hasPermission(NOTIFY)) {
                p.sendMessage(text);
            }
        }
    }
}
