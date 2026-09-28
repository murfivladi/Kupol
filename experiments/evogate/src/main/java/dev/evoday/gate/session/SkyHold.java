package dev.evoday.gate.session;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

// до входа игрок висит в небе над своей точкой. откуда подняли - пишем в данные игрока,
// чтобы после краша или выхода в небе вернуть его на место при следующем заходе
final class SkyHold {

    private final JavaPlugin plugin;
    private final NamespacedKey key;

    SkyHold(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "origin");
    }

    // hover=false - без полёта, для проверки гравитации
    void lift(Player player, boolean hover) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Location from = player.getLocation();
        if (!pdc.has(key, PersistentDataType.STRING)) {
            pdc.set(key, PersistentDataType.STRING, String.join(";",
                    from.getWorld().getName(), String.valueOf(from.getX()), String.valueOf(from.getY()),
                    String.valueOf(from.getZ()), String.valueOf(from.getYaw()), String.valueOf(from.getPitch()),
                    String.valueOf(player.getAllowFlight()), String.valueOf(player.isFlying())));
        }
        int height = from.getWorld().getMaxHeight() + plugin.getConfig().getInt("auth.sky-height", 60);
        Location sky = new Location(from.getWorld(), from.getBlockX() + 0.5, height, from.getBlockZ() + 0.5, from.getYaw(), 0);
        // allowFlight включён всегда, иначе ванилла кикает за "полёт" раньше нашей проверки
        player.setAllowFlight(true);
        player.setFlying(hover);
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        player.teleport(sky);
    }

    // true если игрок был в небе и его вернули
    boolean land(Player player) {
        return land(player, null);
    }

    // target - куда отправить вместо исходной точки
    boolean land(Player player, Location target) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String saved = pdc.get(key, PersistentDataType.STRING);
        if (saved == null) {
            return false;
        }
        pdc.remove(key);
        String[] p = saved.split(";");
        Location back;
        boolean allowFlight = false;
        boolean flying = false;
        try {
            World world = Bukkit.getWorld(p[0]);
            back = world == null ? null : new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
            allowFlight = Boolean.parseBoolean(p[6]);
            flying = Boolean.parseBoolean(p[7]);
        } catch (RuntimeException e) {
            back = null;
        }
        if (target != null) {
            back = target;
        } else if (back == null) {
            back = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        player.setFallDistance(0);
        player.teleport(back);
        GameMode mode = player.getGameMode();
        player.setAllowFlight(allowFlight || mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
        player.setFlying(flying && player.getAllowFlight());
        return true;
    }
}
