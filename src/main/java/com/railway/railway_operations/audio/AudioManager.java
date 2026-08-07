package com.railway.railway_operations.audio;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * Scans and manages audio packs. Reads directly from directories or zip files
 * without extracting — zips are mounted as virtual filesystems via {@link FileSystems}.
 */
public class AudioManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String AUDIO_DIR = "config/railway_operations/audio";

    private static final Map<String, AudioPack> packs = new LinkedHashMap<>();
    private static final Map<String, FileSystem> openFileSystems = new LinkedHashMap<>();

    public static void reload() {
        for (FileSystem fs : openFileSystems.values()) {
            try { fs.close(); } catch (IOException ignored) {}
        }
        openFileSystems.clear();
        packs.clear();
        AudioHashRegistry.clear();

        Path root = Path.of(AUDIO_DIR);
        if (!Files.isDirectory(root)) {
            try {
                Files.createDirectories(root);
                LOGGER.info("Created audio directory: {}", root.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("Failed to create audio directory: {}", root);
            }
            return;
        }

        // Scan directories
        try (var entries = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : entries) {
                loadPack(dir, dir.getFileName().toString());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan audio directory", e);
        }

        // Scan audio pack files (read directly via virtual filesystem)
        // Uses .zip extension to avoid Minecraft treating them as resource packs
        try (var entries = Files.newDirectoryStream(root, "*.zip")) {
            for (Path zipPath : entries) {
                String name = zipPath.getFileName().toString();
                String id = name.substring(0, name.lastIndexOf('.'));
                try {
                    FileSystem fs = FileSystems.newFileSystem(zipPath);
                    openFileSystems.put(id, fs);
                    loadPack(fs.getPath("/"), id);
                    LOGGER.info("Mounted audio pack: {}", name);
                } catch (Exception e) {
                    LOGGER.warn("Failed to mount audio pack {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan for zip files", e);
        }
        LOGGER.info("Loaded {} audio pack(s)", packs.size());
    }

    private static void loadPack(Path dir, String fallbackId) {
        try {
            AudioPack pack = AudioPack.fromDirectory(dir, fallbackId);
            if (pack != null) {
                packs.put(pack.id(), pack);
                // Register all audio files for server caching
                for (AudioPack.BroadcastDef def : pack.broadcasts().values()) {
                    registerFile(pack, def, "zh");
                    registerFile(pack, def, "en");
                }
                LOGGER.info("Loaded audio pack: {} ({} broadcasts)",
                        pack.name(), pack.broadcasts().size());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load audio pack from {} ({}): {}", fallbackId, dir, e.getMessage());
        }
    }

    private static void registerFile(AudioPack pack, AudioPack.BroadcastDef def, String lang) {
        Path file = resolveFile(pack, def, lang);
        if (file != null && Files.exists(file)) {
            AudioHashRegistry.register(file);
        }
    }

    public static Map<String, AudioPack> getPacks() {
        return packs;
    }

    public static AudioPack getPack(String id) {
        return packs.get(id);
    }

    public static Path resolveFile(AudioPack pack, AudioPack.BroadcastDef def, String lang) {
        if (def.isSimple()) {
            return pack.directory().resolve(def.fileName());
        }
        if (lang == null) lang = "zh";
        String file = "zh".equals(lang) ? def.zhTemplate() : def.enTemplate();
        if (file == null) return null;
        return pack.directory().resolve(lang).resolve(file);
    }

    public static Path resolveStationFile(AudioPack pack, String stationId, String lang) {
        if (stationId == null || lang == null) return null;
        return pack.directory().resolve(lang).resolve("stations").resolve(stationId + ".ogg");
    }

    // ---- Bundle serialization for configuration-phase sync ----

    static final int BUNDLE_MAGIC = 0x524F4150; // "ROAP"
    static final int BUNDLE_VERSION = 1;
    static final int MAX_PACK_BYTES = 32 * 1024 * 1024;   // 32 MiB per pack
    static final int MAX_TOTAL_BYTES = 128 * 1024 * 1024; // 128 MiB total

    /**
     * Creates a binary bundle of all .zip audio packs for synchronization to clients.
     * Bundle format: MAGIC(int) + VERSION(int) + COUNT(int) + [NAME(UTF) + SIZE(int) + DATA(bytes)]*N
     */
    public static Transfer createTransfer() {
        try {
            byte[] bundle = createBundle();
            if (bundle.length > MAX_TOTAL_BYTES) {
                return Transfer.error("Server audio packs exceed the 128 MiB synchronization limit");
            }
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bundle));
            return new Transfer(UUID.randomUUID(), bundle, sha256);
        } catch (Exception e) {
            LOGGER.error("Could not prepare audio synchronization", e);
            return Transfer.error("Could not prepare audio synchronization: " + e.getMessage());
        }
    }

    private static byte[] createBundle() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);

        // Scan audio directory for .zip files
        Path root = Path.of(AUDIO_DIR);
        Map<String, byte[]> packFiles = new LinkedHashMap<>();
        if (Files.isDirectory(root)) {
            try (var entries = Files.newDirectoryStream(root, "*.zip")) {
                for (Path zipPath : entries) {
                    String name = zipPath.getFileName().toString();
                    byte[] data = Files.readAllBytes(zipPath);
                    if (data.length > MAX_PACK_BYTES) {
                        throw new IOException("Audio pack " + name + " exceeds " + MAX_PACK_BYTES + " bytes");
                    }
                    packFiles.put(name, data);
                }
            }
        }

        // Write header
        out.writeInt(BUNDLE_MAGIC);
        out.writeInt(BUNDLE_VERSION);
        out.writeInt(packFiles.size());

        // Write entries
        for (var entry : packFiles.entrySet()) {
            String name = entry.getKey();
            byte[] data = entry.getValue();

            out.writeUTF(name);
            out.writeInt(data.length);
            out.write(data);

            // Check running total
            if (bos.size() > MAX_TOTAL_BYTES) {
                throw new IOException("Combined audio packs exceed " + MAX_TOTAL_BYTES + " bytes");
            }
        }

        out.close();
        return bos.toByteArray();
    }

    /** Transfer descriptor used by the configuration task. */
    public static class Transfer {
        private final UUID id;
        private final byte[] bundle;
        private final String sha256;
        private final String error;

        Transfer(UUID id, byte[] bundle, String sha256) {
            this.id = id;
            this.bundle = bundle;
            this.sha256 = sha256;
            this.error = null;
        }

        private Transfer(String error) {
            this.id = null;
            this.bundle = null;
            this.sha256 = null;
            this.error = error;
        }

        static Transfer error(String msg) { return new Transfer(msg); }

        public boolean isError() { return error != null; }
        public String error() { return error; }
        public UUID id() { return id; }
        public byte[] bundle() { return bundle; }
        public String sha256() { return sha256; }
    }
}
