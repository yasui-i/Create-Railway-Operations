package com.railway.railway_operations.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.network.ClientboundAudioSyncPacket;
import com.railway.railway_operations.network.ServerboundUploadChunkPacket;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public class RailwayCommand {

    private static final int CHUNK_SIZE = 1_000_000; // 1MB per chunk
    // Server-side reassembly buffer
    private static final Map<String, List<byte[]>> uploadBuffers = new ConcurrentHashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("railway")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("reload")
                        .executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
        );
    }

    private static int reload(CommandSourceStack src) {
        AudioManager.reload();
        syncToAll(src);
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

    // ---- Client-side: upload all .ap files from audio dir ----

    private static final String AUDIO_DIR = "config/railway_operations/audio";

    /** Upload all .ap files from the client's audio directory. */
    public static void uploadAllFromClient() {
        Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        Path dir = gameDir.resolve(AUDIO_DIR);
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (!Files.isDirectory(dir)) {
            if (player != null) player.sendSystemMessage(
                    Component.literal("Audio dir not found: " + dir.toAbsolutePath()));
            return;
        }
        try (var files = Files.list(dir)) {
            var zipFiles = files.filter(f -> f.toString().endsWith(".zip")).toList();
            if (zipFiles.isEmpty()) {
                if (player != null) player.sendSystemMessage(
                        Component.literal("No .zip files found in audio dir."));
                return;
            }
            if (player != null) player.sendSystemMessage(
                    Component.literal("Uploading " + zipFiles.size() + " audio pack(s)..."));
            for (Path p : zipFiles) {
                uploadFile(p, player);
            }
        } catch (IOException ignored) {}
    }

    private static void uploadFile(Path p, net.minecraft.world.entity.player.Player player) {
        try {
            byte[] data = Files.readAllBytes(p);
            String name = p.getFileName().toString();
            int total = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
            if (player != null) player.sendSystemMessage(
                    Component.literal("  → " + name + " (" + total + " chunks)"));
            for (int i = 0; i < total; i++) {
                int start = i * CHUNK_SIZE;
                int end = Math.min(start + CHUNK_SIZE, data.length);
                byte[] chunk = new byte[end - start];
                System.arraycopy(data, start, chunk, 0, chunk.length);
                PacketDistributor.sendToServer(
                        new ServerboundUploadChunkPacket(name, i, total, chunk));
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    // ---- Server-side: handle incoming chunk ----

    public static void handleChunk(ServerboundUploadChunkPacket p, ServerPlayer player) {
        String key = p.fileName() + "@" + player.getStringUUID();
        List<byte[]> chunks = uploadBuffers.computeIfAbsent(key, k -> new ArrayList<>());
        // Ensure capacity
        while (chunks.size() < p.totalChunks()) chunks.add(null);
        chunks.set(p.chunkIndex(), p.data());

        // Check if complete
        for (byte[] c : chunks) if (c == null) return;

        // Assemble and save
        try {
            int totalSize = 0;
            for (byte[] c : chunks) totalSize += c.length;
            byte[] full = new byte[totalSize];
            int offset = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, full, offset, c.length);
                offset += c.length;
            }
            Path dir = Path.of("config/railway_operations/audio");
            Files.createDirectories(dir);
            Files.write(dir.resolve(p.fileName()), full);
            uploadBuffers.remove(key);
            AudioManager.reload();
            var syncPkt = ClientboundAudioSyncPacket.build();
            for (var pl : player.getServer().getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(pl, syncPkt);
            }
            player.sendSystemMessage(Component.literal(
                    "Uploaded " + p.fileName() + ". Packs reloaded."));
        } catch (IOException e) {
            player.sendSystemMessage(Component.literal("Upload failed: " + e.getMessage()));
        }
    }

    private static void syncToAll(CommandSourceStack src) {
        var server = src.getServer();
        if (server == null) return;
        var syncPkt = ClientboundAudioSyncPacket.build();
        for (var player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, syncPkt);
        }
    }
}
