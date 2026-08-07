package com.railway.railway_operations.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side cache for audio data keyed by SHA-256 hash.
 * After configuration-phase sync, audio data is available via {@link AudioHashRegistry}.
 * This cache provides a fast in-memory and on-disk layer on top.
 */
public class ClientAudioCache {

    private static final Path CACHE_DIR = Path.of("railway_operations/audio_cache");
    private static final Map<String, byte[]> memoryCache = new HashMap<>();

    private static final java.util.Set<String> knownHashes = new java.util.HashSet<>();

    static {
        try { Files.createDirectories(CACHE_DIR); } catch (IOException ignored) {}
    }

    /** Register a hash→filePath mapping synced from server. */
    public static void registerHash(String hash, String filePath) {
        knownHashes.add(hash);
    }

    public static boolean isKnown(String hash) {
        return knownHashes.contains(hash) || AudioHashRegistry.hasHash(hash);
    }

    public static byte[] get(String hash) {
        // Memory cache
        byte[] data = memoryCache.get(hash);
        if (data != null) return data;

        // Disk cache
        Path file = CACHE_DIR.resolve(hash + ".ogg");
        if (Files.exists(file)) {
            try {
                data = Files.readAllBytes(file);
                memoryCache.put(hash, data);
                return data;
            } catch (IOException ignored) {}
        }

        // Fall back to AudioHashRegistry (populated by configuration-phase sync)
        data = AudioHashRegistry.getData(hash);
        if (data != null) {
            memoryCache.put(hash, data);
            return data;
        }

        return null;
    }

    public static void put(String hash, byte[] data) {
        memoryCache.put(hash, data);
        try {
            Files.write(CACHE_DIR.resolve(hash + ".ogg"), data);
        } catch (IOException ignored) {}
    }

    /**
     * Request missing audio from server. With configuration-phase sync this is
     * rarely needed, but kept for edge cases (e.g. new packs uploaded during play).
     */
    public static void requestFromServer(String hash) {
        PacketDistributor.sendToServer(
                new com.railway.railway_operations.network.ServerboundAudioRequestPacket(hash));
    }
}
