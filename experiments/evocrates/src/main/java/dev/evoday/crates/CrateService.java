package dev.evoday.crates;

import dev.evoday.crates.anim.Animation;
import dev.evoday.crates.anim.RouletteAnimation;
import dev.evoday.crates.anim.WorldAnimation;
import dev.evoday.crates.crate.Crate;
import dev.evoday.crates.crate.Reward;
import dev.evoday.crates.storage.KeyRepo;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class CrateService {

    private final EvoCrates plugin;
    // ключи онлайн-игроков, для плейсхолдеров и /crates keys
    private final Map<UUID, Map<String, Integer>> keys = new ConcurrentHashMap<>();
    private final Map<UUID, Animation> opening = new HashMap<>();
    // кто сейчас крутит кейс на этом блоке
    private final Map<Block, UUID> busyBlocks = new HashMap<>();

    public CrateService(EvoCrates plugin) {
        this.plugin = plugin;
    }

    public void loadKeys(UUID uuid) {
        plugin.db().async(c -> KeyRepo.load(c, uuid)).whenComplete((loaded, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "can't load keys of " + uuid, error);
            } else if (Bukkit.getPlayer(uuid) != null) {
                keys.put(uuid, new ConcurrentHashMap<>(loaded));
            }
        });
    }

    public void unloadKeys(UUID uuid) {
        keys.remove(uuid);
    }

    public int cachedKeys(UUID uuid, String crate) {
        Map<String, Integer> map = keys.get(uuid);
        return map == null ? 0 : map.getOrDefault(crate, 0);
    }

    public Map<String, Integer> cachedKeys(UUID uuid) {
        return keys.getOrDefault(uuid, Map.of());
    }

    public CompletableFuture<Integer> addKeys(UUID uuid, String crate, int amount) {
        return plugin.db().async(c -> amount >= 0
                ? KeyRepo.add(c, uuid, crate, amount)
                : Math.max(0, KeyRepo.take(c, uuid, crate, -amount))
        ).whenComplete((now, error) -> {
            Map<String, Integer> map = keys.get(uuid);
            if (error == null && map != null) {
                map.put(crate, now);
            }
        });
    }

    public boolean isOpening(Player player) {
        return opening.containsKey(player.getUniqueId());
    }

    public void open(Player player, Crate crate, Block block) {
        if (opening.containsKey(player.getUniqueId())) {
            plugin.messages().send(player, "already-opening");
            return;
        }
        UUID busyBy = busyBlocks.get(block);
        if (busyBy != null) {
            Player other = Bukkit.getPlayer(busyBy);
            plugin.messages().send(player, "busy", "player", other == null ? "?" : other.getName());
            return;
        }
        // занимаем сразу, чтобы двойной клик не списал два ключа
        opening.put(player.getUniqueId(), null);
        busyBlocks.put(block, player.getUniqueId());

        UUID uuid = player.getUniqueId();
        plugin.db().async(c -> KeyRepo.take(c, uuid, crate.id(), 1)).whenComplete((left, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        release(uuid, block);
                        plugin.getLogger().log(Level.SEVERE, "can't take key", error);
                        if (player.isOnline()) {
                            plugin.messages().send(player, "error");
                        }
                        return;
                    }
                    if (left < 0) {
                        release(uuid, block);
                        if (player.isOnline()) {
                            plugin.messages().send(player, "no-keys", "crate", crate.name());
                        }
                        return;
                    }
                    if (!player.isOnline()) {
                        // ключ списали, а игрок уже вышел - возвращаем
                        release(uuid, block);
                        addKeys(uuid, crate.id(), 1);
                        return;
                    }
                    Map<String, Integer> map = keys.get(uuid);
                    if (map != null) {
                        map.put(crate.id(), left);
                    }
                    start(player, crate, block);
                }));
    }

    private void start(Player player, Crate crate, Block block) {
        Reward reward = crate.roll();
        UUID uuid = player.getUniqueId();
        Runnable finish = () -> {
            release(uuid, block);
            deliver(player, crate, reward);
        };
        Animation animation;
        if (crate.animation() == Crate.Animation.WORLD) {
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            animation = new WorldAnimation(plugin, player, crate, reward, finish, center,
                    plugin.getConfig().getInt("world.spin-ticks", 100),
                    plugin.getConfig().getDouble("world.radius", 1.3),
                    plugin.getConfig().getInt("world.items", 8));
        } else {
            animation = new RouletteAnimation(plugin, player, crate, reward, finish,
                    plugin.getConfig().getInt("roulette.steps", 45),
                    plugin.getConfig().getInt("roulette.max-delay", 10),
                    plugin.messages().raw("roulette-title", "crate", crate.name()));
        }
        opening.put(uuid, animation);
        animation.start();
    }

    private void release(UUID uuid, Block block) {
        opening.remove(uuid);
        busyBlocks.remove(block, uuid);
    }

    private void deliver(Player player, Crate crate, Reward reward) {
        Component name = rewardName(reward);
        if (reward.giveItem()) {
            ItemStack item = reward.item().clone();
            Map<Integer, ItemStack> left = player.getInventory().addItem(item);
            if (!left.isEmpty()) {
                left.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
                plugin.messages().send(player, "inventory-full");
            }
        }
        for (String cmd : reward.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
        }
        if (player.isOnline()) {
            plugin.messages().send(player, "won", "reward", name);
        }
        if (reward.broadcast()) {
            Bukkit.broadcast(plugin.messages().get("broadcast", "player", player.getName(), "reward", name, "crate", crate.name()));
        }
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        plugin.db().async(c -> {
            KeyRepo.log(c, uuid, playerName, crate.id(), reward.id());
            return null;
        });
    }

    // игрок вышел во время анимации - доигрываем сразу и выдаём награду
    public void onQuit(Player player) {
        Animation animation = opening.get(player.getUniqueId());
        if (animation != null) {
            animation.skip();
        }
        unloadKeys(player.getUniqueId());
    }

    public void onRouletteClosed(Player player, RouletteAnimation animation) {
        if (opening.get(player.getUniqueId()) == animation && !animation.isFinished()) {
            animation.skip();
        }
    }

    public void shutdown() {
        for (Animation animation : new ArrayList<>(opening.values())) {
            if (animation != null) {
                animation.skip();
            }
        }
        opening.clear();
        busyBlocks.clear();
    }

    public static Component rewardName(Reward reward) {
        var meta = reward.item().getItemMeta();
        Component base = meta != null && meta.hasDisplayName()
                ? meta.displayName()
                : Component.translatable(reward.item().translationKey());
        int amount = reward.item().getAmount();
        return amount > 1 ? base.append(Component.text(" ×" + amount)) : base;
    }
}
