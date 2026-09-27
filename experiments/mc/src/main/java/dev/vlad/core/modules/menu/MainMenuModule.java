package dev.vlad.core.modules.menu;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.gui.Items;
import dev.vlad.core.gui.Menu;
import dev.vlad.core.module.Module;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Главное меню /menu, кнопки — из menu.yml. */
public final class MainMenuModule extends Module {

    private final File file;
    private YamlConfiguration config;

    public MainMenuModule(VladCore plugin) {
        super(plugin, "menu");
        this.file = new File(plugin.getDataFolder(), "menu.yml");
    }

    @Override
    protected void onEnable() {
        load();
        command(new MenuCommand());
    }

    @Override
    protected void onReload() {
        load();
    }

    private void load() {
        if (!file.exists()) {
            plugin.saveResource("menu.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    private final class MainMenu extends Menu {
        MainMenu(Player viewer) {
            super(viewer, Math.max(1, Math.min(6, config.getInt("rows", 3))), config.getString("title", "Меню"));
        }

        @Override
        protected void render() {
            String filler = config.getString("filler", "");
            if (!filler.isEmpty()) {
                ItemStack pane = Items.of(Items.material(filler), " ", Collections.emptyList());
                for (int slot = 0; slot < size(); slot++) {
                    set(slot, pane);
                }
            }
            ConfigurationSection items = config.getConfigurationSection("items");
            if (items == null) {
                return;
            }
            for (String key : items.getKeys(false)) {
                ConfigurationSection b = items.getConfigurationSection(key);
                if (b == null) {
                    continue;
                }
                int slot = b.getInt("slot", -1);
                if (slot < 0 || slot >= size()) {
                    plugin.getLogger().warning("menu.yml: кнопка " + key + " — слот вне меню");
                    continue;
                }
                String name = fill(b.getString("name", key));
                List<String> lore = new ArrayList<>();
                b.getStringList("lore").forEach(l -> lore.add(fill(l)));
                Material material = Items.material(b.getString("material"));
                ItemStack icon = material == Material.PLAYER_HEAD
                        ? Items.head(viewer, name, lore)
                        : Items.of(material, name, lore);
                String command = b.getString("command", "");
                boolean close = b.getBoolean("close", false);
                set(slot, icon, e -> {
                    if (close) {
                        viewer.closeInventory();
                    }
                    if (!command.isEmpty()) {
                        viewer.performCommand(fill(command));
                    }
                });
            }
        }

        private String fill(String text) {
            return plugin.placeholders().apply(viewer, text.replace("{player}", viewer.getName()));
        }
    }

    private final class MenuCommand extends ModuleCommand {
        MenuCommand() {
            super(MainMenuModule.this.plugin, "menu", "vcore.menu", "/menu", "gui");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player != null) {
                new MainMenu(player).open();
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }
}
