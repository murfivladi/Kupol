package dev.vlad.core.modules.regions;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Приват: кубоид в мире, владелец, участники, флаги и приоритет (для вложенных админских регионов). */
final class Region {

    final String name;
    final String world;
    final int minX, minY, minZ, maxX, maxY, maxZ;
    /** null — админский регион без владельца (спавн и т.п.). */
    UUID owner;
    final Set<UUID> members = new LinkedHashSet<>();
    /** Флаг → разрешено ли это посторонним. Нет в карте — значение по умолчанию из конфига. */
    final Map<String, Boolean> flags = new LinkedHashMap<>();
    int priority;
    long created;

    Region(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    boolean contains(Location l) {
        return l.getWorld() != null && l.getWorld().getName().equals(world)
                && contains(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    boolean intersects(Region o) {
        return world.equals(o.world)
                && minX <= o.maxX && maxX >= o.minX
                && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    boolean isMember(UUID id) {
        return id.equals(owner) || members.contains(id);
    }

    int area() {
        return (maxX - minX + 1) * (maxZ - minZ + 1);
    }
}
