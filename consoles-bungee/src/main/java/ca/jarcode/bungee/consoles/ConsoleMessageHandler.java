package ca.jarcode.bungee.consoles;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class ConsoleMessageHandler implements Listener {

    private final HashMap<String, Object> commands = new HashMap<>();
    private final List<UUID> connected = new ArrayList<>();

    {
        commands.put("clear", (OutgoingHookCommand) (player, args, out) -> {
        });
    }

    ConsoleMessageHandler(Plugin plugin) {
        plugin.getProxy().registerChannel("Console");
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onPluginMessage(PluginMessageEvent event) {
        if (event.getTag().equals("Console")) {
            ByteArrayDataInput input = ByteStreams.newDataInput(event.getData());
            String command = input.readUTF();
            System.out.println("received: " + command);
            Object cmd = commands.get(command);
            if (cmd instanceof IncomingHookCommand) {
                ((IncomingHookCommand) cmd).handle(BungeeConsoles.getProxiedPlayer(event.getReceiver()), input);
            }
        }
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        connected.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onPlayerConnect(final ServerConnectedEvent event) {
        if (!connected.contains(event.getPlayer().getUniqueId())) {
            connected.add(event.getPlayer().getUniqueId());
            execute(event.getPlayer(), event.getServer(), "clear");
        }
    }

    public boolean execute(ProxiedPlayer player, String command, Object... args) {
        return execute(player, player.getServer(), command, args);
    }

    private boolean execute(ProxiedPlayer player, Server server, String command, Object... args) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        Object cmd = commands.get(command);
        if (cmd instanceof OutgoingHookCommand) {
            out.writeUTF(command);
            ((OutgoingHookCommand) cmd).handle(player, args, out);
            server.sendData("Console", out.toByteArray());
            return true;
        } else return false;
    }

}
