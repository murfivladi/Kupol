package dev.evoday.crates;

import dev.evoday.crates.anim.RouletteAnimation;
import dev.evoday.crates.crate.Crate;
import dev.evoday.crates.gui.PreviewMenu;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

public final class CrateListener implements Listener {

    private final EvoCrates plugin;

    public CrateListener(EvoCrates plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Crate crate = plugin.blocks().crateAt(block);
        if (crate == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        // админ с шифтом ломает блок как обычно (чтобы можно было снести кейс)
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && player.isSneaking() && player.hasPermission("evocrates.admin")) {
            event.setCancelled(false);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            plugin.service().open(player, crate, block);
        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            new PreviewMenu(plugin, crate).open(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (plugin.blocks().crateAt(event.getBlock()) == null) {
            return;
        }
        if (event.getPlayer().hasPermission("evocrates.admin") && event.getPlayer().isSneaking()) {
            plugin.blocks().remove(event.getBlock());
            plugin.messages().send(event.getPlayer(), "admin-block-removed");
        } else {
            event.setCancelled(true);
        }
    }

    private static boolean ours(InventoryHolder holder) {
        return holder instanceof PreviewMenu || holder instanceof RouletteAnimation;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (ours(event.getView().getTopInventory().getHolder(false))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (ours(event.getView().getTopInventory().getHolder(false))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof RouletteAnimation animation
                && event.getPlayer() instanceof Player player) {
            plugin.service().onRouletteClosed(player, animation);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.service().loadKeys(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().onQuit(event.getPlayer());
    }
}
