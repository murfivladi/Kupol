package dev.vlad.core.modules.clans;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Кланы: создание, приглашения, звания (глава/офицер/участник), клан-хоум, казна,
 * клановый чат /cc, запрет урона по своим, тег в чате и табе через %vladcore_clan_tag%.
 */
public final class ClansModule extends Module implements Listener {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_а-яА-ЯёЁ]{3,16}");
    private static final Pattern COLOR = Pattern.compile("(?i)&[0-9a-fk-or]|&#[0-9a-f]{6}");
    private static final long INVITE_MS = 60_000;

    private final ClanStore store;
    /** Приглашённый → (id клана, когда истекает). */
    private final Map<UUID, Object[]> invites = new ConcurrentHashMap<>();
    /** Кто пишет в чат клана по умолчанию (/clan chat). */
    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();
    private ClanMenus menus;

    public ClansModule(VladCore plugin) {
        super(plugin, "clans");
        this.store = new ClanStore(plugin);
    }

    @Override
    protected void onEnable() {
        store.load();
        menus = new ClanMenus(this);
        listen(this);
        command(new ClanCommand());
        command(new ClanChatCommand());
        placeholder("clan_name", p -> { Clan c = store.of(p.getUniqueId()); return c == null ? "" : c.name; });
        placeholder("clan_tag", p -> {
            Clan c = store.of(p.getUniqueId());
            return c == null ? "" : Messages.color(plugin.getConfig().getString("clans.tag-format", "&8[{tag}&8] ")
                    .replace("{tag}", c.tag));
        });
        placeholder("clan_rank", p -> {
            Clan c = store.of(p.getUniqueId());
            return c == null ? "" : rankName(c.rank(p.getUniqueId()));
        });
        placeholder("clan_members", p -> { Clan c = store.of(p.getUniqueId()); return c == null ? "0" : String.valueOf(c.members.size()); });
        placeholder("clan_bank", p -> { Clan c = store.of(p.getUniqueId()); return c == null ? "" : plugin.meta().format(c.bank); });
        placeholderPrefix("clantop_name_", (p, n) -> topEntry(n, c -> c.name));
        placeholderPrefix("clantop_bank_", (p, n) -> topEntry(n, c -> plugin.meta().format(c.bank)));
        plugin.getLogger().info("Кланов загружено: " + store.all().size());
    }

    // ---- для меню (ClanMenus) ----

    ClanStore store() {
        return store;
    }

    VladCore core() {
        return plugin;
    }

    /** Клан, в который игрок приглашён (действующее приглашение), либо null. */
    Clan pendingInvite(UUID player) {
        Object[] inv = invites.get(player);
        return inv == null || (long) inv[1] < System.currentTimeMillis() ? null : store.get((String) inv[0]);
    }

    boolean clanChatMode(UUID player) {
        return chatMode.contains(player);
    }

    private String topEntry(String place, Function<Clan, String> text) {
        try {
            int n = Integer.parseInt(place);
            List<Clan> top = store.top();
            return n >= 1 && n <= top.size() ? text.apply(top.get(n - 1)) : "-";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    String rankName(Clan.Rank rank) {
        return rank == null ? "" : plugin.messages().get("clans.rank." + rank.name().toLowerCase(Locale.ROOT));
    }

    static String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    /** Сообщение всем онлайн-участникам клана. */
    private void broadcast(Clan clan, String key, String... placeholders) {
        for (UUID id : clan.members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.messages().send(p, key, placeholders);
            }
        }
    }

    // ---------------- урон по своим ----------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player attacker = e.getDamager() instanceof Player ? (Player) e.getDamager()
                : e.getDamager() instanceof Projectile && ((Projectile) e.getDamager()).getShooter() instanceof Player
                ? (Player) ((Projectile) e.getDamager()).getShooter() : null;
        if (attacker == null || attacker == e.getEntity()) {
            return;
        }
        Clan clan = store.of(attacker.getUniqueId());
        if (clan != null && !clan.friendlyFire && clan == store.of(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /** Режим кланового чата: обычные сообщения уходят в клан. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!chatMode.contains(e.getPlayer().getUniqueId())) {
            return;
        }
        Clan clan = store.of(e.getPlayer().getUniqueId());
        if (clan == null) {
            chatMode.remove(e.getPlayer().getUniqueId());
            return;
        }
        e.setCancelled(true);
        clanChat(e.getPlayer(), clan, e.getMessage());
    }

    void clanChat(Player player, Clan clan, String message) {
        String text = plugin.messages().get("clans.chat-format", "tag", Messages.color(clan.tag),
                "rank", rankName(clan.rank(player.getUniqueId())), "player", player.getName()) + message;
        for (UUID id : clan.members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(text);
            }
        }
    }

    // ---------------- /cc ----------------

    private final class ClanChatCommand extends ModuleCommand {
        ClanChatCommand() {
            super(ClansModule.this.plugin, "cc", "vcore.clans", "/cc <сообщение>", "clanchat");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            Clan clan = store.of(player.getUniqueId());
            if (clan == null) {
                plugin.messages().send(player, "clans.not-in-clan");
                return;
            }
            if (args.length == 0) {
                plugin.messages().send(player, "usage", "usage", getUsage());
                return;
            }
            clanChat(player, clan, String.join(" ", args));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    // ---------------- /clan ----------------

    private final class ClanCommand extends ModuleCommand {
        private final List<String> subs = Arrays.asList("create", "invite", "accept", "leave", "kick", "promote",
                "demote", "disband", "info", "top", "home", "sethome", "deposit", "withdraw", "ff", "tag",
                "join", "chat", "motto", "open", "icon", "menu", "help");

        ClanCommand() {
            super(ClansModule.this.plugin, "clan", "vcore.clans", "/clan <create|invite|accept|info|top|home|...>", "clans");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0 && sender instanceof Player) {
                menus.open((Player) sender);
                return;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                plugin.messages().send(sender, "clans.help");
                return;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            String[] a = Arrays.copyOfRange(args, 1, args.length);
            if (sub.equals("info")) {
                info(sender, a);
                return;
            }
            if (sub.equals("top")) {
                top(sender);
                return;
            }
            Player p = player(sender);
            if (p == null) {
                return;
            }
            switch (sub) {
                case "create": create(p, a); break;
                case "invite": invite(p, a); break;
                case "accept": accept(p); break;
                case "leave": leave(p); break;
                case "kick": kick(p, a); break;
                case "promote": setRank(p, a, true); break;
                case "demote": setRank(p, a, false); break;
                case "disband": disband(p, a); break;
                case "home": home(p); break;
                case "sethome": sethome(p); break;
                case "deposit": money(p, a, true); break;
                case "withdraw": money(p, a, false); break;
                case "ff": friendlyFire(p); break;
                case "tag": tag(p, a); break;
                case "join": join(p, a); break;
                case "chat": toggleChat(p); break;
                case "motto": motto(p, a); break;
                case "open": toggleOpen(p); break;
                case "icon": icon(p); break;
                case "menu": menus.open(p); break;
                default: plugin.messages().send(p, "clans.help");
            }
        }

        /** Клан игрока с проверкой звания; null — с сообщением. */
        private Clan myClan(Player p, Clan.Rank need) {
            Clan clan = store.of(p.getUniqueId());
            if (clan == null) {
                plugin.messages().send(p, "clans.not-in-clan");
                return null;
            }
            if (!clan.rank(p.getUniqueId()).atLeast(need)) {
                plugin.messages().send(p, "clans.need-rank", "rank", rankName(need));
                return null;
            }
            return clan;
        }

        /** Тег: цвета можно, видимых символов 2–6. */
        private String checkTag(Player p, String raw) {
            String plain = COLOR.matcher(raw).replaceAll("");
            if (!plain.matches("[a-zA-Z0-9а-яА-ЯёЁ]{2,6}")) {
                plugin.messages().send(p, "clans.bad-tag");
                return null;
            }
            String lower = plain.toLowerCase();
            for (Clan c : store.all()) {
                if (COLOR.matcher(c.tag).replaceAll("").equalsIgnoreCase(lower) && store.of(p.getUniqueId()) != c) {
                    plugin.messages().send(p, "clans.tag-taken");
                    return null;
                }
            }
            return p.hasPermission("vcore.clans.color-tag") ? raw : plain;
        }

        private void create(Player p, String[] a) {
            if (store.of(p.getUniqueId()) != null) {
                plugin.messages().send(p, "clans.already-in-clan");
                return;
            }
            if (a.length < 2) {
                plugin.messages().send(p, "usage", "usage", "/clan create <название> <тег>");
                return;
            }
            if (!NAME.matcher(a[0]).matches()) {
                plugin.messages().send(p, "clans.bad-name");
                return;
            }
            if (store.get(a[0]) != null) {
                plugin.messages().send(p, "clans.name-taken");
                return;
            }
            String tag = checkTag(p, a[1]);
            if (tag == null) {
                return;
            }
            double cost = plugin.getConfig().getDouble("clans.create-cost", 1000);
            if (cost > 0 && !plugin.meta().withdraw(p, cost)) {
                plugin.messages().send(p, "clans.not-enough", "amount", plugin.meta().format(cost));
                return;
            }
            Clan clan = new Clan(a[0], tag);
            clan.created = System.currentTimeMillis();
            clan.members.put(p.getUniqueId(), Clan.Rank.LEADER);
            store.add(clan);
            store.save();
            Bukkit.broadcastMessage(plugin.messages().get("prefix") + plugin.messages().get("clans.created",
                    "player", p.getName(), "clan", clan.name, "tag", Messages.color(clan.tag)));
        }

        private void invite(Player p, String[] a) {
            Clan clan = myClan(p, Clan.Rank.OFFICER);
            if (clan == null) {
                return;
            }
            if (a.length == 0) {
                plugin.messages().send(p, "usage", "usage", "/clan invite <игрок>");
                return;
            }
            Player target = visible(p, a[0]);
            if (target == null) {
                plugin.messages().send(p, "player-not-found", "player", a[0]);
                return;
            }
            if (store.of(target.getUniqueId()) != null) {
                plugin.messages().send(p, "clans.target-in-clan", "player", target.getName());
                return;
            }
            int max = plugin.getConfig().getInt("clans.max-members", 20);
            if (clan.members.size() >= max) {
                plugin.messages().send(p, "clans.full", "max", String.valueOf(max));
                return;
            }
            invites.put(target.getUniqueId(), new Object[]{clan.id, System.currentTimeMillis() + INVITE_MS});
            plugin.messages().send(p, "clans.invited", "player", target.getName());
            plugin.messages().send(target, "clans.invite", "clan", clan.name, "player", p.getName());
        }

        private void accept(Player p) {
            Object[] inv = invites.remove(p.getUniqueId());
            Clan clan = inv == null || (long) inv[1] < System.currentTimeMillis() ? null : store.get((String) inv[0]);
            if (clan == null) {
                plugin.messages().send(p, "clans.no-invite");
                return;
            }
            if (store.of(p.getUniqueId()) != null) {
                plugin.messages().send(p, "clans.already-in-clan");
                return;
            }
            store.join(clan, p.getUniqueId(), Clan.Rank.MEMBER);
            store.save();
            broadcast(clan, "clans.joined", "player", p.getName());
        }

        private void leave(Player p) {
            Clan clan = myClan(p, Clan.Rank.MEMBER);
            if (clan == null) {
                return;
            }
            if (clan.rank(p.getUniqueId()) == Clan.Rank.LEADER) {
                plugin.messages().send(p, "clans.leader-cant-leave");
                return;
            }
            store.leave(clan, p.getUniqueId());
            store.save();
            plugin.messages().send(p, "clans.left-you", "clan", clan.name);
            broadcast(clan, "clans.left", "player", p.getName());
        }

        private OfflinePlayer member(Player p, Clan clan, String name) {
            for (UUID id : clan.members.keySet()) {
                if (name.equalsIgnoreCase(name(id))) {
                    return Bukkit.getOfflinePlayer(id);
                }
            }
            plugin.messages().send(p, "clans.not-member", "player", name);
            return null;
        }

        private void kick(Player p, String[] a) {
            Clan clan = myClan(p, Clan.Rank.OFFICER);
            if (clan == null || a.length == 0) {
                if (clan != null) {
                    plugin.messages().send(p, "usage", "usage", "/clan kick <игрок>");
                }
                return;
            }
            OfflinePlayer target = member(p, clan, a[0]);
            if (target == null) {
                return;
            }
            // Выгнать можно только того, кто ниже по званию.
            if (!clan.rank(p.getUniqueId()).atLeast(clan.rank(target.getUniqueId())) || target.getUniqueId().equals(p.getUniqueId())
                    || clan.rank(p.getUniqueId()) == clan.rank(target.getUniqueId())) {
                plugin.messages().send(p, "clans.cant-kick");
                return;
            }
            store.leave(clan, target.getUniqueId());
            store.save();
            broadcast(clan, "clans.kicked", "player", String.valueOf(target.getName()), "by", p.getName());
            if (target.getPlayer() != null) {
                plugin.messages().send(target.getPlayer(), "clans.kicked-you", "clan", clan.name);
            }
        }

        private void setRank(Player p, String[] a, boolean up) {
            Clan clan = myClan(p, Clan.Rank.LEADER);
            if (clan == null || a.length == 0) {
                if (clan != null) {
                    plugin.messages().send(p, "usage", "usage", "/clan " + (up ? "promote" : "demote") + " <игрок>");
                }
                return;
            }
            OfflinePlayer target = member(p, clan, a[0]);
            if (target == null || target.getUniqueId().equals(p.getUniqueId())) {
                return;
            }
            Clan.Rank current = clan.rank(target.getUniqueId());
            if (up && current == Clan.Rank.OFFICER) {
                // Повышение офицера = передача лидерства.
                clan.members.put(p.getUniqueId(), Clan.Rank.OFFICER);
                clan.members.put(target.getUniqueId(), Clan.Rank.LEADER);
                broadcast(clan, "clans.new-leader", "player", String.valueOf(target.getName()));
            } else {
                Clan.Rank next = up ? Clan.Rank.OFFICER : Clan.Rank.MEMBER;
                clan.members.put(target.getUniqueId(), next);
                broadcast(clan, "clans.rank-changed", "player", String.valueOf(target.getName()), "rank", rankName(next));
            }
            store.save();
        }

        private void disband(Player p, String[] a) {
            Clan clan = myClan(p, Clan.Rank.LEADER);
            if (clan == null) {
                return;
            }
            if (a.length == 0 || !a[0].equalsIgnoreCase("confirm")) {
                plugin.messages().send(p, "clans.disband-confirm");
                return;
            }
            if (clan.bank > 0) {
                plugin.meta().deposit(p, clan.bank); // казна — главе
            }
            broadcast(clan, "clans.disbanded", "clan", clan.name);
            store.remove(clan);
            store.save();
        }

        private void info(CommandSender sender, String[] a) {
            Clan clan = a.length > 0 ? store.get(a[0])
                    : sender instanceof Player ? store.of(((Player) sender).getUniqueId()) : null;
            if (clan == null) {
                plugin.messages().send(sender, a.length > 0 ? "clans.not-found" : "clans.not-in-clan");
                return;
            }
            String members = clan.members.entrySet().stream()
                    .sorted((x, y) -> y.getValue().compareTo(x.getValue()))
                    .map(e -> (Bukkit.getPlayer(e.getKey()) != null ? "&a" : "&7") + name(e.getKey())
                            + (e.getValue() == Clan.Rank.MEMBER ? "" : " &8(" + rankName(e.getValue()) + "&8)"))
                    .collect(Collectors.joining("&7, "));
            plugin.messages().send(sender, "clans.info", "clan", clan.name, "tag", Messages.color(clan.tag),
                    "count", String.valueOf(clan.members.size()), "members", Messages.color(members),
                    "bank", plugin.meta().format(clan.bank),
                    "ff", plugin.messages().get(clan.friendlyFire ? "module-on" : "module-off"),
                    "motto", clan.motto.isEmpty() ? "-" : Messages.color(clan.motto),
                    "open", plugin.messages().get(clan.open ? "clans.open-yes" : "clans.open-no"));
        }

        private void top(CommandSender sender) {
            List<Clan> top = store.top();
            if (top.isEmpty()) {
                plugin.messages().send(sender, "clans.top-empty");
                return;
            }
            plugin.messages().send(sender, "clans.top-header");
            for (int i = 0; i < Math.min(10, top.size()); i++) {
                Clan c = top.get(i);
                sender.sendMessage(plugin.messages().get("clans.top-line", "place", String.valueOf(i + 1),
                        "tag", Messages.color(c.tag), "clan", c.name, "count", String.valueOf(c.members.size()),
                        "bank", plugin.meta().format(c.bank)));
            }
        }

        private void home(Player p) {
            Clan clan = myClan(p, Clan.Rank.MEMBER);
            if (clan == null) {
                return;
            }
            if (clan.home == null || clan.home.getWorld() == null) {
                plugin.messages().send(p, "clans.no-home");
                return;
            }
            plugin.teleporter().teleport(p, () -> clan.home);
        }

        private void sethome(Player p) {
            Clan clan = myClan(p, Clan.Rank.OFFICER);
            if (clan == null) {
                return;
            }
            clan.home = p.getLocation();
            store.save();
            broadcast(clan, "clans.home-set", "player", p.getName());
        }

        private void money(Player p, String[] a, boolean deposit) {
            Clan clan = myClan(p, deposit ? Clan.Rank.MEMBER : Clan.Rank.OFFICER);
            if (clan == null) {
                return;
            }
            double amount;
            try {
                amount = Math.round(Double.parseDouble(a.length > 0 ? a[0].replace(',', '.') : "x") * 100) / 100.0;
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount <= 0 || Double.isInfinite(amount)) {
                plugin.messages().send(p, "usage", "usage", "/clan " + (deposit ? "deposit" : "withdraw") + " <сумма>");
                return;
            }
            if (deposit) {
                if (!plugin.meta().withdraw(p, amount)) {
                    plugin.messages().send(p, "clans.not-enough", "amount", plugin.meta().format(amount));
                    return;
                }
                clan.bank += amount;
            } else {
                if (clan.bank < amount) {
                    plugin.messages().send(p, "clans.bank-not-enough");
                    return;
                }
                clan.bank -= amount;
                plugin.meta().deposit(p, amount);
            }
            store.save();
            broadcast(clan, deposit ? "clans.deposited" : "clans.withdrew", "player", p.getName(),
                    "amount", plugin.meta().format(amount), "bank", plugin.meta().format(clan.bank));
        }

        private void friendlyFire(Player p) {
            Clan clan = myClan(p, Clan.Rank.LEADER);
            if (clan == null) {
                return;
            }
            clan.friendlyFire = !clan.friendlyFire;
            store.save();
            broadcast(clan, "clans.ff", "state", plugin.messages().get(clan.friendlyFire ? "module-on" : "module-off"));
        }

        private void tag(Player p, String[] a) {
            Clan clan = myClan(p, Clan.Rank.LEADER);
            if (clan == null || a.length == 0) {
                if (clan != null) {
                    plugin.messages().send(p, "usage", "usage", "/clan tag <тег>");
                }
                return;
            }
            String tag = checkTag(p, a[0]);
            if (tag != null) {
                clan.tag = tag;
                store.save();
                broadcast(clan, "clans.tag-changed", "tag", Messages.color(tag));
            }
        }

        private void join(Player p, String[] a) {
            if (store.of(p.getUniqueId()) != null) {
                plugin.messages().send(p, "clans.already-in-clan");
                return;
            }
            Clan clan = a.length > 0 ? store.get(a[0]) : null;
            if (clan == null) {
                plugin.messages().send(p, "clans.not-found");
                return;
            }
            if (!clan.open && pendingInvite(p.getUniqueId()) != clan) {
                plugin.messages().send(p, "clans.closed");
                return;
            }
            int max = plugin.getConfig().getInt("clans.max-members", 20);
            if (clan.members.size() >= max) {
                plugin.messages().send(p, "clans.full", "max", String.valueOf(max));
                return;
            }
            invites.remove(p.getUniqueId());
            store.join(clan, p.getUniqueId(), Clan.Rank.MEMBER);
            store.save();
            broadcast(clan, "clans.joined", "player", p.getName());
        }

        private void toggleChat(Player p) {
            if (myClan(p, Clan.Rank.MEMBER) == null) {
                return;
            }
            boolean on = chatMode.add(p.getUniqueId());
            if (!on) {
                chatMode.remove(p.getUniqueId());
            }
            plugin.messages().send(p, on ? "clans.chat-on" : "clans.chat-off");
        }

        private void motto(Player p, String[] a) {
            Clan clan = myClan(p, Clan.Rank.LEADER);
            if (clan == null) {
                return;
            }
            String motto = String.join(" ", a).trim();
            if (COLOR.matcher(motto).replaceAll("").length() > 40) {
                plugin.messages().send(p, "clans.motto-too-long");
                return;
            }
            clan.motto = p.hasPermission("vcore.clans.color-tag") ? motto : COLOR.matcher(motto).replaceAll("");
            store.save();
            broadcast(clan, "clans.motto-changed", "motto", Messages.color(clan.motto));
        }

        private void toggleOpen(Player p) {
            Clan clan = myClan(p, Clan.Rank.LEADER);
            if (clan == null) {
                return;
            }
            clan.open = !clan.open;
            store.save();
            broadcast(clan, clan.open ? "clans.now-open" : "clans.now-closed");
        }

        /** Иконка — материал предмета в руке. */
        private void icon(Player p) {
            Clan clan = myClan(p, Clan.Rank.LEADER);
            if (clan == null) {
                return;
            }
            Material type = p.getInventory().getItemInMainHand().getType();
            if (type.isAir() || !type.isItem()) {
                plugin.messages().send(p, "clans.icon-hand");
                return;
            }
            clan.icon = type.name();
            store.save();
            plugin.messages().send(p, "clans.icon-set", "icon", type.name().toLowerCase(Locale.ROOT));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) {
                return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            }
            if (args.length == 2) {
                String sub = args[0].toLowerCase();
                if (sub.equals("invite")) {
                    return onlineNames(sender, args[1]);
                }
                if (sub.equals("info") || sub.equals("join")) {
                    return store.all().stream().map(c -> c.name)
                            .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
                }
                if ((sub.equals("kick") || sub.equals("promote") || sub.equals("demote")) && sender instanceof Player) {
                    Clan clan = store.of(((Player) sender).getUniqueId());
                    if (clan != null) {
                        return clan.members.keySet().stream().map(ClansModule::name)
                                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
                    }
                }
                if (sub.equals("disband")) {
                    return Collections.singletonList("confirm");
                }
            }
            return Collections.emptyList();
        }
    }
}
