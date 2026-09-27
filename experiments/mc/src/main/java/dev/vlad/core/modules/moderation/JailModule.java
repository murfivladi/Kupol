package dev.vlad.core.modules.moderation;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Тюрьмы: /setjail, /deljail, /jails, /jail, /unjail.
 * Заключение хранится в данных игрока: moderation.jail (+ place — тюрьма, from — откуда забрали).
 * Срок идёт и офлайн; по окончании игрок возвращается туда, откуда его забрали.
 */
public final class JailModule extends Module implements Listener {

    private static final String PATH = "moderation.jail";
    private static final String TYPE = "jail";
    /** Точка возврата для игрока, выпущенного офлайн. */
    private static final String RELEASE_PATH = "moderation.jail-release";

    private final ModTools tools;
    private final File file;
    private final Map<String, Location> jails = new TreeMap<>();
    /** Кто сейчас онлайн и сидит — для быстрых проверок в частых событиях. */
    private final Set<UUID> jailed = new HashSet<>();
    /** Кого мы сами сейчас телепортируем (в тюрьму или на свободу) — такие телепорты не блокируем. */
    private final Set<UUID> movingByUs = new HashSet<>();
    private BukkitTask ticker;

    public JailModule(VladCore plugin) {
        super(plugin, "jail");
        this.tools = new ModTools(plugin);
        this.file = new File(plugin.getDataFolder(), "jails.yml");
    }

