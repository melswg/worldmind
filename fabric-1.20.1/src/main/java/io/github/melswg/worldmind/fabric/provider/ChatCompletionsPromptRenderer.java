package io.github.melswg.worldmind.fabric.provider;

import io.github.melswg.worldmind.core.conversation.PromptFragment;
import io.github.melswg.worldmind.core.conversation.PromptLayer;
import io.github.melswg.worldmind.core.conversation.PromptTrust;
import io.github.melswg.worldmind.core.conversation.ProviderRequest;
import java.util.Objects;

/** Renders core layers into one structural Chat Completions message safely. */
final class ChatCompletionsPromptRenderer {
    String renderLayers(ProviderRequest providerRequest, PromptTrust trust) {
        Objects.requireNonNull(providerRequest, "providerRequest");
        Objects.requireNonNull(trust, "trust");
        StringBuilder rendered = new StringBuilder();
        providerRequest.promptLayers().stream()
            .filter(layer -> layer.trust() == trust)
            .forEach(layer -> appendLayer(rendered, layer));
        return rendered.toString();
    }

    private void appendLayer(StringBuilder rendered, PromptLayer layer) {
        rendered.append("<worldmind-layer type=\"").append(layer.type())
            .append("\" trust=\"").append(layer.trust()).append("\">\n");
        if (layer.fragments().isEmpty()) {
            rendered.append("<worldmind-empty/>\n");
        } else {
            for (PromptFragment fragment : layer.fragments()) {
                rendered.append("<worldmind-fragment source=\"");
                appendEscaped(rendered, fragment.source());
                rendered.append("\">\n");
                appendEscaped(rendered, fragment.content());
                rendered.append("\n</worldmind-fragment>\n");
            }
        }
        rendered.append("</worldmind-layer>\n");
    }

    /** Prevents source or content from manufacturing a sibling prompt structure. */
    private void appendEscaped(StringBuilder rendered, String value) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case '&' -> rendered.append("&amp;");
                case '<' -> rendered.append("&lt;");
                case '>' -> rendered.append("&gt;");
                case '"' -> rendered.append("&quot;");
                case '\'' -> rendered.append("&apos;");
                default -> {
                    if (Character.getType(codePoint) == Character.CONTROL && codePoint != '\n' && codePoint != '\t') {
                        rendered.append("&#x").append(Integer.toHexString(codePoint)).append(';');
                    } else if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                        rendered.append("&#xfffd;");
                    } else {
                        rendered.appendCodePoint(codePoint);
                    }
                }
            }
        }
    }
}
