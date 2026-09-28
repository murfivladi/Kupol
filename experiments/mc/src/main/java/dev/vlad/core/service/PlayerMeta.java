package dev.vlad.core.service;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Префикс, суффикс и баланс игрока — через Vault (префиксы отдаёт LuckPerms,
 * баланс — любая экономика, в том числе наша). Без Vault — пустые строки.
 * Все обращения к классам Vault спрятаны в {@link VaultBridge}, который загружается,
 * только если Vault установлен.
 */
public final class PlayerMeta {

    private final boolean vault = Bukkit.getPluginManager().getPlugin("Vault") != null;

    public boolean available() {
        return vault && VaultBridge.hasChat();
    }

    public String prefix(Player player) {
        return vault ? VaultBridge.prefix(player) : "";
    }

    public String suffix(Player player) {
        return vault ? VaultBridge.suffix(player) : "";
    }

    /** Баланс в формате экономики ("1 250В"), или "" без экономики. */
    public String balance(Player player) {
        return vault ? VaultBridge.balance(player) : "";
    }

    // ---- деньги (для модулей: казна клана, аукцион...) ----

    public boolean economyAvailable() {
        return vault && VaultBridge.hasEconomy();
    }

    public String format(double amount) {
        return vault ? VaultBridge.format(amount) : String.valueOf(amount);
    }

    /** Снять деньги; false — не хватает или экономики нет. */
    public boolean withdraw(OfflinePlayer player, double amount) {
        return vault && VaultBridge.withdraw(player, amount);
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        return vault && VaultBridge.deposit(player, amount);
    }
}
