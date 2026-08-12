package io.github.melswg.worldmind.fabric;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Vanilla server-message delivery; it never creates a signed player chat message. */
final class FabricServerChatSink implements ServerChatSink {
    private final MinecraftServer server;

    FabricServerChatSink(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void broadcast(Text message) {
        server.getPlayerManager().broadcast(Objects.requireNonNull(message, "message"), false);
    }

    @Override
    public boolean sendPrivate(UUID playerId, Text message) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(Objects.requireNonNull(playerId, "playerId"));
        if (player == null) {
            return false;
        }
        player.sendMessage(Objects.requireNonNull(message, "message"), false);
        return true;
    }
}
