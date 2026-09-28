package dev.evoday.crates.crate;

import dev.evoday.crates.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

final class ItemParser {

    private ItemParser() {
    }

    static ItemStack parse(ConfigurationSection s) {
        String data = s.getString("data");
        if (data != null) {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
        }
        Material material = Material.matchMaterial(s.getString("material", "STONE"));
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException("bad material: " + s.getString("material"));
        }
        ItemStack item = new ItemStack(material, Math.max(1, s.getInt("amount", 1)));
        ItemMeta meta = item.getItemMeta();
        if (s.isString("name")) {
            meta.displayName(noItalic(Messages.MM.deserialize(s.getString("name"))));
        }
        if (s.isList("lore")) {
            List<Component> lore = s.getStringList("lore").stream().map(l -> noItalic(Messages.MM.deserialize(l))).toList();
            meta.lore(lore);
        }
        ConfigurationSection enchants = s.getConfigurationSection("enchants");
        if (enchants != null) {
            for (String key : enchants.getKeys(false)) {
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
                if (e == null) {
                    throw new IllegalArgumentException("bad enchant: " + key);
                }
                meta.addEnchant(e, enchants.getInt(key), true);
            }
        }
        if (s.isInt("custom-model-data")) {
            meta.setCustomModelData(s.getInt("custom-model-data"));
        }
        item.setItemMeta(meta);
        return item;
    }

    static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
