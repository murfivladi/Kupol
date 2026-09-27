package dev.vlad.core.modules.moderation;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.storage.PlayerData;
import dev.vlad.core.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Модерация: kick, ban/tempban/unban, mute/tempmute/unmute, warn/warns/clearwarns,
 * banip/unbanip, history.
 * Наказания хранятся в данных игрока (players/&lt;uuid&gt;.yml, секция moderation).
 */
public final class ModerationModule extends Module implements Listener {

    private static final String BAN = "ban";
    private static final String MUTE = "mute";
    private final ModTools tools;
    private final IpBans ipBans;

    public ModerationModule(VladCore plugin) {
        super(plugin, "moderation");
        this.tools = new ModTools(plugin);
        this.ipBans = new IpBans(plugin);
    }

    @Override
    protected void onEnable() {
        ipBans.load();
        listen(this);
        placeholder("warns", p -> String.valueOf(plugin.players().get(p.getUniqueId()).getMapList("moderation.warns").size()));
        placeholder("muted", p -> yesNo(Punishment.read(plugin.players().get(p.getUniqueId()), MUTE) != null));
        placeholder("mute_time", p -> {
            Punishment mute = Punishment.read(plugin.players().get(p.getUniqueId()), MUTE);
            return mute == null ? "" : tools.time(mute);
        });
        placeholder("banned", p -> yesNo(Punishment.read(plugin.players().get(p.getUniqueId()), BAN) != null));
        command(new KickCommand());
        command(new PunishCommand("ban", "vcore.ban", BAN, false));
        command(new PunishCommand("tempban", "vcore.tempban", BAN, true));
        command(new PunishCommand("mute", "vcore.mute", MUTE, false));
        command(new PunishCommand("tempmute", "vcore.tempmute", MUTE, true));
        command(new PardonCommand("unban", "vcore.unban", BAN, "pardon"));
        command(new PardonCommand("unmute", "vcore.unmute", MUTE));
        command(new WarnCommand());
        command(new WarnsCommand());
        command(new ClearWarnsCommand());
        command(new BanIpCommand());
        command(new UnbanIpCommand());
        command(new HistoryCommand());
    }

    // ---------------- общее ----------------

    private String banScreen(Punishment ban) {
        return plugin.messages().get(ban.permanent() ? "moderation.ban-screen" : "moderation.tempban-screen",
                "reason", ban.reason, "by", ban.by, "time", tools.time(ban));
    }

