package dev.vlad.core.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * Меню-список со страницами: 5 рядов записей + нижний ряд навигации
 * (слот 45 — назад, 49 — счётчик, 53 — вперёд; 46–48 и 50–52 — для своих кнопок).
 */
public abstract class PagedMenu<T> extends Menu {

    private static final int PER_PAGE = 45;
    private int page;

    protected PagedMenu(Player viewer, String title) {
        super(viewer, 6, title);
    }

    protected abstract List<T> entries();

    protected abstract ItemStack icon(T entry, int index);

    protected abstract void onClick(T entry, InventoryClickEvent event);

    /** Свои кнопки в нижнем ряду (слоты 46–48, 50–52). */
    protected void bottomRow() {
    }

    /** Что показать, если список пуст (в центре). */
    protected ItemStack emptyIcon() {
        return Items.of(Material.BARRIER, "&cПусто", Collections.emptyList());
    }

    @Override
    protected final void render() {
        List<T> list = entries();
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        int from = page * PER_PAGE;
        for (int i = from; i < Math.min(from + PER_PAGE, list.size()); i++) {
            T entry = list.get(i);
            set(i - from, icon(entry, i), e -> onClick(entry, e));
        }
        if (list.isEmpty()) {
            set(22, emptyIcon());
        }

        ItemStack filler = Items.of(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int slot = 45; slot < 54; slot++) {
            set(slot, filler);
        }
        if (page > 0) {
            set(45, Items.of(Material.ARROW, "&e« Назад", Collections.emptyList()), e -> {
                page--;
                refresh();
            });
        }
        set(49, Items.of(Material.PAPER, "&7Страница &f" + (page + 1) + "&7/&f" + pages,
                Collections.singletonList("&8Всего: " + list.size())));
        if (page < pages - 1) {
            set(53, Items.of(Material.ARROW, "&eВперёд »", Collections.emptyList()), e -> {
                page++;
                refresh();
            });
        }
        bottomRow();
    }
}
