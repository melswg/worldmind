package io.github.melswg.worldmind.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.melswg.worldmind.core.administration.ConfigurationValidationReport;
import io.github.melswg.worldmind.core.administration.ReloadResult;
import io.github.melswg.worldmind.core.administration.RuntimeStatusSnapshot;
import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** Server-only Brigadier adapter. It only renders already safe, bounded administration DTOs. */
final class WorldmindCommandRegistration {
    private static final int OPERATOR_LEVEL = 4;

    private WorldmindCommandRegistration() { }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher, WorldmindFabricServerLifecycle lifecycle) {
        dispatcher.register(CommandManager.literal("worldmind")
            .requires(source -> source.hasPermissionLevel(OPERATOR_LEVEL))
            .executes(context -> help(context.getSource()))
            .then(CommandManager.literal("status").executes(context -> status(context, lifecycle)))
            .then(CommandManager.literal("validate").executes(context -> validate(context, lifecycle)))
            .then(CommandManager.literal("reload").executes(context -> reload(context, lifecycle)))
        );
    }

    private static int help(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Worldmind: status, validate, reload."), false);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context, WorldmindFabricServerLifecycle lifecycle) {
        RuntimeStatusSnapshot snapshot = lifecycle.status();
        context.getSource().sendFeedback(() -> Text.literal(WorldmindCommandText.status(snapshot)), false);
        return 1;
    }

    private static int validate(CommandContext<ServerCommandSource> context, WorldmindFabricServerLifecycle lifecycle) {
        ServerCommandSource source = context.getSource();
        long generation = lifecycle.currentGeneration();
        lifecycle.validate().whenComplete((report, failure) -> lifecycle.deliverForCurrentGeneration(
            source.getServer(), generation,
            () -> sendValidation(source, report, failure)
        ));
        source.sendFeedback(() -> Text.literal("Worldmind validation accepted."), false);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> context, WorldmindFabricServerLifecycle lifecycle) {
        ServerCommandSource source = context.getSource();
        CompletableFuture<ReloadResult> operation = lifecycle.reload().toCompletableFuture();
        if (operation.isDone()) {
            ReloadResult immediate = operation.getNow(null);
            sendReload(source, immediate, null);
            return immediate != null && immediate.accepted() ? 1 : 0;
        }
        operation.whenComplete((result, failure) -> lifecycle.deliverAdministrationResult(source.getServer(),
            () -> sendReload(source, result, failure)));
        source.sendFeedback(() -> Text.literal("Worldmind reload accepted."), false);
        return 1;
    }

    private static void sendValidation(ServerCommandSource source, ConfigurationValidationReport report, Throwable failure) {
        if (failure != null || report == null) {
            source.sendError(Text.literal("Worldmind validation failed: IO_FAILURE."));
            return;
        }
        if (report.valid()) {
            source.sendFeedback(() -> Text.literal("Worldmind validation: SUCCESS."), false);
            return;
        }
        source.sendError(Text.literal("Worldmind validation: " + report.code() + "."));
        for (ConfigurationDiagnostic diagnostic : boundedDiagnostics(report.diagnostics())) {
            source.sendError(Text.literal(WorldmindCommandText.diagnostic(diagnostic)));
        }
    }

    private static void sendReload(ServerCommandSource source, ReloadResult result, Throwable failure) {
        if (failure != null || result == null) {
            source.sendError(Text.literal("Worldmind reload: IO_FAILURE."));
            return;
        }
        if (result.accepted()) {
            source.sendFeedback(() -> Text.literal("Worldmind reload: " + result.code() + "."), false);
            return;
        }
        source.sendError(Text.literal("Worldmind reload: " + result.code() + "."));
        for (ConfigurationDiagnostic diagnostic : boundedDiagnostics(result.diagnostics())) {
            source.sendError(Text.literal(WorldmindCommandText.diagnostic(diagnostic)));
        }
    }

    private static List<ConfigurationDiagnostic> boundedDiagnostics(List<ConfigurationDiagnostic> diagnostics) {
        return diagnostics.stream().limit(16).toList();
    }
}
