package io.github.melswg.worldmind.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.melswg.worldmind.core.administration.ConfigurationValidationReport;
import io.github.melswg.worldmind.core.administration.ReloadResult;
import io.github.melswg.worldmind.core.administration.RuntimeStatusSnapshot;
import io.github.melswg.worldmind.core.administration.MemoryInspectionCursor;
import io.github.melswg.worldmind.core.administration.MemoryInspectionQuery;
import io.github.melswg.worldmind.core.administration.MemoryInspectionResult;
import io.github.melswg.worldmind.core.administration.MemoryInspectionScope;
import io.github.melswg.worldmind.core.administration.MemoryRecordType;
import io.github.melswg.worldmind.core.administration.MemoryExportResult;
import io.github.melswg.worldmind.core.administration.MemoryDeletionPreview;
import io.github.melswg.worldmind.core.administration.MemoryDeletionRequest;
import io.github.melswg.worldmind.core.administration.MemoryDeletionResult;
import io.github.melswg.worldmind.core.administration.ConfirmationToken;
import io.github.melswg.worldmind.core.configuration.ConfigurationDiagnostic;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.command.argument.UuidArgumentType;
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
            .then(memory(lifecycle))
        );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> memory(
        WorldmindFabricServerLifecycle lifecycle
    ) {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> inspectWorld = CommandManager.literal("world");
        addWorldRecordTypes(inspectWorld, lifecycle, false);
        com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, java.util.UUID> inspectPlayerUuid = CommandManager
            .argument("player-uuid", UuidArgumentType.uuid());
        addPlayerRecordTypes(inspectPlayerUuid, lifecycle, false);
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> detailWorld = CommandManager.literal("world");
        addWorldRecordTypes(detailWorld, lifecycle, true);
        com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, java.util.UUID> detailPlayerUuid = CommandManager
            .argument("player-uuid", UuidArgumentType.uuid());
        addPlayerRecordTypes(detailPlayerUuid, lifecycle, true);
        com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, java.util.UUID> exportPlayerUuid = CommandManager
            .argument("player-uuid", UuidArgumentType.uuid())
            .executes(context -> export(context, lifecycle,
                MemoryInspectionScope.player(UuidArgumentType.getUuid(context, "player-uuid"))));
        return CommandManager.literal("memory")
            .executes(context -> {
                context.getSource().sendFeedback(() -> Text.literal("Worldmind memory: inspect, detail, export."), false);
                return 1;
            })
            .then(CommandManager.literal("inspect")
                .then(inspectWorld)
                .then(CommandManager.literal("player").then(inspectPlayerUuid)))
            .then(CommandManager.literal("detail")
                .then(detailWorld)
                .then(CommandManager.literal("player").then(detailPlayerUuid)))
            .then(CommandManager.literal("export")
                .then(CommandManager.literal("world").executes(context -> export(context, lifecycle, MemoryInspectionScope.world())))
                .then(CommandManager.literal("player").then(exportPlayerUuid)))
            .then(deletion(lifecycle))
            .then(reset(lifecycle));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> deletion(WorldmindFabricServerLifecycle lifecycle) {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> recordWorld = CommandManager.literal("world");
        addDeletionRecordTypes(recordWorld, lifecycle, false);
        com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, java.util.UUID> recordPlayer = CommandManager.argument("player-uuid", UuidArgumentType.uuid());
        addDeletionRecordTypes(recordPlayer, lifecycle, true);
        return CommandManager.literal("delete")
            .then(CommandManager.literal("record").then(recordWorld).then(CommandManager.literal("player").then(recordPlayer)))
            .then(CommandManager.literal("player").then(CommandManager.argument("player-uuid", UuidArgumentType.uuid())
                .executes(context -> prepareDeletion(context, lifecycle, MemoryDeletionRequest.player(UuidArgumentType.getUuid(context, "player-uuid"))))))
            .then(CommandManager.literal("confirm").then(CommandManager.argument("token", StringArgumentType.word())
                .executes(context -> confirmDeletion(context, lifecycle, new ConfirmationToken(StringArgumentType.getString(context, "token")), false))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> reset(WorldmindFabricServerLifecycle lifecycle) {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> world = CommandManager.literal("world")
            .executes(context -> prepareReset(context, lifecycle));
        world.then(CommandManager.literal("confirm").then(CommandManager.argument("token", StringArgumentType.word())
            .executes(context -> confirmDeletion(context, lifecycle, new ConfirmationToken(StringArgumentType.getString(context, "token")), true))));
        return CommandManager.literal("reset").then(world);
    }

    private static void addDeletionRecordTypes(
        com.mojang.brigadier.builder.ArgumentBuilder<ServerCommandSource, ?> parent, WorldmindFabricServerLifecycle lifecycle, boolean player
    ) {
        for (String value : List.of("observation", "batch", "outcome", "reply", "fact", "relationship", "event", "situation", "summary")) {
            MemoryRecordType type = MemoryRecordType.commandValue(value);
            parent.then(CommandManager.literal(value).then(CommandManager.argument("record-id", StringArgumentType.word()).executes(context -> {
                MemoryInspectionScope scope = player ? MemoryInspectionScope.player(UuidArgumentType.getUuid(context, "player-uuid")) : MemoryInspectionScope.world();
                return prepareDeletion(context, lifecycle, MemoryDeletionRequest.record(scope, type, StringArgumentType.getString(context, "record-id")));
            })));
        }
    }

    private static int prepareDeletion(CommandContext<ServerCommandSource> context, WorldmindFabricServerLifecycle lifecycle, MemoryDeletionRequest request) {
        ServerCommandSource source = context.getSource();
        lifecycle.prepareDeletion(request).whenComplete((result, failure) -> lifecycle.deliverAdministrationResult(source.getServer(),
            () -> sendDeletionPreview(source, result, failure)));
        source.sendFeedback(() -> Text.literal("Worldmind memory deletion prepare accepted."), false);
        return 1;
    }

    private static int prepareReset(CommandContext<ServerCommandSource> context, WorldmindFabricServerLifecycle lifecycle) {
        ServerCommandSource source = context.getSource();
        lifecycle.prepareWorldReset().whenComplete((result, failure) -> lifecycle.deliverAdministrationResult(source.getServer(),
            () -> sendDeletionPreview(source, result, failure)));
        source.sendFeedback(() -> Text.literal("Worldmind world reset prepare accepted."), false);
        return 1;
    }

    private static int confirmDeletion(CommandContext<ServerCommandSource> context, WorldmindFabricServerLifecycle lifecycle, ConfirmationToken token, boolean reset) {
        ServerCommandSource source = context.getSource();
        (reset ? lifecycle.confirmWorldReset(token) : lifecycle.confirmDeletion(token)).whenComplete((result, failure) ->
            lifecycle.deliverAdministrationResult(source.getServer(), () -> sendDeletionResult(source, result, failure)));
        source.sendFeedback(() -> Text.literal("Worldmind memory deletion confirmation accepted."), false);
        return 1;
    }

    private static void sendDeletionPreview(ServerCommandSource source, MemoryDeletionPreview result, Throwable failure) {
        if (failure != null || result == null) { source.sendError(Text.literal("Worldmind memory deletion: STORAGE_UNAVAILABLE.")); return; }
        if (result.code() != io.github.melswg.worldmind.core.administration.AdministrationResultCode.CONFIRMATION_REQUIRED) {
            source.sendError(Text.literal("Worldmind memory deletion: " + result.code() + ".")); return;
        }
        source.sendFeedback(() -> Text.literal("Worldmind memory deletion: CONFIRMATION_REQUIRED affected=" + result.affectedRecords()
            + " token=" + result.token().orElseThrow().value() + " ttl=60s."), false);
    }

    private static void sendDeletionResult(ServerCommandSource source, MemoryDeletionResult result, Throwable failure) {
        if (failure != null || result == null) { source.sendError(Text.literal("Worldmind memory deletion: STORAGE_UNAVAILABLE.")); return; }
        if (result.code() != io.github.melswg.worldmind.core.administration.AdministrationResultCode.SUCCESS) {
            source.sendError(Text.literal("Worldmind memory deletion: " + result.code() + ".")); return;
        }
        source.sendFeedback(() -> Text.literal("Worldmind memory deletion: SUCCESS affected=" + result.affectedRecords() + " erasure=LOGICAL_ONLY."), false);
    }

    private static void addWorldRecordTypes(
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> parent,
        WorldmindFabricServerLifecycle lifecycle, boolean detail
    ) {
        for (String value : List.of("observation", "batch", "outcome", "reply", "fact", "relationship", "event", "situation", "summary")) {
            com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> branch = CommandManager.literal(value);
            addRecordType(branch, lifecycle, MemoryInspectionScope.world(), MemoryRecordType.commandValue(value), detail);
            parent.then(branch);
        }
    }

    private static void addPlayerRecordTypes(
        com.mojang.brigadier.builder.ArgumentBuilder<ServerCommandSource, ?> parent,
        WorldmindFabricServerLifecycle lifecycle, boolean detail
    ) {
        // UUID resolution happens at command execution; display names never enter the storage predicate.
        for (String value : List.of("observation", "batch", "outcome", "reply", "fact", "relationship", "event", "situation", "summary")) {
            com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> branch = CommandManager.literal(value);
            addRecordTypeForPlayer(branch, lifecycle, MemoryRecordType.commandValue(value), detail);
            parent.then(branch);
        }
    }

    private static void addRecordType(
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> branch,
        WorldmindFabricServerLifecycle lifecycle, MemoryInspectionScope scope, MemoryRecordType type, boolean detail
    ) {
        if (detail) {
            branch.then(CommandManager.argument("record-id", StringArgumentType.word())
                .executes(context -> detail(context, lifecycle, scope, type, StringArgumentType.getString(context, "record-id"))));
        } else {
            branch.executes(context -> inspect(context, lifecycle, scope, type, null))
                .then(CommandManager.literal("after").then(CommandManager.argument("cursor", StringArgumentType.word())
                    .executes(context -> inspect(context, lifecycle, scope, type, StringArgumentType.getString(context, "cursor")))));
        }
    }

    private static void addRecordTypeForPlayer(
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> branch,
        WorldmindFabricServerLifecycle lifecycle, MemoryRecordType type, boolean detail
    ) {
        if (detail) {
            branch.then(CommandManager.argument("record-id", StringArgumentType.word()).executes(context -> detail(context, lifecycle,
                MemoryInspectionScope.player(UuidArgumentType.getUuid(context, "player-uuid")), type,
                StringArgumentType.getString(context, "record-id"))));
        } else {
            branch.executes(context -> inspect(context, lifecycle,
                MemoryInspectionScope.player(UuidArgumentType.getUuid(context, "player-uuid")), type, null))
                .then(CommandManager.literal("after").then(CommandManager.argument("cursor", StringArgumentType.word())
                    .executes(context -> inspect(context, lifecycle,
                        MemoryInspectionScope.player(UuidArgumentType.getUuid(context, "player-uuid")), type,
                        StringArgumentType.getString(context, "cursor")))));
        }
    }

    private static int inspect(
        CommandContext<ServerCommandSource> context,
        WorldmindFabricServerLifecycle lifecycle,
        MemoryInspectionScope scope,
        MemoryRecordType type,
        String encodedCursor
    ) {
        MemoryInspectionQuery query;
        try {
            query = new MemoryInspectionQuery(scope, type, encodedCursor == null ? java.util.Optional.empty()
                : java.util.Optional.of(MemoryInspectionCursor.decode(encodedCursor, type, scope)));
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendError(Text.literal("Worldmind memory inspection: INVALID_CURSOR."));
            return 0;
        }
        ServerCommandSource source = context.getSource();
        long generation = lifecycle.currentGeneration();
        lifecycle.inspect(query).whenComplete((result, failure) -> lifecycle.deliverForCurrentGeneration(source.getServer(), generation,
            () -> sendInspection(source, result, failure, query)));
        source.sendFeedback(() -> Text.literal("Worldmind memory inspection accepted."), false);
        return 1;
    }

    private static int detail(
        CommandContext<ServerCommandSource> context,
        WorldmindFabricServerLifecycle lifecycle,
        MemoryInspectionScope scope,
        MemoryRecordType type,
        String stableIdentity
    ) {
        if (stableIdentity == null || stableIdentity.length() > 160 || !stableIdentity.startsWith(type.commandValue() + ":")) {
            context.getSource().sendError(Text.literal("Worldmind memory detail: INVALID_RECORD_ID."));
            return 0;
        }
        ServerCommandSource source = context.getSource();
        long generation = lifecycle.currentGeneration();
        lifecycle.detail(scope, type, stableIdentity).whenComplete((result, failure) -> lifecycle.deliverForCurrentGeneration(
            source.getServer(), generation, () -> sendInspection(source, result, failure, null)
        ));
        source.sendFeedback(() -> Text.literal("Worldmind memory detail accepted."), false);
        return 1;
    }

    private static void sendInspection(
        ServerCommandSource source, MemoryInspectionResult result, Throwable failure, MemoryInspectionQuery query
    ) {
        if (failure != null || result == null) {
            source.sendError(Text.literal("Worldmind memory: STORAGE_UNAVAILABLE."));
            return;
        }
        if (result.code() != io.github.melswg.worldmind.core.administration.AdministrationResultCode.SUCCESS) {
            source.sendError(Text.literal("Worldmind memory: " + result.code() + "."));
            return;
        }
        if (result.record().isPresent()) {
            source.sendFeedback(() -> Text.literal(WorldmindCommandText.memoryRecord(result.record().orElseThrow())), false);
            return;
        }
        result.page().orElseThrow().records().forEach(record ->
            source.sendFeedback(() -> Text.literal(WorldmindCommandText.memoryRecord(record)), false));
        result.page().orElseThrow().next().ifPresent(cursor -> source.sendFeedback(() -> Text.literal(
            "Next: /worldmind memory inspect " + scopeCommand(query.scope()) + " " + query.recordType().commandValue()
                + " after " + cursor.encode()), false));
    }

    private static int export(
        CommandContext<ServerCommandSource> context, WorldmindFabricServerLifecycle lifecycle, MemoryInspectionScope scope
    ) {
        ServerCommandSource source = context.getSource();
        long generation = lifecycle.currentGeneration();
        CompletableFuture<MemoryExportResult> operation = lifecycle.export(scope).toCompletableFuture();
        if (operation.isDone()) {
            MemoryExportResult immediate = operation.getNow(null);
            sendExport(source, immediate, null);
            return immediate != null && immediate.code() == io.github.melswg.worldmind.core.administration.AdministrationResultCode.SUCCESS ? 1 : 0;
        }
        operation.whenComplete((result, failure) -> lifecycle.deliverForCurrentGeneration(source.getServer(), generation,
            () -> sendExport(source, result, failure)));
        source.sendFeedback(() -> Text.literal("Worldmind memory export accepted."), false);
        return 1;
    }

    private static void sendExport(ServerCommandSource source, MemoryExportResult result, Throwable failure) {
        if (failure != null || result == null) {
            source.sendError(Text.literal("Worldmind memory export: IO_FAILURE."));
            return;
        }
        if (result.code() != io.github.melswg.worldmind.core.administration.AdministrationResultCode.SUCCESS) {
            source.sendError(Text.literal("Worldmind memory export: " + result.code() + "."));
            return;
        }
        source.sendFeedback(() -> Text.literal("Worldmind memory export completed: " + result.relativeArtifact().orElseThrow()), false);
    }

    private static String scopeCommand(MemoryInspectionScope scope) {
        return scope.kind() == MemoryInspectionScope.Kind.WORLD ? "world"
            : "player " + scope.playerId().orElseThrow();
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
