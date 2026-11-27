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

    /** (Không còn dùng từ 7.1: cô ấy sống quanh nhà của mình, không bám theo người chơi.) */
    public int stayNearPlayer = 30;
    /** Cô ấy để ý thấy người chơi trong khoảng này (chào hỏi, ghé chơi). */
    public int noticePlayerRange = 96;
    /** Bán kính làm việc khi ở một mình quanh nhà. */
    public int homeWorkRadius = 48;
    /** (Không còn dùng từ 7.1: cô ấy không dịch chuyển tới người chơi nữa.) */
    public int teleportDistance = 160;
    /** Đang đi theo mà bạn bỏ xa hơn chừng này (block) thì cô ấy thôi theo, đi làm việc của mình. */
    public int followDistance = 32;
    /** Như trên, cho người rất thân (độ thân ≥ 61): cô ấy chịu khó tìm đường theo xa hơn. */
    public int followDistanceClose = 160;
    /** Gọi "lại đây": bạn bè thì cô ấy chỉ tới nếu bạn ở trong khoảng này; người rất thân gọi thì ở đâu cũng tìm đường tới. */
    public int comeDistance = 48;
    /** Không chặt/đào/săn trong bán kính này quanh giường của người chơi. */
    public int protectRadius = 12;
    /** Bán kính chunk ticket cô ấy tự giữ quanh mình (như một người chơi thật, thế giới quanh cô ấy luôn chạy). */
    public int chunkLoadRadius = 2;
    /** Tạo cô ấy (một lần cho cả thế giới) khi có người chơi vào lần đầu. */
    public boolean spawnOnFirstJoin = true;
    /** Bạn thân (độ thân ≥ 70) thỉnh thoảng được cô ấy bắt chuyện. */
    public boolean idleChatter = true;
    /** Khoảng cách (block) mà cô ấy nghe thấy bạn chat. Gọi tên (nếu bạn đã đặt tên) thì nghe ở mọi nơi. */
    public int hearingDistance = 24;
    /** Số phút cô ấy đứng đợi khi bạn hỏi toạ độ. */
    public int waitMinutes = 10;

    /** Kết nối mô hình ngôn ngữ (tùy chọn) – API kiểu OpenAI, ví dụ Ollama / LM Studio / OpenAI. */
    public Llm llm = new Llm();

    public static class Llm {
        public boolean enabled = false;
        public String endpoint = "http://localhost:11434/v1/chat/completions";
        public String model = "qwen2.5:7b";
        public String apiKey = "";
        public int timeoutSeconds = 20;
        public int maxHistory = 12;
        /** Khi bật LLM: mỗi sáng nhờ LLM giúp cô ấy tự nghĩ kế hoạch trong ngày (chọn điều muốn lo nhất). */
        public boolean thinking = true;
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
