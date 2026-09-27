package dev.vlad.core.gui;

import dev.vlad.core.util.Messages;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Сборка предметов-кнопок: название и описание с цветами, без курсива и лишних подсказок. */
public final class Items {

    private Items() {
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            decorate(meta, name, lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack head(OfflinePlayer owner, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(owner);
        decorate(meta, name, lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Материал по названию из конфига; STONE, если такого нет. */
    public static Material material(String name) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null ? Material.STONE : m;
    }

    private static void decorate(ItemMeta meta, String name, List<String> lore) {
        // §r в начале снимает курсив, который Minecraft ставит на переименованные предметы.
        meta.setDisplayName("§r" + Messages.color(name));
        List<String> colored = new ArrayList<>();
        for (String line : lore) {
            colored.add("§r" + Messages.color(line));
        }
        meta.setLore(colored);
        meta.addItemFlags(ItemFlag.values());
    }
}
