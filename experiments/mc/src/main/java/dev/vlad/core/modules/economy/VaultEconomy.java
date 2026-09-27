package dev.vlad.core.modules.economy;

import dev.vlad.core.VladCore;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Провайдер Vault: через него экономикой VladCore пользуются другие плагины
 * (магазины, аукционы, джобсы...). Класс загружается, только если Vault установлен.
 * Банки (общие счета) не поддерживаются.
 */
final class VaultEconomy implements Economy {

    private final VladCore plugin;
    private final EconomyModule module;
    private final Bank bank;

    private VaultEconomy(VladCore plugin, EconomyModule module, Bank bank) {
        this.plugin = plugin;
        this.module = module;
        this.bank = bank;
    }

    static Object register(VladCore plugin, EconomyModule module, Bank bank) {
        VaultEconomy economy = new VaultEconomy(plugin, module, bank);
        Bukkit.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Highest);
        return economy;
    }

    static void unregister(Object economy) {
        Bukkit.getServicesManager().unregister(Economy.class, economy);
    }

    @SuppressWarnings("deprecation")
    private static UUID id(String name) {
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private EconomyResponse change(UUID uuid, double amount, boolean deposit) {
        if (amount < 0) {
            return new EconomyResponse(0, bank.balance(uuid), ResponseType.FAILURE, "Отрицательная сумма");
        }
        boolean ok = bank.add(uuid, deposit ? amount : -amount);
        return new EconomyResponse(amount, bank.balance(uuid),
                ok ? ResponseType.SUCCESS : ResponseType.FAILURE, ok ? null : "Недостаточно средств");
    }

    @Override public boolean isEnabled() { return module.isEnabled(); }
    @Override public String getName() { return plugin.getName(); }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return module.format(amount); }
    @Override public String currencyNamePlural() { return plugin.getConfig().getString("economy.currency-plural", ""); }
    @Override public String currencyNameSingular() { return plugin.getConfig().getString("economy.currency-singular", ""); }

    @Override public boolean hasAccount(String name) { return bank.has(id(name)); }
    @Override public boolean hasAccount(OfflinePlayer p) { return bank.has(p.getUniqueId()); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override public double getBalance(String name) { return bank.balance(id(name)); }
    @Override public double getBalance(OfflinePlayer p) { return bank.balance(p.getUniqueId()); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { return getBalance(name) >= amount; }
    @Override public boolean has(OfflinePlayer p, double amount) { return getBalance(p) >= amount; }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return change(id(name), amount, false); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return change(p.getUniqueId(), amount, false); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return change(id(name), amount, true); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return change(p.getUniqueId(), amount, true); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    @Override public boolean createPlayerAccount(String name) { return bank.create(id(name)); }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return bank.create(p.getUniqueId()); }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Банки не поддерживаются");
    }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
