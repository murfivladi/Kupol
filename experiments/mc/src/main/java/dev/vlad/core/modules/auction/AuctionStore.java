package dev.vlad.core.modules.auction;

import dev.vlad.core.VladCore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Лоты и хранилища игроков в auction.yml. Все изменения — только из основного
 * потока (см. AuctionModule), поэтому без синхронизации.
 */
final class AuctionStore {

    static final class Listing {
        final String id;
        final UUID seller;
        final ItemStack item;
        final double price;
        final long created;
        final long expires;

        Listing(String id, UUID seller, ItemStack item, double price, long created, long expires) {
            this.id = id;
            this.seller = seller;
            this.item = item;
            this.price = price;
            this.created = created;
            this.expires = expires;
        }
    }

    private final VladCore plugin;
    private final File file;
    /** id → лот, в порядке выставления. */
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    /** Хранилище: просроченные/снятые лоты и то, что не влезло в инвентарь. */
    private final Map<UUID, List<ItemStack>> storage = new HashMap<>();

    AuctionStore(VladCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auction.yml");
    }

    void load() {
        listings.clear();
        storage.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ls = yaml.getConfigurationSection("listings");
        if (ls != null) {
            for (String id : ls.getKeys(false)) {
                ConfigurationSection s = ls.getConfigurationSection(id);
                ItemStack item = s.getItemStack("item");
                if (item == null) {
                    continue;
                }
                listings.put(id, new Listing(id, UUID.fromString(s.getString("seller")), item,
                        s.getDouble("price"), s.getLong("created"), s.getLong("expires")));
            }
        }
        ConfigurationSection st = yaml.getConfigurationSection("storage");
        if (st != null) {
            for (String uuid : st.getKeys(false)) {
                List<ItemStack> items = new ArrayList<>();
                for (Object o : st.getList(uuid, new ArrayList<>())) {
                    if (o instanceof ItemStack) {
                        items.add((ItemStack) o);
                    }
                }
                if (!items.isEmpty()) {
                    storage.put(UUID.fromString(uuid), items);
                }
            }
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String p = "listings." + l.id + ".";
            yaml.set(p + "seller", l.seller.toString());
            yaml.set(p + "item", l.item);
            yaml.set(p + "price", l.price);
            yaml.set(p + "created", l.created);
            yaml.set(p + "expires", l.expires);
        }
        storage.forEach((uuid, items) -> yaml.set("storage." + uuid, items));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить auction.yml", e);
        }
    }

    void add(Listing l) {
        listings.put(l.id, l);
    }

    Listing get(String id) {
        return listings.get(id);
    }

    Listing remove(String id) {
        return listings.remove(id);
    }

    List<Listing> all() {
        return new ArrayList<>(listings.values());
    }

    List<Listing> bySeller(UUID seller) {
        List<Listing> result = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (l.seller.equals(seller)) {
                result.add(l);
            }
        }
        return result;
    }

    List<ItemStack> storage(UUID player) {
        return storage.computeIfAbsent(player, k -> new ArrayList<>());
    }

    void cleanStorage(UUID player) {
        List<ItemStack> items = storage.get(player);
        if (items != null && items.isEmpty()) {
            storage.remove(player);
        }
    }
}
