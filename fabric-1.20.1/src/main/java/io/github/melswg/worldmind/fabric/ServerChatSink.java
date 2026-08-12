package io.github.melswg.worldmind.fabric;

import java.util.UUID;
import net.minecraft.text.Text;

/** Minimal Fabric-only delivery seam for public and player-private chat text. */
interface ServerChatSink {
    void broadcast(Text message);

    /** Returns false when the intended recipient is no longer online. */
    boolean sendPrivate(UUID playerId, Text message);
}
