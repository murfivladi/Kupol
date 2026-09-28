package dev.vlad.core.gui;

import dev.vlad.core.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Меню-инвентарь: предметы в слотах и действия на клик.
 * Все клики внутри меню отменяются ({@link MenuListener}) — предметы нельзя забрать.
 * Наследники заполняют меню в {@link #render()}; {@link #refresh()} перерисовывает.
 */
public abstract class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
    protected final Player viewer;

    protected Menu(Player viewer, int rows, String title) {
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, Messages.color(title));
    }

    protected abstract void render();

    public final void open() {
        refresh();
        viewer.openInventory(inventory);
    }

    public final void refresh() {
        inventory.clear();
        actions.clear();
        render();
    }

    protected final void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected final void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** То же, что set, — для помощников вне класса меню (фон, кнопка "назад"). */
    public final void setButton(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        set(slot, item, action);
    }

    public final int slots() {
        return inventory.getSize();
    }

    protected final int size() {
        return inventory.getSize();
    }

    final void click(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> action = actions.get(event.getRawSlot());
        if (action != null) {
            action.accept(event);
        }
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }
}
