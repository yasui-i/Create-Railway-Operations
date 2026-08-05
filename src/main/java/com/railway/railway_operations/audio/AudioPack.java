package com.railway.railway_operations.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Represents a single audio pack loaded from a directory containing pack.json.
 */
public class AudioPack {

    public static final String PACK_JSON = "pack.json";

    private final String id;
    private final String name;
    private final String description;
    private final Path directory;
    private final Map<String, BroadcastDef> broadcasts = new LinkedHashMap<>();

    public AudioPack(String id, String name, String description, Path directory) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.directory = directory;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public Path directory() { return directory; }
    public Map<String, BroadcastDef> broadcasts() { return broadcasts; }

    public void addBroadcast(String key, BroadcastDef def) {
        broadcasts.put(key, def);
    }

    /**
     * Load an AudioPack from a directory containing pack.json.
     *
     * @param dir       the directory path (real or virtual filesystem)
     * @param packId    fallback ID when the directory name isn't meaningful (e.g. zip root "/")
     */
    public static AudioPack fromDirectory(Path dir, String packId) throws IOException {
        Path jsonPath = dir.resolve(PACK_JSON);
        if (!Files.exists(jsonPath)) return null;

        String raw = Files.readString(jsonPath);
        // Auto-fix missing trailing brace (common copy-paste error)
        raw = raw.trim();
        int open = 0, close = 0;
        for (char c : raw.toCharArray()) {
            if (c == '{') open++;
            if (c == '}') close++;
        }
        while (close < open) { raw += "\n}"; close++; }
        JsonObject root = new Gson().fromJson(raw, JsonObject.class);

        String id = packId != null ? packId : dir.getFileName().toString();
        String name = root.has("name") ? root.get("name").getAsString() : id;
        String desc = root.has("description") ? root.get("description").getAsString() : "";

        AudioPack pack = new AudioPack(id, name, desc, dir);

        if (root.has("broadcasts")) {
            JsonObject bcs = root.getAsJsonObject("broadcasts");
            for (Map.Entry<String, JsonElement> entry : bcs.entrySet()) {
                String key = entry.getKey();
                JsonObject def = entry.getValue().getAsJsonObject();
                BroadcastDef bd = BroadcastDef.fromJson(def, dir);
                if (bd != null) {
                    pack.addBroadcast(key, bd);
                }
            }
        }
        return pack;
    }

    /**
     * A single broadcast definition within a pack.
     */
    public static class BroadcastDef {
        private final String fileName;    // simple mode: direct file name
        private final boolean hasStation; // template mode: whether to append station audio
        private final String zhTemplate;  // template mode: Chinese template file
        private final String enTemplate;  // template mode: English template file

        /** Simple mode: single file without station name concatenation. */
        public BroadcastDef(String fileName) {
            this.fileName = fileName;
            this.hasStation = false;
            this.zhTemplate = null;
            this.enTemplate = null;
        }

        /** Template mode: separate template files + station splicing. */
        public BroadcastDef(String zhTemplate, String enTemplate, boolean hasStation) {
            this.fileName = null;
            this.hasStation = hasStation;
            this.zhTemplate = zhTemplate;
            this.enTemplate = enTemplate;
        }

        public boolean isSimple() { return fileName != null; }
        public boolean hasStation() { return hasStation; }
        public String fileName() { return fileName; }
        public String zhTemplate() { return zhTemplate; }
        public String enTemplate() { return enTemplate; }

        static BroadcastDef fromJson(JsonObject obj, Path packDir) {
            if (obj.has("simple")) {
                return new BroadcastDef(obj.get("simple").getAsString());
            }
            if (obj.has("template")) {
                JsonObject tpl = obj.getAsJsonObject("template");
                String zh = tpl.has("zh") ? tpl.get("zh").getAsString() : null;
                String en = tpl.has("en") ? tpl.get("en").getAsString() : null;
                boolean station = obj.has("has_station") && obj.get("has_station").getAsBoolean();
                return new BroadcastDef(zh, en, station);
            }
            return null;
        }
    }
}
