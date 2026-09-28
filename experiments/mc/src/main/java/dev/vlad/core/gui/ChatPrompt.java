package dev.vlad.core.gui;

import dev.vlad.core.VladCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Ввод текста из меню: меню закрывается, следующее сообщение игрока в чат
 * (оно никому не показывается) уходит в обработчик. "отмена"/"cancel" — отменить.
 * Ответ обрабатывается в основном потоке; через 60 секунд ожидание снимается.
 */
public final class ChatPrompt implements Listener {

    private static final long TIMEOUT_MS = 60_000;

    private final VladCore plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(VladCore plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** question — ключ сообщения с вопросом из messages.yml. */
    public void ask(Player player, String question, Consumer<String> answer, String... placeholders) {
        player.closeInventory();
        pending.put(player.getUniqueId(), new Pending(answer, System.currentTimeMillis() + TIMEOUT_MS));
        plugin.messages().send(player, question, placeholders);
        plugin.messages().send(player, "gui.prompt-hint");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null || p.expires < System.currentTimeMillis()) {
            return;
        }
        event.setCancelled(true);
        String text = event.getMessage().trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
                plugin.messages().send(player, "gui.prompt-cancelled");
                return;
            }
            p.answer.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private static final class Pending {
        final Consumer<String> answer;
        final long expires;

        Pending(Consumer<String> answer, long expires) {
            this.answer = answer;
            this.expires = expires;
        }
    }
}
