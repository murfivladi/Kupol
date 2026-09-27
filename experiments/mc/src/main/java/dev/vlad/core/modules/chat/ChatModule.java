package dev.vlad.core.modules.chat;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.storage.PlayerData;
import dev.vlad.core.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Чат: формат с префиксами LuckPerms, цвета, упоминания, антиспам,
 * /msg, /r, /socialspy, /nick, /ignore, /broadcast, сообщения входа/выхода.
 */
public final class ChatModule extends Module implements Listener {

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)&([0-9a-fk-or]|#[0-9a-f]{6})");

    /** Кому отвечает /r: игрок → последний собеседник. */
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessageText = new ConcurrentHashMap<>();
    private final Set<UUID> socialSpy = ConcurrentHashMap.newKeySet();

    public ChatModule(VladCore plugin) {
        super(plugin, "chat");
    }

    @Override
    protected void onEnable() {
        if (!plugin.meta().available()) {
            plugin.getLogger().warning("Vault Chat не найден — префиксы в чате не будут показываться");
        }
        listen(this);
        placeholder("nick", p -> {
            String nick = data(p.getUniqueId()).getString("chat.nick");
            return nick != null ? Messages.color(nick) : String.valueOf(p.getName());
        });
        placeholder("socialspy", p -> yesNo(socialSpy.contains(p.getUniqueId())));
        command(new MsgCommand());
        command(new ReplyCommand());
        command(new SocialSpyCommand());
        command(new NickCommand());
        command(new IgnoreCommand());
        command(new BroadcastCommand());
        Bukkit.getOnlinePlayers().forEach(this::applyNick);
    }

    // ---------------- общее ----------------

    /** Цвета игрока: только с правом, иначе коды остаются как есть. */
    private static String playerText(Player player, String text, String permission) {
        return player.hasPermission(permission) ? Messages.color(text) : text;
    }

    private static String stripCodes(String text) {
        return COLOR_CODES.matcher(text).replaceAll("");
    }

    private PlayerData data(UUID id) {
        return plugin.players().get(id);
    }

    private boolean ignores(Player who, Player whom) {
        return !whom.hasPermission("vcore.ignore.exempt")
                && data(who.getUniqueId()).getMapList("chat.ignore-list").stream()
                .anyMatch(m -> whom.getUniqueId().toString().equals(m.get("uuid")));
    }

    private void applyNick(Player player) {
        String nick = data(player.getUniqueId()).getString("chat.nick");
        String display = nick == null ? player.getName() : Messages.color(nick) + ChatColor.RESET;
        player.setDisplayName(display);
        player.setPlayerListName(display);
    }

    // ---------------- события ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (antiSpam(player, event.getMessage())) {
            event.setCancelled(true);
            return;
        }
        String message = playerText(player, event.getMessage(), "vcore.chat.color");

        // Упоминания: подсветка ника и звук упомянутому.
        String highlight = plugin.getConfig().getString("chat.mention-color", "&e");
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == player || !player.canSee(other)) {
                continue;
            }
            Matcher m = Pattern.compile("(?i)@?\\b" + Pattern.quote(other.getName()) + "\\b").matcher(message);
            if (m.find()) {
                message = m.replaceAll(Matcher.quoteReplacement(
                        Messages.color(highlight + "@" + other.getName()) + ChatColor.RESET));
                if (!ignores(other, player)) {
                    Bukkit.getScheduler().runTask(plugin,
                            () -> other.playSound(other.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f));
                }
            }
        }

        String prefix = plugin.meta().prefix(player);
        String suffix = plugin.meta().suffix(player);
        String template = plugin.placeholders().apply(player,
                plugin.getConfig().getString("chat.format", "{prefix}{name}{suffix}&7: &f{message}"));
        String format = Messages.color(template
                .replace("{prefix}", prefix)
                .replace("{suffix}", suffix)
                .replace("{world}", player.getWorld().getName()));
        // % в префиксах сломал бы String.format внутри Bukkit.
        format = format.replace("%", "%%").replace("{name}", "%1$s").replace("{message}", "%2$s");
        event.setFormat(format);
        event.setMessage(message);
        event.getRecipients().removeIf(r -> ignores(r, player));
    }

    /** true — сообщение отклонено (слишком часто или повтор). */
    private boolean antiSpam(Player player, String message) {
        if (player.hasPermission("vcore.chat.bypass-spam")) {
            return false;
        }
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("chat.cooldown-ms", 1500);
        Long last = lastMessageAt.get(player.getUniqueId());
        if (last != null && now - last < cooldown) {
            plugin.messages().send(player, "chat.too-fast");
            return true;
        }
        if (plugin.getConfig().getBoolean("chat.block-repeats", true)
                && message.equalsIgnoreCase(lastMessageText.get(player.getUniqueId()))) {
            plugin.messages().send(player, "chat.repeat");
            return true;
        }
        lastMessageAt.put(player.getUniqueId(), now);
        lastMessageText.put(player.getUniqueId(), message);
        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        applyNick(player);
        if (data(player.getUniqueId()).getString("chat.socialspy") != null) {
            socialSpy.add(player.getUniqueId());
        }
        String format = plugin.getConfig().getString("chat.join-message", "");
        if (!format.isEmpty()) {
            event.setJoinMessage(Messages.color(plugin.placeholders().apply(player, format)
                    .replace("{name}", player.getDisplayName())));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        lastPartner.remove(id);
        lastMessageAt.remove(id);
        lastMessageText.remove(id);
        socialSpy.remove(id);
        String format = plugin.getConfig().getString("chat.quit-message", "");
        if (!format.isEmpty()) {
            event.setQuitMessage(Messages.color(plugin.placeholders().apply(player, format)
                    .replace("{name}", player.getDisplayName())));
        }
    }

    // ---------------- личные сообщения ----------------

    private void sendPrivate(CommandSender from, Player to, String text) {
        if (from instanceof Player && ignores(to, (Player) from)) {
            plugin.messages().send(from, "chat.ignored-by", "player", to.getName());
            return;
        }
        String body = from instanceof Player ? playerText((Player) from, text, "vcore.chat.color") : Messages.color(text);
        String fromName = from instanceof Player ? ((Player) from).getDisplayName() : plugin.messages().get("chat.console");
        from.sendMessage(plugin.messages().get("chat.msg-to", "player", to.getDisplayName(), "message", body));
        to.sendMessage(plugin.messages().get("chat.msg-from", "player", fromName, "message", body));
        if (from instanceof Player) {
            lastPartner.put(((Player) from).getUniqueId(), to.getUniqueId());
            lastPartner.put(to.getUniqueId(), ((Player) from).getUniqueId());
        }
        String spy = plugin.messages().get("chat.spy", "from", fromName, "to", to.getDisplayName(), "message", body);
        for (UUID id : socialSpy) {
            Player spyPlayer = Bukkit.getPlayer(id);
            if (spyPlayer != null && spyPlayer != from && spyPlayer != to) {
                spyPlayer.sendMessage(spy);
            }
        }
    }

    private final class MsgCommand extends ModuleCommand {
        MsgCommand() {
            super(ChatModule.this.plugin, "msg", "vcore.msg", "/msg <игрок> <сообщение>",
                    "tell", "w", "m", "whisper", "pm");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length < 2) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            Player target = visible(sender, args[0]);
            if (target == null) {
                plugin.messages().send(sender, "player-not-found", "player", args[0]);
                return;
            }
            if (target == sender) {
                plugin.messages().send(sender, "chat.msg-self");
                return;
            }
            sendPrivate(sender, target, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? onlineNames(sender, args[0]) : Collections.emptyList();
        }
    }

    private final class ReplyCommand extends ModuleCommand {
        ReplyCommand() {
            super(ChatModule.this.plugin, "reply", "vcore.msg", "/r <сообщение>", "r");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            UUID partner = lastPartner.get(player.getUniqueId());
            Player target = partner == null ? null : Bukkit.getPlayer(partner);
            if (target == null || !player.canSee(target)) {
                plugin.messages().send(sender, "chat.no-reply");
                return;
            }
            sendPrivate(player, target, String.join(" ", args));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private final class SocialSpyCommand extends ModuleCommand {
        SocialSpyCommand() {
            super(ChatModule.this.plugin, "socialspy", "vcore.socialspy", "/socialspy", "spy");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            boolean on = socialSpy.add(player.getUniqueId());
            if (!on) {
                socialSpy.remove(player.getUniqueId());
            }
            data(player.getUniqueId()).set("chat.socialspy", on ? "true" : null);
            plugin.messages().send(player, on ? "chat.spy-on" : "chat.spy-off");
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    // ---------------- ники ----------------

    private final class NickCommand extends ModuleCommand {
        NickCommand() {
            super(ChatModule.this.plugin, "nick", "vcore.nick", "/nick <ник|off> [игрок]", "nickname");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            Player target = target(sender, args, 1);
            if (target == null) {
                return;
            }
            PlayerData data = data(target.getUniqueId());
            if (args[0].equalsIgnoreCase("off")) {
                data.set("chat.nick", null);
                applyNick(target);
                plugin.messages().send(sender, "chat.nick-reset", "player", target.getName());
                return;
            }
            String nick = args[0];
            if (!sender.hasPermission("vcore.nick.color")) {
                nick = stripCodes(nick);
            }
            String plain = stripCodes(nick);
            int max = plugin.getConfig().getInt("chat.nick-max-length", 16);
            if (plain.isEmpty() || plain.length() > max || !plain.matches("[a-zA-Z0-9_а-яА-ЯёЁ]+")) {
                plugin.messages().send(sender, "chat.nick-bad", "max", String.valueOf(max));
                return;
            }
            // Нельзя притвориться другим игроком.
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other != target && other.getName().equalsIgnoreCase(plain)) {
                    plugin.messages().send(sender, "chat.nick-taken");
                    return;
                }
            }
            data.set("chat.nick", nick);
            applyNick(target);
            plugin.messages().send(sender, "chat.nick-set", "player", target.getName(), "nick", target.getDisplayName());
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) {
                return Collections.singletonList("off");
            }
            return args.length == 2 && sender.hasPermission("vcore.nick.others")
                    ? onlineNames(sender, args[1]) : Collections.emptyList();
        }
    }

    // ---------------- игнор ----------------

    private final class IgnoreCommand extends ModuleCommand {
        IgnoreCommand() {
            super(ChatModule.this.plugin, "ignore", "vcore.ignore", "/ignore <игрок> | /ignore list", "unignore");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            PlayerData data = data(player.getUniqueId());
            List<Map<?, ?>> list = new ArrayList<>(data.getMapList("chat.ignore-list"));
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                if (list.isEmpty()) {
                    plugin.messages().send(player, "chat.ignore-empty");
                } else {
                    List<String> names = new ArrayList<>();
                    list.forEach(m -> names.add(String.valueOf(m.get("name"))));
                    plugin.messages().send(player, "chat.ignore-list", "players", String.join(", ", names));
                }
                return;
            }
            Player target = visible(sender, args[0]);
            if (target == null) {
                plugin.messages().send(sender, "player-not-found", "player", args[0]);
                return;
            }
            if (target == player) {
                plugin.messages().send(sender, "chat.ignore-self");
                return;
            }
            String id = target.getUniqueId().toString();
            boolean removed = list.removeIf(m -> id.equals(m.get("uuid")));
            if (!removed) {
                if (target.hasPermission("vcore.ignore.exempt")) {
                    plugin.messages().send(sender, "chat.ignore-exempt");
                    return;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("uuid", id);
                entry.put("name", target.getName());
                list.add(entry);
            }
            data.set("chat.ignore-list", list.isEmpty() ? null : list);
            plugin.messages().send(player, removed ? "chat.unignored" : "chat.ignored", "player", target.getName());
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? onlineNames(sender, args[0]) : Collections.emptyList();
        }
    }

    // ---------------- объявления ----------------

    private final class BroadcastCommand extends ModuleCommand {
        BroadcastCommand() {
            super(ChatModule.this.plugin, "broadcast", "vcore.broadcast", "/broadcast <сообщение>", "bc", "bcast");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            Bukkit.broadcastMessage(plugin.messages().get("chat.broadcast", "message", Messages.color(String.join(" ", args))));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }
}
