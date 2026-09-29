package dev.evoday.crates.anim;

import dev.evoday.crates.CrateService;
import dev.evoday.crates.crate.Crate;
import dev.evoday.crates.crate.Reward;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

// предметы летают по кругу над кейсом, замедляются, выигрыш останавливается напротив игрока и поднимается
public final class WorldAnimation implements Animation {

    private static final int REVEAL_TICKS = 50;

    private final Plugin plugin;
    private final Player player;
    private final Crate crate;
    private final Reward reward;
    private final Runnable onFinish;
    private final Location center;
    private final int spinTicks;
    private final double radius;
    private final int count;

    private final List<ItemDisplay> items = new ArrayList<>();
    private TextDisplay label;
    private BukkitTask task;
    private int tick;
    private double totalAngle;
    private boolean rewarded;
    private boolean cleaned;

    public WorldAnimation(Plugin plugin, Player player, Crate crate, Reward reward, Runnable onFinish,
                          Location blockCenter, int spinTicks, double radius, int count) {
        this.plugin = plugin;
        this.player = player;
        this.crate = crate;
        this.reward = reward;
        this.onFinish = onFinish;
        this.center = blockCenter.clone().add(0, 1.3, 0);
        this.spinTicks = Math.max(20, spinTicks);
        this.radius = radius;
        this.count = Math.max(3, Math.min(16, count));
    }

    @Override
    public void start() {
        // выигрыш - нулевой элемент, остальные для вида
        for (int i = 0; i < count; i++) {
            Reward r = i == 0 ? reward : crate.roll();
            items.add(center.getWorld().spawn(position(angle(i)), ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(r.icon());
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setTeleportDuration(1);
                d.setTransformation(scaled(0.55f));
            }));
        }
        // в конце нулевой предмет должен смотреть на игрока
        double toPlayer = Math.atan2(player.getZ() - center.getZ(), player.getX() - center.getX());
        totalAngle = toPlayer + Math.PI * 2 * 4;
        center.getWorld().playSound(center, Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        tick++;
        if (tick <= spinTicks) {
            double t = (double) tick / spinTicks;
            double rot = totalAngle * (1 - Math.pow(1 - t, 3));
            for (int i = 0; i < items.size(); i++) {
                items.get(i).teleport(position(rot + angle(i)));
            }
            if (tick % Math.max(1, (int) (t * 8)) == 0) {
                center.getWorld().playSound(center, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.5f + (float) t * 0.5f);
            }
            center.getWorld().spawnParticle(Particle.END_ROD, center, 1, 0.3, 0.3, 0.3, 0.01);
            if (tick == spinTicks) {
                reveal();
            }
            return;
        }
        if (tick >= spinTicks + REVEAL_TICKS) {
            cleanup();
        }
    }

    private void reveal() {
        for (int i = 1; i < items.size(); i++) {
            ItemDisplay d = items.get(i);
            d.getWorld().spawnParticle(Particle.POOF, d.getLocation(), 4, 0.1, 0.1, 0.1, 0.02);
            d.remove();
        }
        ItemDisplay winner = items.get(0);
        winner.setTeleportDuration(20);
        winner.teleport(center.clone().add(0, 0.4, 0));
        winner.setInterpolationDelay(0);
        winner.setInterpolationDuration(20);
        winner.setTransformation(scaled(1.1f));

        label = center.getWorld().spawn(center.clone().add(0, 1.35, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(CrateService.rewardName(reward));
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
        });
        center.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, center, 40, 0.4, 0.4, 0.4, 0.3);
        center.getWorld().playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        giveReward();
    }

    private void giveReward() {
        if (!rewarded) {
            rewarded = true;
            onFinish.run();
        }
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        if (task != null) {
            task.cancel();
        }
        items.forEach(Entity::remove);
        if (label != null) {
            label.remove();
        }
    }

    @Override
    public void skip() {
        cleanup();
        giveReward();
    }

    private double angle(int index) {
        return Math.PI * 2 * index / count;
    }

    private Location position(double angle) {
        return center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    private static Transformation scaled(float scale) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale), new AxisAngle4f());
    }
}
