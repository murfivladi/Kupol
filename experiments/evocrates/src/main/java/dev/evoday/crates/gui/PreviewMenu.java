package dev.evoday.crates.gui;

import dev.evoday.crates.EvoCrates;
import dev.evoday.crates.crate.Crate;
import dev.evoday.crates.crate.Reward;
import dev.evoday.crates.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PreviewMenu implements InventoryHolder {

    private final Inventory inventory;

    public PreviewMenu(EvoCrates plugin, Crate crate) {
        int rows = Math.min(6, Math.max(1, (crate.rewards().size() + 8) / 9));
        inventory = Bukkit.createInventory(this, rows * 9, plugin.messages().raw("preview-title", "crate", crate.name()));
        int slot = 0;
        for (Reward reward : crate.rewards()) {
            if (slot >= inventory.getSize()) {
                break;
            }
            ItemStack icon = reward.icon();
            String chance = String.format(Locale.ROOT, "%.2f", crate.chance(reward));
            String rarity = plugin.getConfig().getString("rarities." + reward.rarity() + ".name", reward.rarity());
            icon.editMeta(meta -> {
                List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(noItalic(plugin.messages().raw("preview-rarity", "rarity",
                        Messages.MM.deserialize(rarity))));
                lore.add(noItalic(plugin.messages().raw("preview-chance", "chance", chance)));
                meta.lore(lore);
            });
            inventory.setItem(slot++, icon);
        }
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
