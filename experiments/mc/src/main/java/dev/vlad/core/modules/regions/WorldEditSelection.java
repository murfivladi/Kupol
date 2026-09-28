package dev.vlad.core.modules.regions;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.entity.Player;

/** Выделение игрока топориком WorldEdit. Класс трогается, только если WorldEdit установлен. */
final class WorldEditSelection {

    private WorldEditSelection() {
    }

    /** {minX, minY, minZ, maxX, maxY, maxZ} в текущем мире игрока, либо null — выделения нет. */
    static int[] of(Player player) {
        com.sk89q.worldedit.entity.Player wePlayer = BukkitAdapter.adapt(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
        try {
            Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return new int[]{min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()};
        } catch (IncompleteRegionException e) {
            return null;
        }
    }
}
