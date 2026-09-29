package dev.evoday.crates.crate;

import org.bukkit.inventory.ItemStack;

import java.util.List;

public record Reward(String id, int weight, String rarity, ItemStack item, boolean giveItem,
                     List<String> commands, boolean broadcast) {

    public ItemStack icon() {
        return item.clone();
    }
}
