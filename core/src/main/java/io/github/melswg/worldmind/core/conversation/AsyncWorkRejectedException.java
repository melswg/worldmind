package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;

/** Completion cause for work cancelled after admission during coordinator shutdown. */
public final class AsyncWorkRejectedException extends RuntimeException {
    private final AsyncWorkRejection rejection;

    public AsyncWorkRejectedException(AsyncWorkRejection rejection) {
        super(Objects.requireNonNull(rejection, "rejection").name());
        this.rejection = rejection;
    }

    public AsyncWorkRejection rejection() { return rejection; }
}
