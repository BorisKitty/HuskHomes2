package net.william278.huskhomes.network;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.player.OnlineUser;
import net.william278.huskhomes.position.Server;
import net.william278.huskhomes.teleport.Teleport;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public abstract class NetworkMessenger {

    /**
     * Name of the network message channel for HuskHomes messages
     */
    public static final String NETWORK_MESSAGE_CHANNEL = "huskhomes:main";

    /**
     * Map of message UUIDs to messages being processed
     */
    protected HashMap<UUID, CompletableFuture<Message>> processingMessages;

    /**
     * List of pending futures for processing {@link #fetchServerName(OnlineUser)} requests
     */
    protected List<CompletableFuture<String>> serverNameRequests;

    /**
     * List of pending futures for processing {@link #getOnlinePlayerNames(OnlineUser)} requests
     */
    protected List<CompletableFuture<String[]>> onlinePlayerNamesRequests;

    /**
     * List of pending futures for processing {@link #fetchOnlineServerList(OnlineUser)} requests
     */
    protected List<CompletableFuture<String[]>> onlineServersRequests;

    /**
     * ID of the HuskHomes network cluster this server is on
     */
    protected String clusterId;

    /**
     * The implementing HuskHomes plugin instance
     */
    protected HuskHomes plugin;

    /**
     * The time-to-live of a message before it expires and the {@link CompletableFuture} is cancelled
     */
    private final long MESSAGE_TIME_OUT = 5L;

    /**
     * Initialize the network messenger
     *
     * @param implementor Instance of the implementing plugin
     */
    public void initialize(@NotNull HuskHomes implementor) {
        this.processingMessages = new HashMap<>();
        this.serverNameRequests = new ArrayList<>();
        this.onlinePlayerNamesRequests = new ArrayList<>();
        this.onlineServersRequests = new ArrayList<>();
        this.clusterId = implementor.getSettings().clusterId;
        this.plugin = implementor;
    }

    /**
     * Fetch a list of online player usernames from the proxy
     *
     * @param requester {@link OnlineUser} to send the request
     * @return Future returning a {@link List} of usernames of players on the proxy network
     */
    public abstract CompletableFuture<String[]> getOnlinePlayerNames(@NotNull OnlineUser requester);

    /**
     * Find a player on the proxy network by username to validate if they exist.
     *
     * @param requester  {@link OnlineUser} to send the request
     * @param playerName Approximate name of the player to find. If an exact match is not found,
     *                   this will attempt to return the closest match
     * @return Future returning an {@link Optional} of the player's canonical username if found;
     * otherwise an empty {@link Optional}
     */
    public final CompletableFuture<Optional<String>> findPlayer(@NotNull OnlineUser requester, @NotNull String playerName) {
        return getOnlinePlayerNames(requester).thenApply(networkedPlayers ->
                Arrays.stream(networkedPlayers)
                        .filter(user -> user.equalsIgnoreCase(playerName))
                        .findFirst()
                        .or(() -> Arrays.stream(networkedPlayers)
                                .filter(user -> user.toLowerCase().startsWith(playerName))
                                .findFirst()));
    }

    /**
     * Fetch the name of this server on the proxy network
     *
     * @param requester {@link OnlineUser} to send the request
     * @return Future returning the name of this server on the network
     */
    public abstract CompletableFuture<String> fetchServerName(@NotNull OnlineUser requester);

    /**
     * Fetch a list of online server names proxy
     *
     * @param requester {@link OnlineUser} to send the request
     * @return Future returning a {@link List} of online servers connected to the proxy network
     */
    public abstract CompletableFuture<String[]> fetchOnlineServerList(@NotNull OnlineUser requester);

    /**
     * Send a {@link OnlineUser} to a target {@link Server} on the proxy network
     *
     * @param server The {@link Server} to send the {@link OnlineUser} to
     * @return A future, returning {@code true} if the player was sent.<p>
     * If the player could not be sent (i.e. the server was offline), this will return {@code false}
     */
    public abstract CompletableFuture<Boolean> sendPlayer(@NotNull OnlineUser onlineUser, @NotNull Server server);

    /**
     * Send a network message
     *
     * @param sender  {@link OnlineUser} sending the message
     * @param message {@link Message} to send
     * @return Future returning the {@link Message} sent, that will time out after
     * @apiNote This method invokes {@link #dispatchMessage(OnlineUser, Message)}, applying a time-to-live timeOut
     */
    public final CompletableFuture<Optional<Message>> sendMessage(@NotNull OnlineUser sender, @NotNull Message message) {
        return dispatchMessage(sender, message)
                .orTimeout(MESSAGE_TIME_OUT, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    plugin.getLoggingAdapter().log(Level.WARNING, "Message dispatch after " + MESSAGE_TIME_OUT + " seconds", e);
                    return null;
                })
                .thenApply(result -> {
                    processingMessages.remove(message.uuid);
                    return Optional.ofNullable(result);
                });
    }

    /**
     * Dispatch a {@link Message} via the appropriate handler
     *
     * @param sender  The {@link OnlineUser} that sent the {@link Message}
     * @param message The {@link Message} to dispatch
     * @return A future containing a reply to the {@link Message} that was sent
     */
    protected abstract CompletableFuture<Message> dispatchMessage(@NotNull OnlineUser sender, @NotNull Message message);

    /**
     * Send a reply to a received {@link Message}
     *
     * @param reply The reply {@link Message} to send
     */
    protected abstract void sendReply(@NotNull OnlineUser replier, @NotNull Message reply);

    /**
     * Handle and action received network {@link Message}s
     *
     * @param receiver The online {@link OnlineUser} receiving the message
     * @param message  The received {@link Message}
     */
    protected final void handleMessage(@NotNull OnlineUser receiver, @NotNull Message message) {
        switch (message.relayType) {
            // Handle a message and send reply
            case MESSAGE -> prepareReply(receiver, message).thenAccept(reply -> sendReply(receiver, message));
            // Handle a reply message
            case REPLY -> {
                if (processingMessages.containsKey(message.uuid)) {
                    final Message finalMessage = message;
                    processingMessages.get(message.uuid).completeAsync(() -> finalMessage);
                    return;
                }
                plugin.getLoggingAdapter().log(Level.WARNING, "Received a reply to a message that was not sent by this server");
            }
        }
    }

    /**
     * Handle different message types, executed needed operations and dispatches a reply message
     * to the sender
     *
     * @param receiver The {@link OnlineUser} receiving the message
     * @param message  The received {@link Message}
     * @return A future containing the reply {@link Message}
     */
    private CompletableFuture<Message> prepareReply(@NotNull final OnlineUser receiver, @NotNull final Message message) {
        return CompletableFuture.supplyAsync(() -> {
            switch (message.type) {
                case TELEPORT_TO_POSITION_REQUEST -> {
                    if (message.payload.position != null) {
                        message.payload = MessagePayload.withTeleportResult(Teleport.builder(plugin, receiver)
                                .setTarget(message.payload.position)
                                .toTeleport().join().execute().join().getState());
                    } else {
                        message.payload = MessagePayload.empty();
                    }
                }
                case POSITION_REQUEST -> message.payload = MessagePayload.withPosition(receiver.getPosition());
                case TELEPORT_REQUEST -> {
                    if (message.payload.teleportRequest != null) {
                        plugin.getRequestManager().sendLocalTeleportRequest(message.payload.teleportRequest, receiver);
                    } else {
                        message.payload = MessagePayload.empty();
                    }
                }
                case TELEPORT_REQUEST_RESPONSE -> {
                    if (message.payload.teleportRequest != null) {
                        plugin.getRequestManager().handleLocalRequestResponse(receiver, message.payload.teleportRequest);
                    } else {
                        message.payload = MessagePayload.empty();
                    }
                }
            }
            return message;
        }).exceptionally(exception -> {
            plugin.getLoggingAdapter().log(Level.WARNING, "Failed to prepare message reply", exception);
            message.payload = MessagePayload.empty();
            return message;
        }).thenApply(reply -> {
            formatReplyMessage(message, receiver);
            return reply;
        });
    }

    /**
     * Prepare a {@link Message} to be sent as a reply to a received {@link Message}
     *
     * @param message  The received {@link Message}
     * @param receiver The {@link OnlineUser} receiving the message
     */
    private void formatReplyMessage(@NotNull Message message, @NotNull OnlineUser receiver) {
        message.targetPlayer = message.sender;
        message.sender = receiver.username;
        message.relayType = Message.RelayType.REPLY;
    }

    /**
     * Close the network messenger
     */
    public abstract void terminate();

}