package dev.vlad.core.service;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Прямые обращения к Vault. Провайдеры ищутся при каждом вызове (это дёшево):
 * так подхватываются плагины, зарегистрировавшиеся позже нас.
 */
final class VaultBridge {

    private VaultBridge() {
    }

    private static <T> T service(Class<T> type) {
        RegisteredServiceProvider<T> rsp = Bukkit.getServicesManager().getRegistration(type);
        return rsp == null ? null : rsp.getProvider();
    }

    static boolean hasChat() {
        return service(Chat.class) != null;
    }

    static String prefix(Player player) {
        Chat chat = service(Chat.class);
        String p = chat == null ? null : chat.getPlayerPrefix(player);
        return p == null ? "" : p;
    }

    static String suffix(Player player) {
        Chat chat = service(Chat.class);
        String s = chat == null ? null : chat.getPlayerSuffix(player);
        return s == null ? "" : s;
    }

    static String balance(Player player) {
        Economy economy = service(Economy.class);
        return economy == null ? "" : economy.format(economy.getBalance(player));
    }

    static boolean hasEconomy() {
        return service(Economy.class) != null;
    }

    static String format(double amount) {
        Economy economy = service(Economy.class);
        return economy == null ? String.valueOf(amount) : economy.format(amount);
    }

    static boolean withdraw(OfflinePlayer player, double amount) {
        Economy economy = service(Economy.class);
        return economy != null && economy.has(player, amount) && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    static boolean deposit(OfflinePlayer player, double amount) {
        Economy economy = service(Economy.class);
        return economy != null && economy.depositPlayer(player, amount).transactionSuccess();
    }
}
