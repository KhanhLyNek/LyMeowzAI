package com.khanhly.lymeowz.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.mind.MindData;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dữ liệu của mọi LyMeowz trong thế giới, lưu ở {@code <world>/data/lymeowz.json} (không nằm trong NBT).
 * Mỗi cô một bản ghi theo UUID của chính cô ấy – cô ấy không "thuộc về" ai; mỗi người chơi chỉ có một độ thân thiết.
 */
public final class CompanionStore {
    private CompanionStore() {}

    public static final class Spot {
        public String dim;
        public int x, y, z;

        public Spot() {}
        public Spot(String dim, int x, int y, int z) { this.dim = dim; this.x = x; this.y = y; this.z = z; }

        public boolean same(String d, int px, int py, int pz) {
            return dim != null && dim.equals(d) && x == px && y == py && z == pz;
        }
    }

    /** Một yêu cầu của người chơi mà cô ấy đã nhận: đi theo / đợi ở đây / về nhà. */
    public static final class Request {
        public String type;          // FOLLOW, WAIT, HOME
        public String player;        // UUID người nhờ
        public String playerName;
        public long until;           // game time hết hạn
        @Nullable public Spot at;    // chỗ đợi
    }

    public static final class Data {
        public String companion;
        /** Tên do người chơi đặt bằng bảng tên (null = chưa có tên). */
        @Nullable public String name;
        public Spot last;
        @Nullable public Spot home;
        /** Vị trí giường của cô ấy (trong nhà). */
        @Nullable public Spot bed;
        @Nullable public Request request;
        public List<String> history = new ArrayList<>();
        public float food = 20;
        @Nullable public JsonElement inventory;
        @Nullable public MindData mind;
        /** Những thứ cô ấy đã xây (nhà, ruộng, chuồng, rương...) – để quay lại dùng. */
        public Map<String, Spot> structures = new HashMap<>();
        /** Nhà theo bản vẽ đang xây: tên bản vẽ + góc đặt (null = chưa chọn chỗ). */
        @Nullable public String blueprint;
        @Nullable public Spot blueprintAt;
        /** Bản vẽ của nhà đang ở. */
        @Nullable public String homeBlueprint;
        /** Các bản vẽ đã xây xong. */
        public List<String> builtHouses = new ArrayList<>();
        /** Những vùng đã đi tìm đất mà không ưng (để lần sau đi hướng khác). */
        public List<Spot> rejectedSites = new ArrayList<>();
        /** Các khối của hộp trú đêm đang dựng (để sáng ra / khởi động lại vẫn nhớ mà dỡ). */
        public List<Spot> campBlocks = new ArrayList<>();
        /** Vùng đất quen khi chưa có nhà. */
        @Nullable public Spot territory;
        /** Bàn chế tạo cô ấy tự đặt ở chỗ đang làm việc (giữ lại dùng tiếp, đi xa mới thu về). */
        @Nullable public Spot workTable;
        /** Giai đoạn trong lộ trình sinh tồn, và từ lúc nào (game time). */
        public int stage = -1;
        public long stageSince;
        /** Lúc lần đầu tới bước dựng nhà (không bị đặt lại khi lỡ gãy kiếm, gãy cuốc). */
        public long shelterSince;
        /** Các giai đoạn đã tạm bỏ qua vì kẹt quá lâu (vẫn làm tiếp khi có điều kiện). */
        public Set<String> skipped = new HashSet<>();
    }

    private static final class FileFormat {
        int version = 3;
        Map<String, Data> companions = new HashMap<>();
        Set<String> retired = new HashSet<>();
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create(); // gọn, không thụt lề: tệp nhỏ, ghi nhanh
    private static FileFormat data = new FileFormat();
    @Nullable private static Path file;
    private static boolean dirty;

    public static void load(MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("lymeowz.json");
        data = new FileFormat();
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    FileFormat f = GSON.fromJson(r, FileFormat.class);
                    if (f != null && f.version >= 3) data = f;
                    else if (f != null) LyMeowzMod.LOGGER.info("Dữ liệu LyMeowz bản cũ – bắt đầu lại từ đầu.");
                }
            }
        } catch (Exception e) {
            LyMeowzMod.LOGGER.error("Không đọc được {}", file, e);
        }
        if (data.companions == null) data.companions = new HashMap<>();
        if (data.retired == null) data.retired = new HashSet<>();
        for (Data d : data.companions.values()) {
            if (d.history == null) d.history = new ArrayList<>();
            if (d.structures == null) d.structures = new HashMap<>();
            if (d.skipped == null) d.skipped = new HashSet<>();
            if (d.builtHouses == null) d.builtHouses = new ArrayList<>();
            if (d.rejectedSites == null) d.rejectedSites = new ArrayList<>();
            if (d.campBlocks == null) d.campBlocks = new ArrayList<>();
        }
        dirty = false;
    }

    /** Lưu ngay (lúc tắt server). */
    public static void save() {
        if (!dirty || file == null) return;
        String json = GSON.toJson(data);
        dirty = false;
        write(file, json);
    }

    /** Lưu định kỳ: dựng JSON trên luồng chính (nhanh), còn ghi đĩa thì để luồng phụ làm — server không phải chờ ổ cứng. */
    public static void saveAsync() {
        if (!dirty || file == null) return;
        String json = GSON.toJson(data);
        dirty = false;
        Path f = file;
        IO.execute(() -> write(f, json));
    }

    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LyMeowz-save");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static synchronized void write(Path f, String json) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("lymeowz.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LyMeowzMod.LOGGER.error("Không lưu được {}", f, e);
        }
    }

    public static void unload() {
        save();
        file = null;
        data = new FileFormat();
    }

    public static void markDirty() {
        dirty = true;
    }

    @Nullable
    public static Data get(UUID companion) {
        return data.companions.get(companion.toString());
    }

    public static Collection<Data> all() {
        return data.companions.values();
    }

    public static boolean isEmpty() {
        return data.companions.isEmpty();
    }

    public static Data create(UUID companion, Spot at) {
        Data d = new Data();
        d.companion = companion.toString();
        d.last = at;
        data.companions.put(d.companion, d);
        dirty = true;
        return d;
    }

    /** Bản mới thay bản cũ (giữ nguyên trí nhớ, túi đồ, nhà...). */
    public static void transfer(UUID from, UUID to) {
        Data d = data.companions.remove(from.toString());
        if (d == null) return;
        d.companion = to.toString();
        data.companions.put(d.companion, d);
        data.retired.add(from.toString());
        dirty = true;
    }

    public static void remove(UUID companion) {
        if (data.companions.remove(companion.toString()) != null) dirty = true;
    }

    public static boolean isRetired(UUID companion) {
        return data.retired.contains(companion.toString());
    }

    public static void forgetRetired(UUID companion) {
        if (data.retired.remove(companion.toString())) dirty = true;
    }

    public static List<Data> list() {
        return new ArrayList<>(data.companions.values());
    }
}
