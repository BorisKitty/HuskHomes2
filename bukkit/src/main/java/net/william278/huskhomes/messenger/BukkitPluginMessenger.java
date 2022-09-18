package net.william278.huskhomes.messenger;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.BukkitHuskHomes;
import net.william278.huskhomes.player.BukkitPlayer;
import net.william278.huskhomes.player.OnlineUser;
import net.william278.huskhomes.position.Server;
import org.bukkit.Bukkit;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * <a href="https://www.spigotmc.org/wiki/bukkit-bungee-plugin-messaging-channel/">Plugin Messaging channel</a> messenger implementation
 */
public class BukkitPluginMessenger extends NetworkMessenger implements PluginMessageListener {

    /**
     * The name of BungeeCord's provided plugin channel.
     *
     * @implNote Technically, the effective identifier of this channel is {@code bungeecord:main},  but Spigot remaps
     * {@code BungeeCord} automatically to the new one (<a href="https://wiki.vg/Plugin_channels#bungeecord:main">source</a>).
     * Spigot's <a href="https://www.spigotmc.org/wiki/bukkit-bungee-plugin-messaging-channel/">official documentation</a>
     * still instructs usage of {@code BungeeCord} as the name to use, however. It's all a bit inconsistent, so just in case
     * it's best to leave it how it is for to maintain backwards compatibility.
     */
    private static final String BUNGEE_PLUGIN_CHANNEL_NAME = "BungeeCord";

