package dev.vlad.core.service;

import org.bukkit.Bukkit;
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
}
