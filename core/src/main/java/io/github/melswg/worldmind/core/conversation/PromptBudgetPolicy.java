package io.github.melswg.worldmind.core.conversation;

import io.github.melswg.worldmind.core.configuration.GenerationParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stable, provider-neutral prompt sizing and selection for the v1 conversation
 * path. The limits are Unicode code-point estimates with structural and source
 * attribution overhead; they are deliberately not a provider token estimate.
 */
public final class PromptBudgetPolicy {
    /** Maximum estimated provider-visible input, including prompt structure. */
    public static final long MAX_TOTAL_INPUT_CODE_POINTS = 12_000;
    /** Maximum estimated contribution of one untrusted prompt layer. */
    public static final long MAX_UNTRUSTED_LAYER_CODE_POINTS = 2_400;
    /** Maximum estimated contribution of one serialized public-chat fragment. */
    public static final long MAX_SERIALIZED_CHAT_MESSAGE_CODE_POINTS = 900;

    private static final String TRUNCATION_MARKER = "\n[worldmind: content truncated]\n";
    private static final long PROVIDER_REQUEST_OVERHEAD = 64;
    private static final long LAYER_OVERHEAD = 48;
    private static final long FRAGMENT_OVERHEAD = 52;

    private PromptBudgetPolicy() {
    }

    /**
     * Applies the v1 deterministic selection policy without mutating source
     * batches or profile material. An empty result means the trusted
     * floor, or the required newest chat fragment, cannot be represented safely.
     */
    public static Optional<ProviderRequest> select(
        String model,
        GenerationParameters generationParameters,
        List<PromptLayer> originalLayers
    ) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(generationParameters, "generationParameters");
        Optional<List<PromptLayer>> layers = copyAndLimitPerLayer(originalLayers);
        if (layers.isEmpty()) {
            return Optional.empty();
        }
        ProviderRequest selected = new ProviderRequest(model, generationParameters, layers.orElseThrow());

        if (trustedFloorExceedsBudget(selected)) {
            return Optional.empty();
        }

        selected = removeLeastRelevantUntrustedData(selected);
        if (estimate(selected) <= MAX_TOTAL_INPUT_CODE_POINTS) {
            return Optional.of(selected);
        }

