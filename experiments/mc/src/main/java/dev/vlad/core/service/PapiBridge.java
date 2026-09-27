package dev.vlad.core.service;

import dev.vlad.core.VladCore;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Обращения к PlaceholderAPI. Загружается, только если он установлен. */
final class PapiBridge {

    private PapiBridge() {
    }

    static String apply(OfflinePlayer player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    static void registerExpansion(VladCore plugin, Placeholders placeholders) {
        new Expansion(plugin, placeholders).register();
    }

    /** %vladcore_...% — встроенное расширение, отдельно скачивать через /papi ecloud не нужно. */
    private static final class Expansion extends PlaceholderExpansion {

        private final VladCore plugin;
        private final Placeholders placeholders;

        Expansion(VladCore plugin, Placeholders placeholders) {
            this.plugin = plugin;
            this.placeholders = placeholders;
        }

        @Override
        public String getIdentifier() {
            return "vladcore";
        }

        @Override
        public String getAuthor() {
            return String.join(", ", plugin.getDescription().getAuthors());
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        /** Не выгружать при /papi reload — расширение живёт вместе с плагином. */
        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            return placeholders.resolve(player, params);
        }
    }
}
