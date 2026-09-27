package dev.vlad.core.modules.auth;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Авторизация для offline-mode: /register, /login, /logout, /changepassword, /resetpassword.
 * Пока игрок не вошёл — он заморожен и ничего не может. Пароли — PBKDF2 (см. {@link Passwords}),
 * данные — в players/&lt;uuid&gt;.yml, секция auth.
 */
public final class AuthModule extends Module implements Listener {

    /** Команды, доступные до входа; они же вырезаются из лога сервера. */
    private static final List<String> AUTH_COMMANDS =
            Arrays.asList("login", "l", "register", "reg", "changepassword", "changepass", "cp");
    private static final List<String> SECRET_COMMANDS =
            Arrays.asList("login", "l", "register", "reg", "changepassword", "changepass", "cp",
                    "vladcore:login", "vladcore:l", "vladcore:register", "vladcore:reg",
                    "vladcore:changepassword", "vladcore:changepass", "vladcore:cp");

    private final Set<UUID> guests = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeouts = new ConcurrentHashMap<>();
    /** Кто сейчас ждёт результата хеширования — чтобы не запускать второй раз. */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    /** Ник в нижнем регистре → ник как зарегистрирован. */
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final File namesFile;
    private final PasswordLogFilter logFilter = new PasswordLogFilter(SECRET_COMMANDS);
    private BukkitTask reminder;

    public AuthModule(VladCore plugin) {
        super(plugin, "auth");
        this.namesFile = new File(plugin.getDataFolder(), "auth-names.yml");
    }

