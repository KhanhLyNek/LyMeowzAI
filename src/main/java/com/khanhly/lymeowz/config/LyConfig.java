package com.khanhly.lymeowz.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.khanhly.lymeowz.LyMeowzMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/lymeowz.json – được tạo tự động lần chạy đầu tiên.
 */
public class LyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static LyConfig INSTANCE = new LyConfig();

    /** Tên hiển thị của cô ấy. */
    public String name = "LyMeowz";
    /** Bán kính (block) cô ấy được tự do đi chơi quanh người chơi. */
    public int followRadius = 100;
    /** Khi người chơi đứng lại, cô ấy làm việc (chặt cây, đào đá, săn) trong bán kính này quanh người chơi. */
    public int workRadius = 24;
    /** Không chặt/đào/săn trong bán kính này quanh nhà và giường của người chơi. */
    public int protectRadius = 16;
    /** Xa hơn khoảng này (hoặc khác chiều không gian) thì cô ấy dịch chuyển tới người chơi. */
    public int teleportDistance = 128;
    /** Bán kính chunk ticket cô ấy tự giữ load quanh mình (3 = vùng 1 chunk quanh cô ấy luôn được tick). */
    public int chunkLoadRadius = 3;
    /** Tự tạo một LyMeowz cho người chơi lần đầu vào thế giới. */
    public boolean autoSpawnOnFirstJoin = true;
    /** Thỉnh thoảng tự nói chuyện vu vơ. */
    public boolean idleChatter = true;
    /** Khi người chơi đi ngủ thì cô ấy cũng về nhà. */
    public boolean goHomeWhenOwnerSleeps = true;
    /** Khoảng cách (block) mà cô ấy nghe thấy bạn chat mà không cần gọi tên. */
    public int hearingDistance = 32;

    /** Kết nối mô hình ngôn ngữ (tùy chọn) – API kiểu OpenAI, ví dụ Ollama / LM Studio / OpenAI. */
    public Llm llm = new Llm();

    public static class Llm {
        public boolean enabled = false;
        public String endpoint = "http://localhost:11434/v1/chat/completions";
        public String model = "qwen2.5:7b";
        public String apiKey = "";
        public int timeoutSeconds = 20;
        public int maxHistory = 12;
    }

    public static LyConfig get() {
        return INSTANCE;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("lymeowz.json");
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    LyConfig loaded = GSON.fromJson(r, LyConfig.class);
                    if (loaded != null) INSTANCE = loaded;
                }
            }
            if (INSTANCE.llm == null) INSTANCE.llm = new Llm();
            // Ghi lại để bổ sung các khóa mới.
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(INSTANCE, w);
            }
        } catch (Exception e) {
            LyMeowzMod.LOGGER.error("Không đọc được config/lymeowz.json, dùng mặc định", e);
        }
    }
}
