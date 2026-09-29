package dev.evoday.gate.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// часть EvoGate для Velocity: /server, /hub и прочие команды прокси до бэкенда не доходят,
// поэтому до входа их блокирует прокси. Кто вошёл - сообщает бэкенд через канал evogate:auth.
// Ограничения действуют только на серверах из auth-servers (config.properties), на лобби игрок свободен
public final class EvoGateProxy {

    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("evogate", "auth");
    private static final Component NOT_LOGGED = MiniMessage.miniMessage().deserialize(
            "<gradient:#F19404:#F14704><b>Proxy</b></gradient> <dark_gray>»</dark_gray> <red>Сначала войдите.</red>");

    private final ProxyServer proxy;
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Path dataDirectory;
    // имена серверов из velocity.toml в нижнем регистре
    private volatile Set<String> authServers = Set.of("vanilla");

    @Inject
    public EvoGateProxy(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    private void loadConfig() {
        Path file = dataDirectory.resolve("config.properties");
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(file)) {
                Files.writeString(file, "# серверы (имена из velocity.toml через запятую), на которых нужна капча и вход.\n"
                        + "# на остальных (лобби) команды прокси и переходы не блокируются\n"
                        + "auth-servers=vanilla\n", StandardCharsets.UTF_8);
            }
            Properties props = new Properties();
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            }
            Set<String> names = new HashSet<>();
            Arrays.stream(props.getProperty("auth-servers", "vanilla").split(","))
                    .map(n -> n.trim().toLowerCase(Locale.ROOT)).filter(n -> !n.isEmpty()).forEach(names::add);
            authServers = Set.copyOf(names);
        } catch (IOException e) {
            // без конфига остаётся значение по умолчанию
        }
    }

    private boolean isAuthServer(String name) {
        return authServers.contains(name.toLowerCase(Locale.ROOT));
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        loadConfig();
        proxy.getChannelRegistrar().register(CHANNEL);
    }

    // при входе на сервер с авторизацией старое "вошёл" не действует - бэкенд пришлёт актуальное
    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        if (isAuthServer(event.getServer().getServerInfo().getName())) {
            authed.remove(event.getPlayer().getUniqueId());
        }
    }

    private boolean locked(Player player) {
        return player.getCurrentServer()
                .map(c -> isAuthServer(c.getServerInfo().getName()))
                .orElse(false) && !authed.contains(player.getUniqueId());
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }
        // дальше не пересылаем ни в какую сторону; клиент не должен уметь "войти" сам
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection server) || event.getData().length != 1) {
            return;
        }
        UUID uuid = server.getPlayer().getUniqueId();
        if (event.getData()[0] == 1) {
            authed.add(uuid);
        } else {
            authed.remove(uuid);
        }
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onCommand(CommandExecuteEvent event) {
        CommandSource source = event.getCommandSource();
        if (!(source instanceof Player player) || !locked(player)) {
            return;
        }
        String label = event.getCommand().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        // команды бэкенда (/login, /register) уходят дальше, там их проверяет сам EvoGate
        if (proxy.getCommandManager().hasCommand(label)) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            player.sendMessage(NOT_LOGGED);
        }
    }

    // до входа нельзя уйти на другой сервер (первое подключение пропускаем)
    @Subscribe(priority = Short.MIN_VALUE)
    public void onPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        if (locked(player) && player.getCurrentServer().isPresent()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(NOT_LOGGED);
        }
    }

    // кик с сервера входа (капча, таймаут, антибот) - отключаем, а не отправляем
    // на запасной сервер из try, где EvoGate может не быть
    @Subscribe(priority = Short.MIN_VALUE)
    public void onKicked(KickedFromServerEvent event) {
        if (locked(event.getPlayer())) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    event.getServerKickReason().orElse(Component.text("Disconnected"))));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        authed.remove(event.getPlayer().getUniqueId());
    }
}
