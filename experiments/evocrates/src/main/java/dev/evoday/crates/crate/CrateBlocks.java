package dev.evoday.crates.crate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

// какие блоки являются кейсами + голограммы над ними
public final class CrateBlocks {

    public record Pos(String world, int x, int y, int z) {

        static Pos of(Block b) {
            return new Pos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }

        public Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y + 0.5, z + 0.5);
        }

        @Override
        public String toString() {
            return world + ";" + x + ";" + y + ";" + z;
        }
    }

    private final JavaPlugin plugin;
    private final CrateRegistry registry;
    private final File file;
    private final Map<Pos, String> blocks = new HashMap<>();
    private final Map<Pos, TextDisplay> holograms = new HashMap<>();

    public CrateBlocks(JavaPlugin plugin, CrateRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.file = new File(plugin.getDataFolder(), "blocks.yml");
    }

    public void load() {
        blocks.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String line : yaml.getStringList("blocks")) {
            String[] p = line.split(";");
            if (p.length == 5) {
                blocks.put(new Pos(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])), p[4]);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        blocks.forEach((pos, id) -> lines.add(pos + ";" + id));
        yaml.set("blocks", lines);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "can't save blocks.yml", e);
        }
    }

    public Crate crateAt(Block block) {
        return registry.get(blocks.get(Pos.of(block)));
    }

    public void set(Block block, Crate crate) {
        Pos pos = Pos.of(block);
        blocks.put(pos, crate.id());
        removeHologram(pos);
        save();
        refresh();
    }

    public boolean remove(Block block) {
        Pos pos = Pos.of(block);
        if (blocks.remove(pos) == null) {
            return false;
        }
        removeHologram(pos);
        save();
        return true;
    }

    // голограммы не сохраняются в мире, поэтому после выгрузки чанка их надо создать заново.
    // вызывается по таймеру
    public void refresh() {
        blocks.forEach((pos, id) -> {
            TextDisplay existing = holograms.get(pos);
            if (existing != null && existing.isValid()) {
                return;
            }
            Crate crate = registry.get(id);
            Location loc = pos.center();
            if (crate == null || loc == null || crate.hologram().isEmpty()
                    || !loc.getWorld().isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                return;
            }
            Location at = loc.clone().add(0, 0.6 + 0.27 * crate.hologram().size(), 0);
            TextDisplay display = loc.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.text(Component.join(JoinConfiguration.newlines(), crate.hologram()));
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            });
            holograms.put(pos, display);
        });
    }

    private void removeHologram(Pos pos) {
        TextDisplay d = holograms.remove(pos);
        if (d != null) {
            d.remove();
        }
    }

    public void removeAllHolograms() {
        holograms.values().forEach(TextDisplay::remove);
        holograms.clear();
    }
}
