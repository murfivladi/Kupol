package dev.evoday.gate.listener;

import dev.evoday.gate.EvoGate;
import dev.evoday.gate.storage.Account;
import dev.evoday.gate.storage.AccountRepo;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class JoinListener implements Listener {

    private final EvoGate plugin;

    public JoinListener(EvoGate plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String name = event.getName();
        String regex = plugin.getConfig().getString("auth.nickname-regex", "^[a-zA-Z0-9_]{3,16}$");
        if (!Pattern.matches(regex, name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().raw("kick-nickname"));
            return;
        }
        // иначе второй игрок с тем же ником выкинет того, кто уже зашёл
        if (Bukkit.getPlayerExact(name) != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().raw("kick-already-online"));
            return;
        }
        Account account;
        try {
            account = plugin.db().sync(c -> AccountRepo.byName(c, name));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "can't load account " + name, e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().raw("kick-loading"));
            return;
        }
        if (account != null && !account.name().equals(name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.messages().raw("kick-name-case", "name", account.name()));
            return;
        }
        String ip = event.getAddress().getHostAddress();
        String kick = switch (plugin.antiBot().checkJoin(ip, account == null, plugin.auth().unauthedFrom(ip))) {
            case THROTTLED -> "kick-throttle";
            case UNAUTHED_LIMIT -> "kick-unauthed-ip";
            case ATTACK -> "kick-attack";
            case OK -> null;
        };
        if (kick != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().raw(kick));
            return;
        }
        plugin.auth().preload(event.getUniqueId(), account);
    }

    // другой плагин мог отменить вход уже после нас
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginResult(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            plugin.auth().dropPreload(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        plugin.auth().hideLockedFrom(event.getPlayer());
        plugin.auth().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        plugin.auth().onQuit(event.getPlayer());
    }
}