    @Override
    public void initialize(@NotNull HuskHomes implementor) {
        super.initialize(implementor);

        // Register HuskHomes messaging channel
        Bukkit.getMessenger().registerIncomingPluginChannel((BukkitHuskHomes) implementor, BUNGEE_PLUGIN_CHANNEL_NAME, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel((BukkitHuskHomes) implementor, BUNGEE_PLUGIN_CHANNEL_NAME);
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public CompletableFuture<String[]> getOnlinePlayerNames(@NotNull OnlineUser requester) {
        onlinePlayerNamesRequest = new CompletableFuture<>();
        final ByteArrayDataOutput pluginMessageWriter = ByteStreams.newDataOutput();
        pluginMessageWriter.writeUTF("PlayerList");
        pluginMessageWriter.writeUTF("ALL");
        ((BukkitPlayer) requester).sendPluginMessage((BukkitHuskHomes) plugin,
                BUNGEE_PLUGIN_CHANNEL_NAME,
                pluginMessageWriter.toByteArray());
        return onlinePlayerNamesRequest;
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public CompletableFuture<String> getServerName(@NotNull OnlineUser requester) {
        serverNameRequest = new CompletableFuture<>();
        final ByteArrayDataOutput pluginMessageWriter = ByteStreams.newDataOutput();
        pluginMessageWriter.writeUTF("GetServer");
        ((BukkitPlayer) requester).sendPluginMessage((BukkitHuskHomes) plugin,
                BUNGEE_PLUGIN_CHANNEL_NAME,
                pluginMessageWriter.toByteArray());
        return serverNameRequest;
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public CompletableFuture<String[]> getOnlineServers(@NotNull OnlineUser requester) {
        onlineServersRequest = new CompletableFuture<>();
        final ByteArrayDataOutput pluginMessageWriter = ByteStreams.newDataOutput();
        pluginMessageWriter.writeUTF("GetServers");
        ((BukkitPlayer) requester).sendPluginMessage((BukkitHuskHomes) plugin,
                BUNGEE_PLUGIN_CHANNEL_NAME,
                pluginMessageWriter.toByteArray());
        return onlineServersRequest;
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public CompletableFuture<Boolean> sendPlayer(@NotNull OnlineUser onlineUser, @NotNull Server server) {
        return getOnlineServers(onlineUser).thenApplyAsync(onlineServers -> {
            for (String onlineServerName : onlineServers) {
                if (onlineServerName.equals(server.name)) {
                    final ByteArrayDataOutput pluginMessageWriter = ByteStreams.newDataOutput();
                    pluginMessageWriter.writeUTF("Connect");
                    pluginMessageWriter.writeUTF(server.name);
                    ((BukkitPlayer) onlineUser).sendPluginMessage((BukkitHuskHomes) plugin,
                            BUNGEE_PLUGIN_CHANNEL_NAME,
                            pluginMessageWriter.toByteArray());
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Message> sendMessage(@NotNull OnlineUser sender, @NotNull Message message) {
        final CompletableFuture<Message> repliedMessage = new CompletableFuture<>();
        processingMessages.put(message.uuid, repliedMessage);
        sendPluginMessage((BukkitPlayer) sender, message);
        return repliedMessage;
    }

    @Override
    protected void sendReply(@NotNull OnlineUser replier, @NotNull Message reply) {
        sendPluginMessage((BukkitPlayer) replier, reply);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void sendPluginMessage(@NotNull BukkitPlayer player, @NotNull Message message) {
        CompletableFuture.runAsync(() -> {
            final ByteArrayDataOutput messageWriter = ByteStreams.newDataOutput();
            messageWriter.writeUTF("ForwardToPlayer");
            messageWriter.writeUTF(message.targetPlayer);
            messageWriter.writeUTF(NETWORK_MESSAGE_CHANNEL);

            // Write the plugin message
            try (ByteArrayOutputStream messageByteOutputStream = new ByteArrayOutputStream()) {
                try (DataOutputStream messageDataOutputStream = new DataOutputStream(messageByteOutputStream)) {
                    messageDataOutputStream.writeUTF(message.toJson());
                    messageWriter.writeShort(messageByteOutputStream.toByteArray().length);
                    messageWriter.write(messageByteOutputStream.toByteArray());
                }
            } catch (IOException e) {
                plugin.getLoggingAdapter().log(Level.SEVERE,
                        "An error occurred dispatching a plugin message", e);
            }

            // Send the written message
            player.sendPluginMessage((BukkitHuskHomes) plugin,
                    BUNGEE_PLUGIN_CHANNEL_NAME, messageWriter.toByteArray());
        });
    }

    @Override
    public void terminate() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel((BukkitHuskHomes) plugin);
        Bukkit.getMessenger().unregisterIncomingPluginChannel((BukkitHuskHomes) plugin);
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public void onPluginMessageReceived(@NotNull String channel, @NotNull org.bukkit.entity.Player player,
                                        final byte[] messageBytes) {
        if (!channel.equals(BUNGEE_PLUGIN_CHANNEL_NAME)) {
            return;
        }
        final ByteArrayDataInput pluginMessage = ByteStreams.newDataInput(messageBytes);
        final String subChannel = pluginMessage.readUTF();
        switch (subChannel) {
            case NETWORK_MESSAGE_CHANNEL -> {
                final short messageInputLength = pluginMessage.readShort();
                final byte[] messageInputBytes = new byte[messageInputLength];
                pluginMessage.readFully(messageInputBytes);

                // Read the message in byte-by-byte and parse it
                try (DataInputStream messageReader = new DataInputStream(new ByteArrayInputStream(messageInputBytes))) {
                    final Message message = Message.fromJson(messageReader.readUTF());

                    // Ignore plugin messages to other clusters
                    if (!message.clusterId.equals(clusterId)) {
                        return;
                    }

                    handleMessage(BukkitPlayer.adapt(player), message);
                } catch (IOException e) {
                    plugin.getLoggingAdapter().log(Level.SEVERE,
                            "Failed to read an inbound plugin message", e);
                }
            }
            case "GetServer" -> {
                final String serverName = pluginMessage.readUTF();
                if (serverNameRequest != null) {
                    serverNameRequest.completeAsync(() -> serverName);
                }
            }
            case "PlayerList" -> {
                pluginMessage.readUTF(); // Read the server name (unused)
                final String[] playerNames = pluginMessage.readUTF().split(", ");
                if (onlinePlayerNamesRequest != null) {
                    onlinePlayerNamesRequest.completeAsync(() -> playerNames);
                }
            }
            case "GetServers" -> {
                final String[] serverNames = pluginMessage.readUTF().split(", ");
                if (onlineServersRequest != null) {
                    onlineServersRequest.completeAsync(() -> serverNames);
                }
            }
        }
    }

}
