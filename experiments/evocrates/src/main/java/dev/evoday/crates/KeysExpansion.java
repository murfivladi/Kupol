package dev.evoday.crates;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

// %evocrates_keys_<кейс>%
final class KeysExpansion extends PlaceholderExpansion {

    private final EvoCrates plugin;

    KeysExpansion(EvoCrates plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "evocrates";
    }

    @Override
    public String getAuthor() {
        return "EvoDay";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || !params.startsWith("keys_")) {
            return null;
        }
        return String.valueOf(plugin.service().cachedKeys(player.getUniqueId(), params.substring(5)));
    }
}
