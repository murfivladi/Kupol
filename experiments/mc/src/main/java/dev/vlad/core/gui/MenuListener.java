package dev.vlad.core.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Отмена любых перемещений предметов в меню и передача кликов по верхнему инвентарю. */
public final class MenuListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu)) {
            return;
        }
        // Отменяем всё, включая shift-клик из своего инвентаря в меню.
        event.setCancelled(true);
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            ((Menu) event.getView().getTopInventory().getHolder()).click(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
