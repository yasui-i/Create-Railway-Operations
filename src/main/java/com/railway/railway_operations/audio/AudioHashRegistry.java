package com.railway.railway_operations.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * Server-side: maps file path → SHA-256 hash and keeps audio data in memory.
 * Client-side: maps hash → local cache file path.
 */
public class AudioHashRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, String> pathToHash = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> hashToData = new ConcurrentHashMap<>();
    private static final Map<String, Path> hashToPath = new ConcurrentHashMap<>();

    public static void clear() {
        pathToHash.clear();
        hashToData.clear();
        hashToPath.clear();
    }

    /** Compute hash for a file and store it. Returns the hex hash. */
    public static String register(Path file) {
        try {
            byte[] data = Files.readAllBytes(file);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            String key = file.toAbsolutePath().toString();
            pathToHash.put(key, hash);
            hashToData.put(hash, data);
            hashToPath.put(hash, file);
            return hash;
        } catch (Exception e) {
            LOGGER.error("Failed to hash audio file: {}", file, e);
            return null;
        }
    }

    public static String getHash(Path file) {
        return pathToHash.get(file.toAbsolutePath().toString());
    }

    public static byte[] getData(String hash) {
        return hashToData.get(hash);
    }

    public static Path getPath(String hash) {
        return hashToPath.get(hash);
    }

    public static boolean hasHash(String hash) {
        return hashToData.containsKey(hash);
    }

    public static Map<String, byte[]> getAllData() {
        return hashToData;
    }
}
