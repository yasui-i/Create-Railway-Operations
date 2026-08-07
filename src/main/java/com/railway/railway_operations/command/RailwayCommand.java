package com.railway.railway_operations.command;

import com.mojang.brigadier.CommandDispatcher;
import com.railway.railway_operations.CreateRailwayOperations;
import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.network.AudioSyncChunkPayload;
import com.railway.railway_operations.network.AudioSyncStartPayload;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public class RailwayCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("railway")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("reload")
                        .executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("sync")
                        .executes(ctx -> sync(ctx.getSource())))
        );
    }

    private static int reload(CommandSourceStack src) {
        AudioManager.reload();
        src.sendSuccess(() -> Component.literal(
                "Packs reloaded. " + AudioManager.getPacks().size() + " pack(s)."), true);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        int count = AudioManager.getPacks().size();
        var sb = new StringBuilder("=== Audio Packs ===\n");
        for (var p : AudioManager.getPacks().values()) {
            sb.append("  ").append(p.name()).append(" (").append(p.id()).append("): ")
                    .append(p.broadcasts().size()).append(" broadcasts\n");
        }
        sb.append("Total: ").append(count).append(" pack(s)");
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return count;
    }

    /** Bundle all audio packs and sync to every connected player. */
    private static int sync(CommandSourceStack src) {
        var server = src.getServer();
        if (server == null) return 0;

        AudioManager.reload();
        AudioManager.Transfer transfer = AudioManager.createTransfer();
        if (transfer.isError()) {
            src.sendFailure(Component.literal("Sync failed: " + transfer.error()));
            return 0;
        }

        byte[] bundle = transfer.bundle();
        int totalBytes = bundle.length;
        int chunkCount = (totalBytes + AudioSyncChunkPayload.MAX_CHUNK_BYTES - 1)
                / AudioSyncChunkPayload.MAX_CHUNK_BYTES;

        AudioSyncStartPayload startPacket = new AudioSyncStartPayload(
                transfer.id(), totalBytes, chunkCount, transfer.sha256());

        // Pre-slice chunks
        byte[][] chunks = new byte[chunkCount][];
        for (int i = 0; i < chunkCount; i++) {
            int start = i * AudioSyncChunkPayload.MAX_CHUNK_BYTES;
            int end = Math.min(start + AudioSyncChunkPayload.MAX_CHUNK_BYTES, totalBytes);
            chunks[i] = new byte[end - start];
            System.arraycopy(bundle, start, chunks[i], 0, chunks[i].length);
        }

        // Send to all connected players
        int playerCount = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, startPacket);
            for (int i = 0; i < chunkCount; i++) {
                PacketDistributor.sendToPlayer(player,
                        new AudioSyncChunkPayload(transfer.id(), i, chunks[i]));
            }
            playerCount++;
        }

        int finalPlayerCount = playerCount;
        src.sendSuccess(() -> Component.literal(
                "Synced " + AudioManager.getPacks().size() + " pack(s) ("
                        + (totalBytes / 1024) + " KiB) to "
                        + finalPlayerCount + " player(s)."), true);
        CreateRailwayOperations.LOGGER.info(
                "Audio sync triggered by {}: {} pack(s), {} KiB, {} player(s)",
                src.getTextName(), AudioManager.getPacks().size(),
                totalBytes / 1024, playerCount);
        return playerCount;
    }
}
