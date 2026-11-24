package com.khanhly.lymeowz.chat;

import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hiểu lời người chơi nói. Lệnh quen thuộc được xử lý tại chỗ (luôn chạy, không cần mạng);
 * câu lạ thì hỏi mô hình ngôn ngữ nếu đã bật, còn không thì "Mugyu~?".
 */
public class Brain {
    public enum Intent { SET_HOME, HOME, COME, FOLLOW, STAY, LOVE, THANKS, HOW_ARE_YOU, WHO, GREET, UNKNOWN }

    private static final Pattern TAG = Pattern.compile("\\[(FOLLOW|STAY|HOME|COME)]", Pattern.CASE_INSENSITIVE);

    private final LyMeowzEntity ly;
    /** Lịch sử hội thoại ngắn, dạng "user:..." / "assistant:...". */
    private final Deque<String> history = new ArrayDeque<>();
    private boolean waitingLlm;

    public Brain(LyMeowzEntity ly) {
        this.ly = ly;
    }

    /** Bỏ dấu tiếng Việt + chữ thường để so khớp "về nhà" lẫn "ve nha". */
    public static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd');
        return " " + n.replaceAll("[^a-z0-9?]+", " ").trim() + " ";
    }

    private static boolean has(String n, String... phrases) {
        for (String p : phrases) if (n.contains(" " + p + " ")) return true;
        return false;
    }

    public static Intent parse(String raw) {
        String n = normalize(raw);
        if (has(n, "sethome", "set home", "nha moi", "day la nha", "o day la nha", "lam nha o day")) return Intent.SET_HOME;
        if (has(n, "ve nha", "go home", "home", "di ve")) return Intent.HOME;
        if (has(n, "lai day", "den day", "toi day", "qua day", "come", "come here")) return Intent.COME;
        if (has(n, "di theo", "theo minh", "theo to", "theo anh", "theo em", "follow", "follow me", "di thoi", "di nao")) return Intent.FOLLOW;
        if (has(n, "dung yen", "dung day", "doi o day", "o day doi", "o yen", "stay", "wait", "doi to", "doi minh", "doi anh")) return Intent.STAY;
        if (has(n, "yeu", "love", "thich cau", "thich em", "thuong", "de thuong", "cute", "xinh", "dep", "kawaii",
                "hon", "kiss", "om", "hug", "marry", "cuoi", "vo", "waifu", "beautiful", "pretty")) return Intent.LOVE;
        if (has(n, "cam on", "thank", "thanks", "thank you", "tks", "ty")) return Intent.THANKS;
        if (has(n, "khoe khong", "on khong", "the nao roi", "how are you", "sao roi")) return Intent.HOW_ARE_YOU;
        if (has(n, "ten gi", "la ai", "who are you", "your name")) return Intent.WHO;
        if (has(n, "chao", "xin chao", "hi", "hello", "hey", "yo", "alo")) return Intent.GREET;
        return Intent.UNKNOWN;
    }

    /** Người chơi chat gì đó. */
    public void hear(ServerPlayerEntity player, String raw) {
        if (!ly.isOwner(player)) return;
        LyConfig cfg = LyConfig.get();
        String n = normalize(raw);
        String name = normalize(cfg.name).trim();
        boolean named = n.contains(" " + name + " ") || has(n, "ly", "meowz", "lymeowz");
        boolean near = player.getWorld() == ly.getWorld() && player.squaredDistanceTo(ly) < (double) cfg.hearingDistance * cfg.hearingDistance;
        if (!named && !near) return;

        remember("user: " + raw);
        Intent intent = parse(raw);
        switch (intent) {
            case SET_HOME -> {
                ly.setHome((ServerWorld) player.getWorld(), player.getBlockPos());
                reply(Lines.HOME_SET);
                ly.hearts();
            }
            case HOME -> { ly.setMode(LyMeowzEntity.Mode.HOME); reply(Lines.GO_HOME); }
            case COME -> { come(player); reply(Lines.COME); }
            case FOLLOW -> { ly.setMode(LyMeowzEntity.Mode.FOLLOW); reply(Lines.FOLLOW); }
            case STAY -> { ly.setMode(LyMeowzEntity.Mode.STAY); reply(Lines.STAY); }
            case LOVE -> { reply(Lines.EMBARRASSED); ly.hearts(); }
            case THANKS -> reply(Lines.THANKS);
            case HOW_ARE_YOU -> reply(Lines.HOW_ARE_YOU);
            case WHO -> reply(Lines.WHO_ARE_YOU);
            case GREET -> reply(Lines.GREET);
            case UNKNOWN -> {
                if (cfg.llm.enabled) askLlm(player);
                else if (named || n.contains("?")) reply(Lines.CONFUSED);
            }
        }
    }

    private void come(ServerPlayerEntity player) {
        ly.setMode(LyMeowzEntity.Mode.FOLLOW);
        if (player.getWorld() != ly.getWorld() || player.distanceTo(ly) > 24) ly.teleportToOwner(player);
        else ly.getNavigation().startMovingTo(player, 1.3);
    }

    private void reply(String[] lines) {
        String line = Lines.pick(lines, ly.getRandom());
        ly.say(line);
        remember("assistant: " + line.replace("%s", LyConfig.get().name));
    }

    private void remember(String s) {
        history.addLast(s);
        int max = Math.max(2, LyConfig.get().llm.maxHistory);
        while (history.size() > max) history.removeFirst();
    }

    private void askLlm(ServerPlayerEntity player) {
        if (waitingLlm) return;
        waitingLlm = true;
        var server = player.getServer();
        String context = "Người chơi tên " + player.getName().getString()
                + ", đang cách bạn " + (player.getWorld() == ly.getWorld() ? (int) player.distanceTo(ly) + " block" : "rất xa (khác thế giới)")
                + ". Chế độ hiện tại: " + ly.getMode() + ". Trời đang " + (ly.getWorld().isNight() ? "tối" : "sáng") + ".";
        LlmClient.chat(history, context).whenComplete((text, err) -> server.execute(() -> {
            waitingLlm = false;
            if (ly.isRemoved()) return;
            if (err != null || text == null || text.isBlank()) {
                reply(Lines.CONFUSED);
                return;
            }
            String clean = text;
            Matcher m = TAG.matcher(text);
            while (m.find()) {
                switch (m.group(1).toUpperCase(Locale.ROOT)) {
                    case "FOLLOW" -> ly.setMode(LyMeowzEntity.Mode.FOLLOW);
                    case "STAY" -> ly.setMode(LyMeowzEntity.Mode.STAY);
                    case "HOME" -> ly.setMode(LyMeowzEntity.Mode.HOME);
                    case "COME" -> { ServerPlayerEntity o = ly.getOwner(); if (o != null) come(o); }
                }
            }
            clean = TAG.matcher(clean).replaceAll("").trim();
            if (clean.length() > 256) clean = clean.substring(0, 253) + "...";
            ly.sayRaw(clean);
            remember("assistant: " + clean);
        }));
    }

    public void writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (String s : history) list.add(NbtString.of(s));
        nbt.put("LyHistory", list);
    }

    public void readNbt(NbtCompound nbt) {
        history.clear();
        NbtList list = nbt.getList("LyHistory", NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) history.addLast(list.getString(i));
    }
}
