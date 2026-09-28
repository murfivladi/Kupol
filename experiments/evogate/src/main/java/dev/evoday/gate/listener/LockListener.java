package dev.evoday.gate.listener;

import dev.evoday.gate.EvoGate;
import dev.evoday.gate.session.Session;
import dev.evoday.gate.session.State;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.List;
import java.util.Locale;

// всё, что нельзя делать до входа
public final class LockListener implements Listener {

    private final EvoGate plugin;

    public LockListener(EvoGate plugin) {
        this.plugin = plugin;
    }

    private boolean locked(Object entity) {
        return entity instanceof Player p && plugin.auth().isLocked(p);
    }

    private void cancelIfLocked(Object entity, Cancellable event) {
        if (locked(entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.auth().isLocked(player)) {
            return;
        }
        event.setCancelled(true);
        Session s = plugin.auth().session(player);
        if (s != null && s.state() == State.CAPTCHA) {
            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    plugin.auth().onCaptchaInput(player, text);
                }
            });
        } else if (s != null && s.state() == State.CHECK) {
            plugin.messages().send(player, "wait-check");
        } else {
            plugin.messages().send(player, s != null && s.state() == State.REGISTER ? "register-prompt" : "login-prompt");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.auth().isLocked(event.getPlayer())) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        label = label.substring(label.indexOf(':') + 1);
        List<String> allowed = plugin.getConfig().getStringList("auth.allowed-commands");
        if (!allowed.contains(label)) {
            event.setCancelled(true);
            Session s = plugin.auth().session(event.getPlayer());
            String key = s == null ? "not-logged" : switch (s.state()) {
                case CHECK -> "wait-check";
                case CAPTCHA -> "wait-captcha";
                default -> "not-logged";
            };
            plugin.messages().send(event.getPlayer(), key);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.auth().isLocked(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        // во время проверки игрок должен свободно падать
        if (plugin.antiBot().isChecking(event.getPlayer())) {
            plugin.antiBot().onMove(event.getPlayer(), from.getY(), to.getY());
            return;
        }
        // крутить головой можно
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        cancelIfLocked(event.getEntity(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        cancelIfLocked(event.getDamager(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        cancelIfLocked(event.getTarget(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        cancelIfLocked(event.getEntity(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        cancelIfLocked(event.getEntity(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        cancelIfLocked(event.getWhoClicked(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        cancelIfLocked(event.getWhoClicked(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        cancelIfLocked(event.getPlayer(), event);
    }
}
