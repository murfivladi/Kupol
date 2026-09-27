package dev.vlad.core.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;

/** Подтверждение действия: зелёное "Да" слева, красное "Нет" справа, в центре — о чём речь. */
public final class ConfirmMenu extends Menu {

    private final ItemStack subject;
    private final Runnable onYes;
    private final Runnable onNo;

    public ConfirmMenu(Player viewer, String title, ItemStack subject, Runnable onYes, Runnable onNo) {
        super(viewer, 3, title);
        this.subject = subject;
        this.onYes = onYes;
        this.onNo = onNo;
    }

    @Override
    protected void render() {
        List<String> none = Collections.emptyList();
        ItemStack yes = Items.of(Material.LIME_STAINED_GLASS_PANE, "&a&lДа", none);
        ItemStack no = Items.of(Material.RED_STAINED_GLASS_PANE, "&c&lНет", none);
        for (int slot : new int[]{0, 1, 2, 9, 10, 11, 18, 19, 20}) {
            set(slot, yes, e -> onYes.run());
        }
        for (int slot : new int[]{6, 7, 8, 15, 16, 17, 24, 25, 26}) {
            set(slot, no, e -> onNo.run());
        }
        set(13, subject);
    }
}