    // ---------------- события ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        Punishment ban = Punishment.read(plugin.players().get(event.getUniqueId()), BAN);
        if (ban == null) {
            ban = ipBans.get(event.getAddress().getHostAddress());
        }
        if (ban != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(ban));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.players().get(player.getUniqueId()).set("moderation.ip", ip(player));
    }

    private static String ip(Player player) {
        return player.getAddress().getAddress().getHostAddress();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Punishment mute = Punishment.read(plugin.players().get(event.getPlayer().getUniqueId()), MUTE);
        if (mute != null) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "moderation.you-are-muted", "reason", mute.reason, "time", tools.time(mute));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        label = label.substring(label.indexOf(':') + 1); // "minecraft:msg" → "msg"
        if (!plugin.getConfig().getStringList("moderation.mute-blocked-commands").contains(label)) {
            return;
        }
        Punishment mute = Punishment.read(plugin.players().get(event.getPlayer().getUniqueId()), MUTE);
        if (mute != null) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "moderation.you-are-muted", "reason", mute.reason, "time", tools.time(mute));
        }
    }

    // ---------------- команды ----------------

    private abstract class TargetCommand extends ModuleCommand {
        TargetCommand(String name, String permission, String usage, String... aliases) {
            super(ModerationModule.this.plugin, name, permission, usage, aliases);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 && sender.hasPermission(getPermission())
                    ? onlineNames(sender, args[0]) : Collections.emptyList();
        }
    }

    private final class KickCommand extends TargetCommand {
        KickCommand() {
            super("kick", "vcore.kick", "/kick <игрок> [причина]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                plugin.messages().send(sender, "player-not-found", "player", args[0]);
                return;
            }
            if (tools.exempt(sender, target)) {
                return;
            }
            String reason = tools.reason(args, 1);
            target.kickPlayer(plugin.messages().get("moderation.kick-screen", "reason", reason, "by", sender.getName()));
            Punishment.log(plugin.players().get(target.getUniqueId()), "kick", reason, sender.getName(), 0);
            tools.announce(sender, "moderation.kicked", "player", target.getName(), "by", sender.getName(), "reason", reason);
        }
    }

    /** ban, tempban, mute, tempmute. */
    private final class PunishCommand extends TargetCommand {
        private final String type;
        private final boolean temp;

        PunishCommand(String name, String permission, String type, boolean temp) {
            super(name, permission, "/" + name + " <игрок>" + (temp ? " <время: 1d12h30m>" : "") + " [причина]");
            this.type = type;
            this.temp = temp;
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length < (temp ? 2 : 1)) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            OfflinePlayer target = tools.find(sender, args[0]);
            if (target == null || tools.exempt(sender, target)) {
                return;
            }
            long duration = 0;
            if (temp) {
                duration = Durations.parse(args[1]);
                if (duration < 0) {
                    plugin.messages().send(sender, "moderation.bad-time");
                    return;
                }
            }
            long now = System.currentTimeMillis();
            Punishment p = new Punishment(tools.reason(args, temp ? 2 : 1), sender.getName(), now, temp ? now + duration : 0);
            PlayerData data = plugin.players().get(target.getUniqueId());
            p.write(data, type);
            Punishment.log(data, type, p.reason, sender.getName(), duration);

            String name = String.valueOf(target.getName());
            if (BAN.equals(type)) {
                if (target.getPlayer() != null) {
                    target.getPlayer().kickPlayer(banScreen(p));
                }
                tools.announce(sender, temp ? "moderation.tempbanned" : "moderation.banned",
                        "player", name, "by", sender.getName(), "reason", p.reason, "time", tools.time(p));
            } else {
                if (target.getPlayer() != null) {
                    plugin.messages().send(target.getPlayer(), "moderation.you-are-muted", "reason", p.reason, "time", tools.time(p));
                }
                tools.announce(sender, temp ? "moderation.tempmuted" : "moderation.muted",
                        "player", name, "by", sender.getName(), "reason", p.reason, "time", tools.time(p));
            }
        }
    }

    private final class PardonCommand extends TargetCommand {
        private final String type;

        PardonCommand(String name, String permission, String type, String... aliases) {
            super(name, permission, "/" + name + " <игрок>", aliases);
            this.type = type;
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            OfflinePlayer target = tools.find(sender, args[0]);
            if (target == null) {
                return;
            }
            PlayerData data = plugin.players().get(target.getUniqueId());
            String name = String.valueOf(target.getName());
            if (Punishment.read(data, type) == null) {
                plugin.messages().send(sender, BAN.equals(type) ? "moderation.not-banned" : "moderation.not-muted",
                        "player", name);
                return;
            }
            data.set("moderation." + type, null);
            Punishment.log(data, "un" + type, "", sender.getName(), 0);
            tools.announce(sender, BAN.equals(type) ? "moderation.unbanned" : "moderation.unmuted",
                    "player", name, "by", sender.getName());
            if (target.getPlayer() != null && MUTE.equals(type)) {
                plugin.messages().send(target.getPlayer(), "moderation.you-are-unmuted");
            }
        }
    }

    private final class WarnCommand extends TargetCommand {
        WarnCommand() {
            super("warn", "vcore.warn", "/warn <игрок> [причина]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            OfflinePlayer target = tools.find(sender, args[0]);
            if (target == null || tools.exempt(sender, target)) {
                return;
            }
            String reason = tools.reason(args, 1);
            PlayerData data = plugin.players().get(target.getUniqueId());
            List<Map<?, ?>> warns = new ArrayList<>(data.getMapList("moderation.warns"));
            Map<String, Object> warn = new LinkedHashMap<>();
            warn.put("reason", reason);
            warn.put("by", sender.getName());
            warn.put("at", System.currentTimeMillis());
            warns.add(warn);
            data.set("moderation.warns", warns);
            Punishment.log(data, "warn", reason, sender.getName(), 0);

            String name = String.valueOf(target.getName());
            String count = String.valueOf(warns.size());
            tools.announce(sender, "moderation.warned", "player", name, "by", sender.getName(), "reason", reason, "count", count);
            if (target.getPlayer() != null) {
                plugin.messages().send(target.getPlayer(), "moderation.you-are-warned", "reason", reason, "count", count);
            }
            // Автонаказание: moderation.warn-actions.<число варнов> — команда от консоли.
            String action = plugin.getConfig().getString("moderation.warn-actions." + warns.size());
            if (action != null && !action.isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.replace("{player}", name));
            }
        }
    }

    private final class WarnsCommand extends TargetCommand {
        WarnsCommand() {
            super("warns", "vcore.warns", "/warns [игрок]", "warnings");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            OfflinePlayer target;
            if (args.length > 0) {
                if (!sender.hasPermission("vcore.warns.others")) {
                    plugin.messages().send(sender, "no-permission");
                    return;
                }
                target = tools.find(sender, args[0]);
            } else {
                target = player(sender);
            }
            if (target == null) {
                return;
            }
            List<Map<?, ?>> warns = plugin.players().get(target.getUniqueId()).getMapList("moderation.warns");
            String name = String.valueOf(target.getName());
            if (warns.isEmpty()) {
                plugin.messages().send(sender, "moderation.no-warns", "player", name);
                return;
            }
            plugin.messages().send(sender, "moderation.warns-header", "player", name, "count", String.valueOf(warns.size()));
            SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy HH:mm");
            for (int i = 0; i < warns.size(); i++) {
                Map<?, ?> w = warns.get(i);
                Object at = w.get("at");
                sender.sendMessage(plugin.messages().get("moderation.warns-line",
                        "n", String.valueOf(i + 1),
                        "reason", String.valueOf(w.get("reason")),
                        "by", String.valueOf(w.get("by")),
                        "date", at instanceof Number ? date.format(new Date(((Number) at).longValue())) : "?"));
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 && sender.hasPermission("vcore.warns.others")
                    ? onlineNames(sender, args[0]) : Collections.emptyList();
        }
    }

    private final class ClearWarnsCommand extends TargetCommand {
        ClearWarnsCommand() {
            super("clearwarns", "vcore.clearwarns", "/clearwarns <игрок>");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            OfflinePlayer target = tools.find(sender, args[0]);
            if (target == null) {
                return;
            }
            plugin.players().get(target.getUniqueId()).set("moderation.warns", null);
            plugin.messages().send(sender, "moderation.warns-cleared", "player", String.valueOf(target.getName()));
        }
    }

    private static final Pattern IP = Pattern.compile("[0-9a-fA-F.:]+");

    /** Аргумент — IP напрямую, либо ник (берём его последний IP). null с сообщением. */
    private String resolveIp(CommandSender sender, String arg) {
        if (IP.matcher(arg).matches() && (arg.contains(".") || arg.contains(":"))) {
            return arg;
        }
        OfflinePlayer target = tools.find(sender, arg);
        if (target == null) {
            return null;
        }
        String ip = target.getPlayer() != null ? ip(target.getPlayer())
                : plugin.players().get(target.getUniqueId()).getString("moderation.ip");
        if (ip == null) {
            plugin.messages().send(sender, "moderation.no-ip", "player", arg);
        }
        return ip;
    }

    private final class BanIpCommand extends TargetCommand {
        BanIpCommand() {
            super("banip", "vcore.banip", "/banip <игрок|IP> [причина]", "ipban");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            Player named = Bukkit.getPlayerExact(args[0]);
            if (named != null && tools.exempt(sender, named)) {
                return;
            }
            String ip = resolveIp(sender, args[0]);
            if (ip == null) {
                return;
            }
            Punishment p = new Punishment(tools.reason(args, 1), sender.getName(), System.currentTimeMillis(), 0);
            ipBans.ban(ip, p);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (ip.equals(ip(online))) {
                    Punishment.log(plugin.players().get(online.getUniqueId()), "banip", p.reason, sender.getName(), 0);
                    online.kickPlayer(banScreen(p));
                }
            }
            tools.announce(sender, "moderation.ipbanned", "player", args[0], "by", sender.getName(), "reason", p.reason);
        }
    }

    private final class UnbanIpCommand extends TargetCommand {
        UnbanIpCommand() {
            super("unbanip", "vcore.unbanip", "/unbanip <игрок|IP>", "pardonip");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            String ip = resolveIp(sender, args[0]);
            if (ip == null) {
                return;
            }
            if (!ipBans.unban(ip)) {
                plugin.messages().send(sender, "moderation.ip-not-banned", "player", args[0]);
                return;
            }
            tools.announce(sender, "moderation.ipunbanned", "player", args[0], "by", sender.getName());
        }
    }

    private final class HistoryCommand extends TargetCommand {
        private static final int PAGE = 10;

        HistoryCommand() {
            super("history", "vcore.history", "/history <игрок> [страница]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            OfflinePlayer target = tools.find(sender, args[0]);
            if (target == null) {
                return;
            }
            List<Map<?, ?>> history = new ArrayList<>(
                    plugin.players().get(target.getUniqueId()).getMapList("moderation.history"));
            String name = String.valueOf(target.getName());
            if (history.isEmpty()) {
                plugin.messages().send(sender, "moderation.history-empty", "player", name);
                return;
            }
            Collections.reverse(history); // свежие сверху
            int pages = (history.size() + PAGE - 1) / PAGE;
            int page = 1;
            if (args.length > 1) {
                try {
                    page = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {
                    // первая страница
                }
            }
            page = Math.min(Math.max(page, 1), pages);
            plugin.messages().send(sender, "moderation.history-header", "player", name,
                    "count", String.valueOf(history.size()), "page", String.valueOf(page), "pages", String.valueOf(pages));
            SimpleDateFormat date = new SimpleDateFormat("dd.MM.yy HH:mm");
            for (int i = (page - 1) * PAGE; i < Math.min(page * PAGE, history.size()); i++) {
                Map<?, ?> e = history.get(i);
                Object at = e.get("at");
                Object duration = e.get("duration");
                long ms = duration instanceof Number ? ((Number) duration).longValue() : 0;
                String type = String.valueOf(e.get("type"));
                sender.sendMessage(plugin.messages().get("moderation.history-line",
                        "date", at instanceof Number ? date.format(new Date(((Number) at).longValue())) : "?",
                        "type", plugin.messages().get("moderation.types." + type),
                        "time", ms > 0 ? " (" + Durations.format(ms) + ")" : "",
                        "by", String.valueOf(e.get("by")),
                        "reason", String.valueOf(e.get("reason"))));
            }
        }
    }
}
