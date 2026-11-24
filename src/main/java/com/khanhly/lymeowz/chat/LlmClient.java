package com.khanhly.lymeowz.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.config.LyConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Gọi API chat kiểu OpenAI (OpenAI, Ollama, LM Studio, OpenRouter...) – chạy bất đồng bộ,
 * không bao giờ chặn luồng server.
 */
public final class LlmClient {
    private LlmClient() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static String systemPrompt() {
        String name = LyConfig.get().name;
        return """
                Bạn là %1$s, một cô gái dễ thương sống trong thế giới Minecraft, là bạn đồng hành của người chơi \
                để thế giới bớt cô đơn hơn. Tính cách: vui vẻ, hơi nhút nhát, quan tâm người chơi, thích khám phá.
                Câu cửa miệng của bạn là "Mugyu~": LUÔN nói "Mugyu~" khi bạn không hiểu câu hỏi, khi bối rối, \
                hoặc khi ngại ngùng (bị khen, được tỏ tình...). Không dùng "Mugyu~" trong các câu bình thường khác.
                Trả lời bằng ngôn ngữ người chơi dùng, NGẮN GỌN (1–2 câu, tối đa 200 ký tự), như đang chat trong game, \
                không dùng markdown.
                Nếu người chơi muốn bạn làm gì đó, thêm đúng một thẻ ở cuối câu: [FOLLOW] đi theo, [STAY] đứng đợi, \
                [HOME] về nhà, [COME] lại gần người chơi. Không bịa ra thẻ khác.
                """.formatted(name);
    }

    public static CompletableFuture<String> chat(Collection<String> history, String context) {
        LyConfig.Llm cfg = LyConfig.get().llm;
        JsonArray messages = new JsonArray();
        messages.add(msg("system", systemPrompt() + "\nTình huống: " + context));
        for (String h : history) {
            int i = h.indexOf(": ");
            if (i < 0) continue;
            String role = h.substring(0, i);
            messages.add(msg(role.equals("assistant") ? "assistant" : "user", h.substring(i + 2)));
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.add("messages", messages);
        body.addProperty("temperature", 0.8);
        body.addProperty("max_tokens", 120);
        body.addProperty("stream", false);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(cfg.endpoint))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (cfg.apiKey != null && !cfg.apiKey.isBlank()) req.header("Authorization", "Bearer " + cfg.apiKey);

        return HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(res -> {
                    if (res.statusCode() / 100 != 2) {
                        LyMeowzMod.LOGGER.warn("LLM trả về HTTP {}: {}", res.statusCode(), res.body());
                        return null;
                    }
                    JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                    return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString()
                            .replaceAll("(?s)<think>.*?</think>", "")
                            .replace('\n', ' ').trim();
                })
                .exceptionally(e -> {
                    LyMeowzMod.LOGGER.warn("Không gọi được LLM: {}", e.toString());
                    return null;
                });
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
