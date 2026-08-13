package io.github.melswg.worldmind.core.conversation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Non-blocking admission result for one owned async job. */
public record AsyncWorkSubmission<T>(Optional<AsyncWorkRejection> rejection, CompletionStage<T> completion) {
    public AsyncWorkSubmission {
        rejection = Objects.requireNonNull(rejection, "rejection");
        completion = Objects.requireNonNull(completion, "completion");
    }

    public boolean accepted() { return rejection.isEmpty(); }
}
