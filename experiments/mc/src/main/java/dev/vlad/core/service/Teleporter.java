package dev.vlad.core.service;

import dev.vlad.core.VladCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Общий телепорт с задержкой для всех модулей (tpa, дома, варпы...).
 * Пока идёт отсчёт, движение (смена блока) или урон отменяют его.
 * Цель берётся через Supplier в момент телепорта — к игроку, который мог уйти.
 */
public final class Teleporter implements Listener {

    private static final String BYPASS = "vcore.teleport.bypass-delay";

    private final VladCore plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public Teleporter(VladCore plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private int delaySeconds() {
        return Math.max(0, plugin.getConfig().getInt("teleport.delay", 3));
    }

    public void teleport(Player player, Supplier<Location> destination) {
        cancel(player, false);
        int delay = delaySeconds();
        if (delay == 0 || player.hasPermission(BYPASS)) {
            go(player, destination);
            return;
        }
        plugin.messages().send(player, "teleport.warmup", "seconds", String.valueOf(delay));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            go(player, destination);
        }, delay * 20L);
        pending.put(player.getUniqueId(), new Pending(task, player.getLocation()));
    }

    private void go(Player player, Supplier<Location> destination) {
        Location to = destination.get();
        if (to == null || to.getWorld() == null || !player.isOnline()) {
            plugin.messages().send(player, "teleport.target-gone");
            return;
        }
        player.teleportAsync(to).thenAccept(ok -> {
            if (ok) {
                plugin.messages().send(player, "teleport.done");
            }
        });
    }

    /** Отмена отсчёта; notify — написать игроку, что телепорт прерван. */
    public void cancel(Player player, boolean notify) {
        Pending p = pending.remove(player.getUniqueId());
        if (p != null) {
            p.task.cancel();
            if (notify) {
                plugin.messages().send(player, "teleport.cancelled");
            }
        }
    }

    public void cancelAll() {
        pending.values().forEach(p -> p.task.cancel());
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending p = pending.get(event.getPlayer().getUniqueId());
        Location to = event.getTo();
        if (p != null && (to.getWorld() != p.start.getWorld()
                || to.getBlockX() != p.start.getBlockX()
                || to.getBlockY() != p.start.getBlockY()
                || to.getBlockZ() != p.start.getBlockZ())) {
            cancel(event.getPlayer(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            cancel((Player) event.getEntity(), true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
    }

    private static final class Pending {
        final BukkitTask task;
        final Location start;

        Pending(BukkitTask task, Location start) {
            this.task = task;
            this.start = start;
        }
    }
}