    @Override
    protected void onEnable() {
        load();
        listen(this);
        placeholder("jailed", p -> yesNo(Punishment.read(plugin.players().get(p.getUniqueId()), TYPE) != null));
        placeholder("jail_time", p -> {
            Punishment jail = Punishment.read(plugin.players().get(p.getUniqueId()), TYPE);
            return jail == null ? "" : tools.time(jail);
        });
        command(new SetJailCommand());
        command(new DelJailCommand());
        command(new JailsCommand());
        command(new JailCommand());
        command(new UnjailCommand());
        Bukkit.getOnlinePlayers().forEach(this::check);
        // Раз в секунду: не истёк ли срок у сидящих онлайн.
        ticker = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> new ArrayList<>(jailed).forEach(id -> {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        check(p);
                    }
                }), 20L, 20L);
    }

    @Override
    protected void onDisable() {
        if (ticker != null) {
            ticker.cancel();
        }
        jailed.clear();
    }

    @Override
    protected void onReload() {
        load();
    }

    private void load() {
        jails.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String name : yaml.getKeys(false)) {
            Location location = yaml.getLocation(name);
            if (location != null) {
                jails.put(name, location);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        jails.forEach(yaml::set);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить jails.yml", e);
        }
    }

    private void teleport(Player player, Location to) {
        movingByUs.add(player.getUniqueId());
        player.teleportAsync(to).whenComplete((ok, err) -> movingByUs.remove(player.getUniqueId()));
    }

    private Location jailOf(PlayerData data) {
        Location location = jails.get(String.valueOf(data.getString(PATH + ".place")));
        if (location == null && !jails.isEmpty()) {
            location = jails.values().iterator().next(); // тюрьму удалили — берём любую
        }
        return location;
    }

    /**
     * Приводит игрока в соответствие с данными: сидит — в тюрьму (если далеко),
     * срок вышел — освобождает и возвращает обратно.
     */
    private void check(Player player) {
        PlayerData data = plugin.players().get(player.getUniqueId());
        if (!data.contains(PATH)) {
            jailed.remove(player.getUniqueId());
            // Выпущен через /unjail, пока был офлайн, — возвращаем на место.
            Location release = data.getLocation(RELEASE_PATH);
            if (release != null) {
                data.set(RELEASE_PATH, null);
                release(player, release);
                plugin.messages().send(player, "jail.released");
            }
            return;
        }
        Location from = data.getLocation(PATH + ".from");
        Punishment p = Punishment.read(data, TYPE); // истёкшее стирается здесь
        if (p == null) {
            release(player, from);
            plugin.messages().send(player, "jail.released");
            return;
        }
        jailed.add(player.getUniqueId());
        Location jail = jailOf(data);
        if (jail != null && tooFar(player.getLocation(), jail)) {
            teleport(player, jail);
        }
    }

    private void release(Player player, Location from) {
        jailed.remove(player.getUniqueId());
        teleport(player, from != null && from.getWorld() != null ? from : Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    private boolean tooFar(Location at, Location jail) {
        int radius = plugin.getConfig().getInt("jail.radius", 10);
        return at.getWorld() != jail.getWorld() || at.distanceSquared(jail) > (double) radius * radius;
    }

    private boolean isJailed(Player player) {
        return jailed.contains(player.getUniqueId());
    }

    // ---------------- события ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        check(event.getPlayer());
        if (isJailed(event.getPlayer())) {
            Punishment p = Punishment.read(plugin.players().get(event.getPlayer().getUniqueId()), TYPE);
            if (p != null) {
                plugin.messages().send(event.getPlayer(), "jail.you-are-jailed", "reason", p.reason, "time", tools.time(p));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        jailed.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (isJailed(event.getPlayer())) {
            Location jail = jailOf(plugin.players().get(event.getPlayer().getUniqueId()));
            if (jail != null) {
                event.setRespawnLocation(jail);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isJailed(event.getPlayer()) && !movingByUs.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "jail.denied");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isJailed(event.getPlayer()) || event.getFrom().getBlock().equals(event.getTo().getBlock())) {
            return;
        }
        Location jail = jailOf(plugin.players().get(event.getPlayer().getUniqueId()));
        if (jail != null && tooFar(event.getTo(), jail)) {
            teleport(event.getPlayer(), jail);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isJailed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isJailed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isJailed(event.getPlayer())) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        label = label.substring(label.indexOf(':') + 1);
        if (!plugin.getConfig().getStringList("jail.allowed-commands").contains(label)) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "jail.denied");
        }
    }

    // ---------------- команды ----------------

    private List<String> completeJails(String prefix) {
        String lower = prefix.toLowerCase();
        return jails.keySet().stream().filter(j -> j.startsWith(lower)).collect(Collectors.toList());
    }

    private final class SetJailCommand extends ModuleCommand {
        SetJailCommand() {
            super(JailModule.this.plugin, "setjail", "vcore.setjail", "/setjail [название]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            String name = args.length > 0 ? args[0].toLowerCase() : "default";
            jails.put(name, player.getLocation());
            save();
            plugin.messages().send(player, "jail.set", "jail", name);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? completeJails(args[0]) : Collections.emptyList();
        }
    }

    private final class DelJailCommand extends ModuleCommand {
        DelJailCommand() {
            super(JailModule.this.plugin, "deljail", "vcore.setjail", "/deljail <название>");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            String name = args[0].toLowerCase();
            if (jails.remove(name) == null) {
                plugin.messages().send(sender, "jail.not-found", "jail", name);
                return;
            }
            save();
            plugin.messages().send(sender, "jail.deleted", "jail", name);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? completeJails(args[0]) : Collections.emptyList();
        }
    }

    private final class JailsCommand extends ModuleCommand {
        JailsCommand() {
            super(JailModule.this.plugin, "jails", "vcore.jail", "/jails");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (jails.isEmpty()) {
                plugin.messages().send(sender, "jail.none");
                return;
            }
            plugin.messages().send(sender, "jail.list", "count", String.valueOf(jails.size()));
            sender.sendMessage(plugin.messages().get("jail.list-items", "jails", String.join(", ", jails.keySet())));
        }
    }

    private final class JailCommand extends ModuleCommand {
        JailCommand() {
            super(JailModule.this.plugin, "jail", "vcore.jail",
                    "/jail <игрок> <время: 30m, 1d | perm> [причина]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length < 2) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            if (jails.isEmpty()) {
                plugin.messages().send(sender, "jail.none");
                return;
            }
            OfflinePlayer target = tools.find(sender, args[0]);
            if (target == null || tools.exempt(sender, target)) {
                return;
            }
            long duration = tools.parseTime(args[1]);
            if (duration < 0) {
                plugin.messages().send(sender, "moderation.bad-time");
                return;
            }
            // Тюрьма: если в конфиге задана jail.default и она существует — она, иначе первая.
            String place = plugin.getConfig().getString("jail.default", "default");
            if (!jails.containsKey(place)) {
                place = jails.keySet().iterator().next();
            }
            long now = System.currentTimeMillis();
            Punishment p = new Punishment(tools.reason(args, 2), sender.getName(), now, duration == 0 ? 0 : now + duration);
            PlayerData data = plugin.players().get(target.getUniqueId());
            Location from = data.contains(PATH) ? data.getLocation(PATH + ".from") : null; // повторный jail — не терять
            p.write(data, TYPE);
            data.set(PATH + ".place", place);
            if (target.getPlayer() != null) {
                from = target.getPlayer().getLocation();
            }
            data.set(PATH + ".from", from);
            Punishment.log(data, TYPE, p.reason, sender.getName(), duration);

            String name = String.valueOf(target.getName());
            tools.announce(sender, "jail.jailed", "player", name, "by", sender.getName(),
                    "reason", p.reason, "time", tools.time(p));
            if (target.getPlayer() != null) {
                check(target.getPlayer());
                plugin.messages().send(target.getPlayer(), "jail.you-are-jailed", "reason", p.reason, "time", tools.time(p));
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? onlineNames(sender, args[0]) : Collections.emptyList();
        }
    }

    private final class UnjailCommand extends ModuleCommand {
        UnjailCommand() {
            super(JailModule.this.plugin, "unjail", "vcore.unjail", "/unjail <игрок>");
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
            if (Punishment.read(data, TYPE) == null) {
                plugin.messages().send(sender, "jail.not-jailed", "player", name);
                return;
            }
            Location from = data.getLocation(PATH + ".from");
            data.set(PATH, null);
            Punishment.log(data, "unjail", "", sender.getName(), 0);
            tools.announce(sender, "jail.unjailed", "player", name, "by", sender.getName());
            if (target.getPlayer() != null) {
                release(target.getPlayer(), from);
                plugin.messages().send(target.getPlayer(), "jail.released");
            } else {
                // Офлайн — вернём при входе (см. check).
                data.set(RELEASE_PATH, from != null ? from : Bukkit.getWorlds().get(0).getSpawnLocation());
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? onlineNames(sender, args[0]) : Collections.emptyList();
        }
    }
}