    @Override
    protected void onEnable() {
        loadNames();
        logFilter.install();
        listen(this);
        placeholder("logged_in", p -> yesNo(p.getPlayer() != null && isLoggedIn(p.getPlayer())));
        command(new RegisterCommand());
        command(new LoginCommand());
        command(new ChangePasswordCommand());
        command(new ResetPasswordCommand());
        command(new LogoutCommand());
        // Уже онлайн при включении модуля (перезагрузка) — пусть тоже войдут.
        Bukkit.getOnlinePlayers().forEach(this::startSession);
        reminder = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : guests) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    remind(p);
                }
            }
        }, 200L, 200L);
    }

    @Override
    protected void onDisable() {
        logFilter.uninstall();
        if (reminder != null) {
            reminder.cancel();
        }
        timeouts.values().forEach(BukkitTask::cancel);
        timeouts.clear();
        guests.clear();
    }

    // ---------------- данные ----------------

    private void loadNames() {
        names.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(namesFile);
        for (String key : yaml.getKeys(false)) {
            names.put(key, yaml.getString(key, key));
        }
    }

    private void saveNames() {
        YamlConfiguration yaml = new YamlConfiguration();
        names.forEach(yaml::set);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                yaml.save(namesFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить auth-names.yml", e);
            }
        });
    }

    private PlayerData data(Player player) {
        return plugin.players().get(player.getUniqueId());
    }

    private boolean registered(Player player) {
        return data(player).getString("auth.hash") != null;
    }

    public boolean isLoggedIn(Player player) {
        return !guests.contains(player.getUniqueId());
    }

    private static String ip(Player player) {
        return player.getAddress().getAddress().getHostAddress();
    }

    // ---------------- сессия ----------------

    private void startSession(Player player) {
        PlayerData data = data(player);
        long sessionMs = plugin.getConfig().getLong("auth.session-minutes", 15) * 60_000L;
        boolean sameIp = ip(player).equals(data.getString("auth.last-ip"));
        long lastLogin = data.getLong("auth.last-login", 0);
        if (registered(player) && sessionMs > 0 && sameIp && System.currentTimeMillis() - lastLogin < sessionMs) {
            plugin.messages().send(player, "auth.session-restored");
            return;
        }
        requireLogin(player);
    }

    /** Заморозить игрока до /login и запустить таймер кика. */
    private void requireLogin(Player player) {
        guests.add(player.getUniqueId());
        remind(player);
        int timeout = plugin.getConfig().getInt("auth.timeout-seconds", 60);
        timeouts.put(player.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (guests.contains(player.getUniqueId()) && player.isOnline()) {
                player.kickPlayer(plugin.messages().get("auth.timeout"));
            }
        }, timeout * 20L));
    }

    private void remind(Player player) {
        plugin.messages().send(player, registered(player) ? "auth.please-login" : "auth.please-register");
    }

    private void completeLogin(Player player) {
        guests.remove(player.getUniqueId());
        attempts.remove(player.getUniqueId());
        BukkitTask task = timeouts.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        PlayerData data = data(player);
        data.set("auth.last-ip", ip(player));
        data.set("auth.last-login", System.currentTimeMillis());
    }

    /** Тяжёлую работу (хеш) — в фоне, результат — обратно в основной поток, если игрок ещё тут. */
    private <T> void async(Player player, Supplier<T> work, Consumer<T> then) {
        if (!busy.add(player.getUniqueId())) {
            return; // уже проверяем предыдущую попытку
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T result = work.get();
            Bukkit.getScheduler().runTask(plugin, () -> {
                busy.remove(player.getUniqueId());
                if (player.isOnline()) {
                    then.accept(result);
                }
            });
        });
    }

    /** null — пароль подходит, иначе ключ сообщения об ошибке. */
    private String checkNewPassword(Player player, String password) {
        int min = plugin.getConfig().getInt("auth.min-password-length", 6);
        if (password.length() < min) {
            return "auth.too-short";
        }
        if (password.length() > 64) {
            return "auth.too-long";
        }
        if (password.equalsIgnoreCase(player.getName())) {
            return "auth.same-as-name";
        }
        return null;
    }

    // ---------------- события ----------------

    /** Нельзя зайти под "Vlad", если зарегистрирован "vlad" (в offline-mode это разные UUID). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // Иначе в offline-mode любой выкинет игрока, зайдя под его ником ("вход с другого места").
        Player online = Bukkit.getPlayerExact(event.getName());
        if (online != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().get("auth.already-online"));
            return;
        }
        String registeredAs = names.get(event.getName().toLowerCase());
        if (registeredAs != null && !registeredAs.equals(event.getName())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.messages().get("auth.wrong-case", "name", registeredAs));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        startSession(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        guests.remove(id);
        attempts.remove(id);
        BukkitTask task = timeouts.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private boolean guest(Object entity) {
        return entity instanceof Player && guests.contains(((Player) entity).getUniqueId());
    }

    private void block(Cancellable event, Object who) {
        if (guest(who)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Крутить головой можно, двигаться — нет.
        if (guest(event.getPlayer()) && (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY() || event.getFrom().getZ() != event.getTo().getZ())) {
            event.setTo(event.getFrom().setDirection(event.getTo().getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (guest(event.getPlayer())) {
            event.setCancelled(true);
            remind(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!guest(event.getPlayer())) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        label = label.substring(label.indexOf(':') + 1);
        if (!AUTH_COMMANDS.contains(label)) {
            event.setCancelled(true);
            remind(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) { block(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { block(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { block(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) { block(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) { block(event, event.getEntity()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) { block(event, event.getWhoClicked()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) { block(event, event.getEntity()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) { block(event, event.getDamager()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) { block(event, event.getEntity()); }

    // ---------------- команды ----------------

    private abstract class AuthCommand extends ModuleCommand {
        AuthCommand(String name, String usage, String... aliases) {
            // Права нет: команды должны работать у всех, включая ещё не вошедших.
            super(AuthModule.this.plugin, name, null, usage, aliases);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private final class RegisterCommand extends AuthCommand {
        RegisterCommand() {
            super("register", "/register <пароль> <пароль>", "reg");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (registered(player)) {
                plugin.messages().send(player, "auth.already-registered");
                return;
            }
            if (args.length < 2) {
                plugin.messages().send(player, "usage", "usage", getUsage());
                return;
            }
            if (!args[0].equals(args[1])) {
                plugin.messages().send(player, "auth.mismatch");
                return;
            }
            String problem = checkNewPassword(player, args[0]);
            if (problem != null) {
                plugin.messages().send(player, problem, "min",
                        String.valueOf(plugin.getConfig().getInt("auth.min-password-length", 6)));
                return;
            }
            String password = args[0];
            async(player, () -> Passwords.hash(password), hash -> {
                data(player).set("auth.hash", hash);
                names.put(player.getName().toLowerCase(), player.getName());
                saveNames();
                completeLogin(player);
                plugin.messages().send(player, "auth.registered");
            });
        }
    }

    private final class LoginCommand extends AuthCommand {
        LoginCommand() {
            super("login", "/login <пароль>", "l");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (isLoggedIn(player)) {
                plugin.messages().send(player, "auth.already-logged-in");
                return;
            }
            if (!registered(player)) {
                plugin.messages().send(player, "auth.please-register");
                return;
            }
            if (args.length < 1) {
                plugin.messages().send(player, "usage", "usage", getUsage());
                return;
            }
            String password = args[0];
            String hash = data(player).getString("auth.hash");
            async(player, () -> Passwords.verify(password, hash), ok -> {
                if (ok) {
                    completeLogin(player);
                    plugin.messages().send(player, "auth.logged-in");
                    return;
                }
                int max = plugin.getConfig().getInt("auth.max-attempts", 5);
                int used = attempts.merge(player.getUniqueId(), 1, Integer::sum);
                if (used >= max) {
                    player.kickPlayer(plugin.messages().get("auth.too-many-attempts"));
                } else {
                    plugin.messages().send(player, "auth.wrong-password", "left", String.valueOf(max - used));
                }
            });
        }
    }

    private final class ChangePasswordCommand extends AuthCommand {
        ChangePasswordCommand() {
            super("changepassword", "/changepassword <старый> <новый>", "changepass", "cp");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (!isLoggedIn(player) || !registered(player)) {
                remind(player);
                return;
            }
            if (args.length < 2) {
                plugin.messages().send(player, "usage", "usage", getUsage());
                return;
            }
            String problem = checkNewPassword(player, args[1]);
            if (problem != null) {
                plugin.messages().send(player, problem, "min",
                        String.valueOf(plugin.getConfig().getInt("auth.min-password-length", 6)));
                return;
            }
            String oldPassword = args[0];
            String newPassword = args[1];
            String hash = data(player).getString("auth.hash");
            async(player, () -> Passwords.verify(oldPassword, hash) ? Passwords.hash(newPassword) : null, newHash -> {
                if (newHash == null) {
                    plugin.messages().send(player, "auth.wrong-old-password");
                    return;
                }
                data(player).set("auth.hash", newHash);
                plugin.messages().send(player, "auth.password-changed");
            });
        }
    }

    private final class LogoutCommand extends AuthCommand {
        LogoutCommand() {
            super("logout", "/logout");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (!isLoggedIn(player)) {
                remind(player);
                return;
            }
            // Сессия сброшена: при перезаходе тоже спросим пароль.
            data(player).set("auth.last-login", 0L);
            plugin.messages().send(player, "auth.logged-out");
            requireLogin(player);
        }
    }

    private final class ResetPasswordCommand extends ModuleCommand {
        ResetPasswordCommand() {
            super(AuthModule.this.plugin, "resetpassword", "vcore.auth.admin", "/resetpassword <игрок>", "unregister");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                target = Bukkit.getOfflinePlayerIfCached(args[0]);
            }
            if (target == null) {
                plugin.messages().send(sender, "player-not-found", "player", args[0]);
                return;
            }
            PlayerData data = plugin.players().get(target.getUniqueId());
            data.set("auth", null);
            names.remove(String.valueOf(target.getName()).toLowerCase());
            saveNames();
            plugin.messages().send(sender, "auth.reset", "player", String.valueOf(target.getName()));
            if (target.getPlayer() != null) {
                target.getPlayer().kickPlayer(plugin.messages().get("auth.reset-kick"));
            }
        }
    }
}
