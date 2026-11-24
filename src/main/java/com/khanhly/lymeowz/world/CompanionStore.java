package com.khanhly.lymeowz.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.khanhly.lymeowz.LyMeowzMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Toàn bộ dữ liệu của LyMeowz nằm trong {@code <world>/data/lymeowz.json}, không nằm trong NBT của entity.
 * Nhờ vậy định dạng lưu không phụ thuộc vào phiên bản Minecraft (NBT/SavedData đổi API liên tục
 * từ 1.21.5 tới 26.x), và luôn tìm được cô ấy kể cả khi chunk của cô ấy chưa được load.
 */
public final class CompanionStore {
    private CompanionStore() {}

    /** Một vị trí trong một chiều không gian. */
    public static final class Spot {
        public String dim;
        public int x, y, z;

        public Spot() {}
        public Spot(String dim, int x, int y, int z) { this.dim = dim; this.x = x; this.y = y; this.z = z; }

        public boolean same(String d, int px, int py, int pz) {
            return dim != null && dim.equals(d) && x == px && y == py && z == pz;
        }
    }

    /** Dữ liệu của một LyMeowz (mỗi người chơi một cô). */
    public static final class Data {
        public String owner;
        public String companion;
        public Spot last;
        public String mode = "FOLLOW";
        @Nullable public Spot home;
        @Nullable public Spot ownerSpawn;
        public List<String> history = new ArrayList<>();
        /** Thanh no (0–20) như người chơi. */
        public float food = 20;
        /** Túi đồ (định dạng codec ItemStack của Minecraft). */
        @Nullable public com.google.gson.JsonElement inventory;
    }

    private static final class FileFormat {
        int version = 1;
        Map<String, Data> companions = new HashMap<>();
        Set<String> retired = new HashSet<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static FileFormat data = new FileFormat();
    private static final Map<UUID, Data> BY_COMPANION = new HashMap<>();
    @Nullable private static Path file;
    private static boolean dirty;

    // ------------------------------------------------------------------ lifecycle

    public static void load(MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("lymeowz.json");
        data = new FileFormat();
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    FileFormat f = GSON.fromJson(r, new TypeToken<FileFormat>() {}.getType());
                    if (f != null) data = f;
                }
            }
        } catch (Exception e) {
            LyMeowzMod.LOGGER.error("Không đọc được {}", file, e);
        }
        if (data.companions == null) data.companions = new HashMap<>();
        if (data.retired == null) data.retired = new HashSet<>();
        reindex();
        dirty = false;
    }

    public static void save() {
        if (!dirty || file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("lymeowz.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (Exception e) {
            LyMeowzMod.LOGGER.error("Không lưu được {}", file, e);
        }
    }

    public static void unload() {
        save();
        file = null;
        data = new FileFormat();
        BY_COMPANION.clear();
    }

    public static void markDirty() {
        dirty = true;
    }

    private static void reindex() {
        BY_COMPANION.clear();
        data.companions.values().removeIf(d -> d.owner == null || d.companion == null);
        for (Data d : data.companions.values()) {
            try { BY_COMPANION.put(UUID.fromString(d.companion), d); } catch (IllegalArgumentException ignored) {}
            if (d.history == null) d.history = new ArrayList<>();
            if (d.mode == null) d.mode = "FOLLOW";
        }
    }

    // ------------------------------------------------------------------ queries

    @Nullable
    public static Data byOwner(UUID owner) {
        return data.companions.get(owner.toString());
    }

    @Nullable
    public static Data byCompanion(UUID companion) {
        return BY_COMPANION.get(companion);
    }

    public static Iterable<Data> all() {
        return data.companions.values();
    }

    /** UUID của bản cũ đã bị thay thế – nếu được load lại thì phải gỡ đi. */
    public static boolean isRetired(UUID companion) {
        return data.retired.contains(companion.toString());
    }

    // ------------------------------------------------------------------ updates

    /** Gán (hoặc thay) LyMeowz cho một người chơi. Bản cũ (nếu có) bị đánh dấu "nghỉ hưu". */
    public static Data assign(UUID owner, UUID companion, Spot at) {
        Data d = byOwner(owner);
        if (d == null) {
            d = new Data();
            d.owner = owner.toString();
            data.companions.put(d.owner, d);
        } else if (d.companion != null && !d.companion.equals(companion.toString())) {
            data.retired.add(d.companion);
            BY_COMPANION.remove(UUID.fromString(d.companion));
        }
        d.companion = companion.toString();
        d.last = at;
        BY_COMPANION.put(companion, d);
        dirty = true;
        return d;
    }

    public static void remove(Data d) {
        data.companions.remove(d.owner);
        try { BY_COMPANION.remove(UUID.fromString(d.companion)); } catch (IllegalArgumentException ignored) {}
        dirty = true;
    }

    public static void forgetRetired(UUID companion) {
        if (data.retired.remove(companion.toString())) dirty = true;
    }
}