        return Optional.ofNullable(trimNewestChatFragmentToFit(selected));
    }

    /** Returns the deterministic provider-neutral size estimate for a request. */
    public static long estimate(ProviderRequest request) {
        Objects.requireNonNull(request, "request");
        long total = PROVIDER_REQUEST_OVERHEAD;
        for (PromptLayer layer : request.promptLayers()) {
            total = saturatedAdd(total, estimate(layer));
        }
        return total;
    }

    private static Optional<List<PromptLayer>> copyAndLimitPerLayer(List<PromptLayer> originalLayers) {
        Objects.requireNonNull(originalLayers, "originalLayers");
        List<PromptLayer> limited = new ArrayList<>(originalLayers.size());
        for (PromptLayer layer : originalLayers) {
            Objects.requireNonNull(layer, "originalLayers must not contain null");
            if (layer.trust() == PromptTrust.TRUSTED_INSTRUCTION) {
                limited.add(layer);
            } else if (layer.type() == PromptLayerType.CURRENT_CHAT_BATCH) {
                PromptLayer limitedChat = limitChatLayer(layer);
                if (!layer.fragments().isEmpty() && (
                    limitedChat.fragments().isEmpty()
                        || !limitedChat.fragments().get(limitedChat.fragments().size() - 1).source()
                            .equals(layer.fragments().get(layer.fragments().size() - 1).source())
                )) {
                    return Optional.empty();
                }
                limited.add(limitedChat);
            } else {
                limited.add(limitUntrustedLayer(layer));
            }
        }
        return Optional.of(List.copyOf(limited));
    }

    private static boolean trustedFloorExceedsBudget(ProviderRequest request) {
        long total = PROVIDER_REQUEST_OVERHEAD;
        for (PromptLayer layer : request.promptLayers()) {
            if (layer.trust() == PromptTrust.TRUSTED_INSTRUCTION) {
                total = saturatedAdd(total, estimate(layer));
            } else {
                total = saturatedAdd(total, emptyLayerEstimate(layer));
            }
        }
        return total > MAX_TOTAL_INPUT_CODE_POINTS;
    }

    private static ProviderRequest removeLeastRelevantUntrustedData(ProviderRequest original) {
        ProviderRequest selected = original;
        for (PromptLayerType type : List.of(
            PromptLayerType.MEMORY,
            PromptLayerType.LORE,
            PromptLayerType.CURRENT_GAME_CONTEXT
        )) {
            while (estimate(selected) > MAX_TOTAL_INPUT_CODE_POINTS && hasFragments(selected, type)) {
                selected = withoutOldestFragment(selected, type);
            }
        }
        while (estimate(selected) > MAX_TOTAL_INPUT_CODE_POINTS && chatFragmentCount(selected) > 1) {
            selected = withoutOldestFragment(selected, PromptLayerType.CURRENT_CHAT_BATCH);
        }
        return selected;
    }

    private static ProviderRequest trimNewestChatFragmentToFit(ProviderRequest original) {
        PromptLayer chat = layer(original, PromptLayerType.CURRENT_CHAT_BATCH);
        if (chat == null || chat.fragments().isEmpty()) {
            return null;
        }
        PromptFragment newest = chat.fragments().get(chat.fragments().size() - 1);
        long fixedWithoutNewest = saturatedSubtract(estimate(original), estimate(newest));
        long available = saturatedSubtract(MAX_TOTAL_INPUT_CODE_POINTS, fixedWithoutNewest);
        PromptFragment trimmed = limitFragment(newest, available);
        if (trimmed == null) {
            return null;
        }
        ProviderRequest selected = replaceNewestChatFragment(original, trimmed);
        return estimate(selected) <= MAX_TOTAL_INPUT_CODE_POINTS ? selected : null;
    }

    private static PromptLayer limitUntrustedLayer(PromptLayer layer) {
        List<PromptFragment> selected = new ArrayList<>();
        long used = emptyLayerEstimate(layer);
        for (PromptFragment fragment : layer.fragments()) {
            long remaining = saturatedSubtract(MAX_UNTRUSTED_LAYER_CODE_POINTS, used);
            PromptFragment limited = limitFragment(fragment, remaining);
            if (limited == null) {
                break;
            }
            selected.add(limited);
            used = saturatedAdd(used, estimate(limited));
            if (used >= MAX_UNTRUSTED_LAYER_CODE_POINTS) {
                break;
            }
        }
        return new PromptLayer(layer.type(), layer.trust(), selected);
    }

    private static PromptLayer limitChatLayer(PromptLayer layer) {
        List<PromptFragment> selectedNewestFirst = new ArrayList<>();
        long used = emptyLayerEstimate(layer);
        List<PromptFragment> fragments = layer.fragments();
        for (int index = fragments.size() - 1; index >= 0; index--) {
            long remainingInLayer = saturatedSubtract(MAX_UNTRUSTED_LAYER_CODE_POINTS, used);
            long fragmentLimit = Math.min(remainingInLayer, MAX_SERIALIZED_CHAT_MESSAGE_CODE_POINTS);
            PromptFragment limited = limitFragment(fragments.get(index), fragmentLimit);
            if (limited == null) {
                continue;
            }
            selectedNewestFirst.add(limited);
            used = saturatedAdd(used, estimate(limited));
            if (used >= MAX_UNTRUSTED_LAYER_CODE_POINTS) {
                break;
            }
        }
        List<PromptFragment> ordered = new ArrayList<>(selectedNewestFirst.size());
        for (int index = selectedNewestFirst.size() - 1; index >= 0; index--) {
            ordered.add(selectedNewestFirst.get(index));
        }
        return new PromptLayer(layer.type(), layer.trust(), ordered);
    }

    private static PromptFragment limitFragment(PromptFragment fragment, long maximum) {
        long whole = estimate(fragment);
        if (whole <= maximum) {
            return fragment;
        }
        long marker = codePointCount(TRUNCATION_MARKER);
        long overhead = saturatedAdd(FRAGMENT_OVERHEAD, codePointCount(fragment.source()));
        long availableContent = saturatedSubtract(saturatedSubtract(maximum, overhead), marker);
        if (availableContent <= 0) {
            return null;
        }
        String shortened = prefixByCodePoints(fragment.content(), availableContent);
        if (shortened.isEmpty()) {
            return null;
        }
        return new PromptFragment(fragment.source(), shortened + TRUNCATION_MARKER);
    }

    private static ProviderRequest withoutOldestFragment(ProviderRequest request, PromptLayerType type) {
        List<PromptLayer> layers = new ArrayList<>(request.promptLayers().size());
        for (PromptLayer layer : request.promptLayers()) {
            if (layer.type() == type && !layer.fragments().isEmpty()) {
                layers.add(new PromptLayer(layer.type(), layer.trust(), layer.fragments().subList(1, layer.fragments().size())));
            } else {
                layers.add(layer);
            }
        }
        return new ProviderRequest(request.model(), request.generationParameters(), layers);
    }

    private static ProviderRequest replaceNewestChatFragment(ProviderRequest request, PromptFragment replacement) {
        List<PromptLayer> layers = new ArrayList<>(request.promptLayers().size());
        for (PromptLayer layer : request.promptLayers()) {
            if (layer.type() != PromptLayerType.CURRENT_CHAT_BATCH) {
                layers.add(layer);
                continue;
            }
            List<PromptFragment> fragments = new ArrayList<>(layer.fragments());
            fragments.set(fragments.size() - 1, replacement);
            layers.add(new PromptLayer(layer.type(), layer.trust(), fragments));
        }
        return new ProviderRequest(request.model(), request.generationParameters(), layers);
    }

    private static boolean hasFragments(ProviderRequest request, PromptLayerType type) {
        PromptLayer layer = layer(request, type);
        return layer != null && !layer.fragments().isEmpty();
    }

    private static int chatFragmentCount(ProviderRequest request) {
        PromptLayer layer = layer(request, PromptLayerType.CURRENT_CHAT_BATCH);
        return layer == null ? 0 : layer.fragments().size();
    }

    private static PromptLayer layer(ProviderRequest request, PromptLayerType type) {
        for (PromptLayer candidate : request.promptLayers()) {
            if (candidate.type() == type) {
                return candidate;
            }
        }
        return null;
    }

    private static long estimate(PromptLayer layer) {
        long total = emptyLayerEstimate(layer);
        for (PromptFragment fragment : layer.fragments()) {
            total = saturatedAdd(total, estimate(fragment));
        }
        return total;
    }

    private static long emptyLayerEstimate(PromptLayer layer) {
        return saturatedAdd(
            saturatedAdd(LAYER_OVERHEAD, codePointCount(layer.type().name())),
            codePointCount(layer.trust().name())
        );
    }

    private static long estimate(PromptFragment fragment) {
        long total = FRAGMENT_OVERHEAD;
        total = saturatedAdd(total, codePointCount(fragment.source()));
        return saturatedAdd(total, codePointCount(fragment.content()));
    }

    private static String prefixByCodePoints(String value, long maximumCodePoints) {
        if (maximumCodePoints <= 0) {
            return "";
        }
        int end = 0;
        long counted = 0;
        while (end < value.length() && counted < maximumCodePoints) {
            int codePoint = value.codePointAt(end);
            end += Character.charCount(codePoint);
            counted++;
        }
        return value.substring(0, end);
    }

    private static long codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static long saturatedAdd(long left, long right) {
        if (left < 0 || right < 0 || Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedSubtract(long left, long right) {
        if (left <= right) {
            return 0;
        }
        return left - right;
    }
}
