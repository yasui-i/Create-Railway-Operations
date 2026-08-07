package com.railway.railway_operations.network;

import com.railway.railway_operations.audio.AudioManager;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Client-side handler for configuration-phase audio bundle synchronization.
 * Mirrors the pattern used by custom-train-door's TarindoorClientSync.
 */
public final class AudioClientSync {

    private static final Logger LOGGER = LoggerFactory.getLogger("railway_operations/AudioSync");
    // Allow Unicode characters (e.g. Chinese filenames), but reject path separators and control chars
    private static final Pattern SAFE_NAME = Pattern.compile("[^\\\\/:*?\"<>|\\x00-\\x1f]{1,128}");

    // ---- bundle binary format constants ----
    static final int BUNDLE_MAGIC = 0x524F4150; // "ROAP"
    static final int BUNDLE_VERSION = 1;
    static final int MAX_PACK_BYTES = 32 * 1024 * 1024;   // 32 MiB per pack
    static final int MAX_TOTAL_BYTES = 128 * 1024 * 1024; // 128 MiB total

    private static TransferState transfer;

    private AudioClientSync() {}

    // ---- packet handlers ----

    public static synchronized void handleStart(AudioSyncStartPayload p, IPayloadContext ctx) {
        // Validate header
        if (p.totalBytes() < 0 || p.totalBytes() > MAX_TOTAL_BYTES
                || p.chunkCount() < 0 || p.chunkCount() > 146  // ceil(128MiB / 900KiB) ≈ 146
                || !Pattern.compile("[0-9a-f]{64}").matcher(p.sha256()).matches()) {
            ctx.disconnect(Component.literal("Invalid audio synchronization header"));
            return;
        }

        // Verify chunk count matches expected
        int expectedChunks = (p.totalBytes() + AudioSyncChunkPayload.MAX_CHUNK_BYTES - 1)
                / AudioSyncChunkPayload.MAX_CHUNK_BYTES;
        if (expectedChunks != p.chunkCount()) {
            ctx.disconnect(Component.literal("Invalid audio chunk count"));
            return;
        }

        transfer = new TransferState(p.syncId(), p.totalBytes(), p.chunkCount(), p.sha256());

        // If bundle is empty (0 bytes), finish immediately
        if (p.chunkCount() == 0) {
            finishTransfer(ctx);
        }
    }

    public static synchronized void handleChunk(AudioSyncChunkPayload p, IPayloadContext ctx) {
        TransferState t = transfer;
        if (t == null
                || !t.id.equals(p.syncId())
                || p.index() < 0
                || p.index() >= t.chunks.length
                || t.chunks[p.index()] != null) {
            ctx.disconnect(Component.literal("Invalid or duplicate audio data chunk"));
            return;
        }

        t.chunks[p.index()] = p.data();
        t.receivedBytes += p.data().length;
        t.receivedChunks++;

        // Oversize check
        if (t.receivedBytes > t.totalBytes) {
            ctx.disconnect(Component.literal("Audio synchronization exceeded its declared size"));
            transfer = null;
            return;
        }

        // All chunks received
        if (t.receivedChunks == t.chunks.length) {
            finishTransfer(ctx);
        }
    }

    private static void finishTransfer(IPayloadContext ctx) {
        TransferState t = transfer;
        transfer = null;
        if (t == null) return;

        try {
            byte[] bundle = joinAndVerify(t);
            installBundle(bundle);
            LOGGER.info("Installed synchronized audio bundle {}", t.id);

            // Reload audio packs — no Minecraft resource reload needed
            // (playback uses OpenAL directly, not the Minecraft sound engine)
            ctx.enqueueWork(() -> {
                AudioManager.reload();
                LOGGER.info("Audio packs reloaded after sync; acknowledging bundle {}", t.id);
                if (ctx.connection().isConnected()) {
                    ctx.reply(new AudioSyncAckPayload(t.id));
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to install synchronized audio bundle (client will use lazy-load fallback)", e);
            // Non-fatal: acknowledge anyway so the player can connect.
            // Missing audio will be fetched on-demand via ServerboundAudioRequestPacket.
            ctx.enqueueWork(() -> {
                AudioManager.reload();
                if (ctx.connection().isConnected()) {
                    ctx.reply(new AudioSyncAckPayload(t.id));
                }
            });
        }
    }

    private static byte[] joinAndVerify(TransferState t) throws IOException, NoSuchAlgorithmException {
        byte[] result = new byte[t.totalBytes];
        int offset = 0;
        for (byte[] chunk : t.chunks) {
            if (chunk == null || offset + chunk.length > result.length) {
                throw new IOException("Missing or oversized chunk");
            }
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        if (offset != result.length) {
            throw new IOException("Bundle length mismatch");
        }
        // SHA-256 verification
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(result));
        if (!MessageDigest.isEqual(
                hash.getBytes(StandardCharsets.US_ASCII),
                t.digest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("SHA-256 mismatch");
        }
        return result;
    }

    private static void installBundle(byte[] bundle) throws IOException {
        Path audioDir = FMLPaths.GAMEDIR.get()
                .resolve("config").resolve("railway_operations").resolve("audio")
                .toAbsolutePath().normalize();

        // Validate path is under game directory
        Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        if (!audioDir.startsWith(gameDir)) {
            throw new IOException("Unsafe audio cache path");
        }

        Files.createDirectories(audioDir);

        // Clear existing synced packs (files that came from a previous sync)
        // We don't delete user-uploaded files — only overwrite by name
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bundle));
        try {
            int magic = in.readInt();
            if (magic != BUNDLE_MAGIC) throw new IOException("Unknown bundle format");
            int version = in.readInt();
            if (version != BUNDLE_VERSION) throw new IOException("Unsupported bundle version");

            int count = in.readInt();
            if (count < 0 || count > 256) throw new IOException("Invalid pack count");

            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                int size = in.readInt();

                if (!SAFE_NAME.matcher(name).matches()
                        || size < 0 || size > MAX_PACK_BYTES) {
                    throw new IOException("Invalid pack entry: " + name);
                }

                byte[] data = in.readNBytes(size);
                if (data.length != size) throw new IOException("Truncated pack: " + name);

                Path target = audioDir.resolve(name);
                Path tmp = audioDir.resolve(name + ".tmp");
                Files.write(tmp, data);
                // Write synced marker so we can identify server-synced files
                try {
                    Files.move(tmp, target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            if (in.available() > 0) throw new IOException("Trailing bundle data");
        } finally {
            in.close();
        }
    }

    /** Reset state on disconnect. */
    public static void onLoggingOut() {
        transfer = null;
    }

    // ---- inner types ----

    private static class TransferState {
        final UUID id;
        final int totalBytes;
        final byte[][] chunks;
        final String digest;
        int receivedBytes;
        int receivedChunks;

        TransferState(UUID id, int totalBytes, int chunkCount, String digest) {
            this.id = id;
            this.totalBytes = totalBytes;
            this.chunks = new byte[chunkCount][];
            this.digest = digest;
        }
    }
}
