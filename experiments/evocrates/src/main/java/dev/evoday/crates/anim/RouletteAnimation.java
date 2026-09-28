package dev.evoday.crates.anim;

import dev.evoday.crates.crate.Crate;
import dev.evoday.crates.crate.Reward;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

// лента из предметов в среднем ряду, крутится и тормозит на выигрыше в центре (слот 13)
public final class RouletteAnimation implements Animation, InventoryHolder {

    private static final int ROW = 9;
    private static final int CENTER = 13;

    private final Plugin plugin;
    private final Player player;
    private final Runnable onFinish;
    private final int steps;
    private final int maxDelay;
    private final List<ItemStack> strip = new ArrayList<>();
    private final Inventory inventory;

    private BukkitTask task;
    private int offset;
    private int wait;
    private boolean finished;

    public RouletteAnimation(Plugin plugin, Player player, Crate crate, Reward reward, Runnable onFinish,
                             int steps, int maxDelay, Component title) {
        this.plugin = plugin;
        this.player = player;
        this.onFinish = onFinish;
        this.steps = Math.max(10, steps);
        this.maxDelay = Math.max(2, maxDelay);
        for (int i = 0; i < this.steps + 9; i++) {
            strip.add(crate.roll().icon());
        }
        // когда offset == steps, в центре окажется strip[steps + 4]
        strip.set(this.steps + 4, reward.icon());
        inventory = Bukkit.createInventory(this, 27, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void start() {
        ItemStack glass = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, glass);
        }
        inventory.setItem(CENTER - 9, pane(Material.LIME_STAINED_GLASS_PANE));
        inventory.setItem(CENTER + 9, pane(Material.LIME_STAINED_GLASS_PANE));
        draw();
        player.openInventory(inventory);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (wait-- > 0) {
            return;
        }
        offset++;
        draw();
        player.playSound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
        if (offset >= steps) {
            end();
            return;
        }
        // ease-out: в начале без пауз, к концу пауза растёт до maxDelay
        double t = (double) offset / steps;
        wait = (int) Math.round(maxDelay * t * t * t);
    }

    private void draw() {
        for (int i = 0; i < ROW; i++) {
            inventory.setItem(ROW + i, strip.get(offset + i));
        }
    }

    private void end() {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
        offset = steps;
        draw();
        // подсветить выигрыш
        ItemStack gold = pane(Material.YELLOW_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) {
            if (i < ROW || i >= ROW * 2) {
                inventory.setItem(i, gold);
            }
        }
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        onFinish.run();
        if (!plugin.isEnabled()) {
            player.closeInventory();
            return;
        }
        // даём посмотреть на награду и закрываем
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.getOpenInventory().getTopInventory() == inventory) {
                player.closeInventory();
            }
        }, 40L);
    }

    @Override
    public void skip() {
        end();
    }

    public boolean isFinished() {
        return finished;
    }

    private static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        item.editMeta(m -> m.setHideTooltip(true));
        return item;
    }
}
