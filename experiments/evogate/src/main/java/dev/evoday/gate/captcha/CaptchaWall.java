package dev.evoday.gate.captcha;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

// панель из невидимых рамок с картами перед игроком. видит её только он сам.
// карт всего COLS*ROWS на весь сервер, renderer контекстный - у каждого игрока на них своя картинка
public final class CaptchaWall {

    private static final int COLS = 4;
    private static final int ROWS = 2;

    private final JavaPlugin plugin;
    private final MapView[] views = new MapView[COLS * ROWS];
    private final Map<UUID, BufferedImage[]> images = new ConcurrentHashMap<>();
    private final Map<UUID, BufferedImage[]> drawn = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemFrame>> frames = new ConcurrentHashMap<>();
    private final Map<UUID, Long> tickets = new ConcurrentHashMap<>();
    private static final ExecutorService RENDER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "EvoGate-Captcha");
        t.setDaemon(true);
        return t;
    });

    public CaptchaWall(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        File file = new File(plugin.getDataFolder(), "maps.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Integer> ids = new ArrayList<>(yaml.getIntegerList("ids"));
        boolean changed = false;
        for (int i = 0; i < views.length; i++) {
            MapView view = i < ids.size() ? Bukkit.getMap(ids.get(i)) : null;
            if (view == null) {
                view = Bukkit.createMap(Bukkit.getWorlds().get(0));
                if (i < ids.size()) {
                    ids.set(i, view.getId());
                } else {
                    ids.add(view.getId());
                }
                changed = true;
            }
            view.getRenderers().forEach(view::removeRenderer);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            view.setLocked(true);
            view.addRenderer(new Tile(i));
            views[i] = view;
        }
        if (changed) {
            yaml.set("ids", ids);
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "can't save maps.yml", e);
            }
        }
    }

    public void show(Player player, String code) {
        removeFrames(player);
        images.remove(player.getUniqueId());
        UUID uuid = player.getUniqueId();
        long ticket = tickets.merge(uuid, 1L, Long::sum);
        // картинка рисуется ~150мс, не держим этим основной поток
        CompletableFuture.supplyAsync(() -> slice(CaptchaImage.render(code, COLS * 128, ROWS * 128)), RENDER)
                .whenComplete((tiles, error) -> {
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () -> apply(player, uuid, ticket, tiles, error));
                    }
                });
    }

    private void apply(Player player, UUID uuid, long ticket, BufferedImage[] tiles, Throwable error) {
        if (error != null) {
            plugin.getLogger().log(Level.SEVERE, "captcha render failed", error);
            return;
        }
        // пока рисовали, игрок мог выйти или ввести код
        if (!player.isOnline() || tickets.getOrDefault(uuid, 0L) != ticket) {
            return;
        }
        images.put(uuid, tiles);
        place(player);
    }

    private static BufferedImage[] slice(BufferedImage full) {
        BufferedImage[] tiles = new BufferedImage[COLS * ROWS];
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                tiles[row * COLS + col] = full.getSubimage(col * 128, row * 128, 128, 128);
            }
        }
        return tiles;
    }

    private void place(Player player) {
        // рамки вешаются только по сторонам света, поэтому поворачиваем игрока ровно к панели
        BlockFace toPanel = cardinal(player.getLocation().getYaw());
        BlockFace facing = toPanel.getOppositeFace();
        Location eye = player.getEyeLocation();
        eye.setYaw(yaw(toPanel));
        eye.setPitch(0);
        Location feet = player.getLocation();
        feet.setYaw(yaw(toPanel));
        feet.setPitch(0);
        player.teleport(feet);

        int distance = Math.max(2, plugin.getConfig().getInt("captcha.distance", 5));
        Location base = eye.getBlock().getLocation().add(toPanel.getModX() * distance, 0, toPanel.getModZ() * distance);
        // правая рука смотрящего на панель
        int rx = -toPanel.getModZ();
        int rz = toPanel.getModX();

        List<ItemFrame> list = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int offset = col - COLS / 2;
                Location at = base.clone().add(rx * offset, ROWS - 1 - row, rz * offset);
                list.add(spawnFrame(player, at, facing, views[row * COLS + col]));
            }
        }
        frames.put(player.getUniqueId(), list);
    }

    private ItemFrame spawnFrame(Player owner, Location at, BlockFace facing, MapView view) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        map.setItemMeta(meta);
        ItemFrame frame = at.getWorld().spawn(at, ItemFrame.class, f -> {
            f.setFacingDirection(facing, true);
            f.setPersistent(false);
            f.setVisibleByDefault(false);
            f.setVisible(false);
            f.setFixed(true);
            f.setInvulnerable(true);
            f.setItem(map, false);
        });
        owner.showEntity(plugin, frame);
        return frame;
    }

    public void hide(Player player) {
        removeFrames(player);
        tickets.remove(player.getUniqueId());
        images.remove(player.getUniqueId());
        drawn.remove(player.getUniqueId());
    }

    private void removeFrames(Player player) {
        List<ItemFrame> list = frames.remove(player.getUniqueId());
        if (list != null) {
            list.forEach(ItemFrame::remove);
        }
    }

    public void hideAll() {
        frames.values().forEach(list -> list.forEach(ItemFrame::remove));
        frames.clear();
        tickets.clear();
        images.clear();
        drawn.clear();
    }

    private static BlockFace cardinal(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y >= 45 && y < 135) {
            return BlockFace.WEST;
        }
        if (y >= 135 && y < 225) {
            return BlockFace.NORTH;
        }
        if (y >= 225 && y < 315) {
            return BlockFace.EAST;
        }
        return BlockFace.SOUTH;
    }

    private static float yaw(BlockFace face) {
        return switch (face) {
            case NORTH -> 180;
            case EAST -> -90;
            case WEST -> 90;
            default -> 0;
        };
    }

    private final class Tile extends MapRenderer {

        private final int index;

        Tile(int index) {
            super(true);
            this.index = index;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            BufferedImage[] tiles = images.get(player.getUniqueId());
            if (tiles == null) {
                return;
            }
            BufferedImage[] done = drawn.computeIfAbsent(player.getUniqueId(), k -> new BufferedImage[COLS * ROWS]);
            if (done[index] == tiles[index]) {
                return;
            }
            canvas.drawImage(0, 0, tiles[index]);
            done[index] = tiles[index];
        }
    }
}
