package com.railway.railway_operations.audio;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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
}
