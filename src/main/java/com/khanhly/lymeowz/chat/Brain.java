package com.khanhly.lymeowz.chat;

import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.world.CompanionStore;
import com.khanhly.lymeowz.speech.Speech;
import com.khanhly.lymeowz.speech.Understanding;
import com.khanhly.lymeowz.survival.Survival;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hiểu lời người chơi nói. Lệnh quen thuộc được xử lý tại chỗ (luôn chạy, không cần mạng);
 * câu lạ thì hỏi mô hình ngôn ngữ nếu đã bật, còn không thì "Mugyu~?".
 */
public class Brain {
    public enum Intent { SET_HOME, HOME, COME, FOLLOW, STAY, WOOD, STONE, FOOD, GIVE, INV, WORK, LOVE, THANKS, HOW_ARE_YOU, WHO, GREET, UNKNOWN }

    private static final Pattern TAG = Pattern.compile("\\[(FOLLOW|STAY|HOME|COME|WOOD|STONE|FOOD|GIVE)]", Pattern.CASE_INSENSITIVE);

    private final LyMeowzEntity ly;
    private boolean waitingLlm;

    public Brain(LyMeowzEntity ly) {
        this.ly = ly;
    }

    /** Bỏ dấu tiếng Việt + chữ thường để so khớp "về nhà" lẫn "ve nha". */
    public static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd');
        // Tách dấu "?" thành một từ riêng để "gi?" vẫn khớp "gi".
        return " " + n.replace("?", " ? ").replaceAll("[^a-z0-9?]+", " ").trim() + " ";
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
        if (has(n, "chat cay", "chat go", "lay go", "kiem go", "don cay", "chop", "wood", "get wood")) return Intent.WOOD;
        if (has(n, "dao da", "lay da", "kiem da", "dao khoang", "dao quang", "kiem quang", "mine", "mining", "stone")) return Intent.STONE;
        if (has(n, "di san", "san thit", "kiem do an", "tim do an", "kiem thit", "hunt", "food")) return Intent.FOOD;
        if (has(n, "dua do", "dua cho", "dua het", "cho minh do", "cho to do", "give", "give me")) return Intent.GIVE;
        if (has(n, "tui do", "co gi", "dang co gi", "inventory", "bag", "trong tui")) return Intent.INV;
        if (has(n, "lam viec", "tu do", "tu lo", "work", "sinh ton")) return Intent.WORK;
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
    public void hear(ServerPlayer player, String raw) {
        if (!ly.isOwner(player)) return;
        LyConfig cfg = LyConfig.get();
        String n = normalize(raw);
        String name = normalize(cfg.name).trim();
        boolean named = n.contains(" " + name + " ") || has(n, "ly", "meowz", "lymeowz");
        boolean near = player.level() == ly.level() && player.distanceToSqr(ly) < (double) cfg.hearingDistance * cfg.hearingDistance;
        if (!named && !near) return;

        remember("user: " + raw);
        ly.mind().event(com.khanhly.lymeowz.mind.Mind.Event.CHATTED, player, null, null);
        Intent intent = parse(raw);
        // Người đối xử tệ với cô ấy thì cô ấy có thể không nghe lời.
        boolean grudge = ly.mind().affinity(player.getUUID()) < -50 && ly.getRandom().nextFloat() < 0.6f;
        if (grudge && intent.ordinal() <= Intent.WORK.ordinal()) { reply(Speech.Act.REFUSE, Speech.Ctx.of()); return; }
        switch (intent) {
            case SET_HOME -> {
                ly.setHome((ServerLevel) player.level(), player.blockPosition());
                reply(Speech.Act.HOME_SET, Speech.Ctx.of());
                ly.hearts();
            }
            case HOME -> { ly.setMode(LyMeowzEntity.Mode.HOME); ack("HOME"); }
            case COME -> { come(player); ack("COME"); }
            case FOLLOW -> { ly.setMode(LyMeowzEntity.Mode.FOLLOW); ack("FOLLOW"); }
            case STAY -> { ly.setMode(LyMeowzEntity.Mode.STAY); ack("STAY"); }
            case WOOD -> { ly.setMode(LyMeowzEntity.Mode.FOLLOW); ly.survival.setFocus(Survival.Focus.WOOD); ack("WOOD"); }
            case STONE -> { ly.setMode(LyMeowzEntity.Mode.FOLLOW); ly.survival.setFocus(Survival.Focus.STONE); ack("STONE"); }
            case FOOD -> { ly.setMode(LyMeowzEntity.Mode.FOLLOW); ly.survival.setFocus(Survival.Focus.FOOD); ack("FOOD"); }
            case WORK -> { ly.setMode(LyMeowzEntity.Mode.FOLLOW); ly.survival.setFocus(Survival.Focus.AUTO); ack("WORK"); }
            case GIVE -> {
                int given = ly.giveResourcesTo(player);
                reply(given > 0 ? Speech.Act.GIVE : Speech.Act.GIVE_NOTHING, Speech.Ctx.of().item(null, given));
            }
            case GREET -> reply(Speech.Act.GREET, Speech.Ctx.of());
            case THANKS -> reply(Speech.Act.THANKS, Speech.Ctx.of());
            case HOW_ARE_YOU -> reply(Speech.Act.FEELING, Speech.Ctx.of());
            default -> {
                // Câu hỏi, khen/chê, rủ nói chuyện: tự hiểu và tự trả lời từ trạng thái + trí nhớ.
                if (Understanding.respond(ly, player, raw)) return;
                if (cfg.llm.enabled) askLlm(player);
                else if (named || n.contains("?")) reply(Speech.Act.CONFUSED, Speech.Ctx.of());
            }
        }
    }

    private void ack(String what) {
        reply(Speech.Act.ACK, Speech.Ctx.of().text(what));
    }

    private void come(ServerPlayer player) {
        ly.setMode(LyMeowzEntity.Mode.FOLLOW);
        if (player.level() != ly.level() || player.distanceTo(ly) > 24) ly.teleportToOwner(player);
        else ly.getNavigation().moveTo(player, 1.3);
    }

    private void reply(Speech.Act act, Speech.Ctx ctx) {
        String line = Speech.build(ly, act, ctx.to(ly.getOwner()));
        if (line.isEmpty()) return;
        ly.sayRaw(line);
        remember("assistant: " + line);
    }

    /** Lịch sử hội thoại ngắn ("user: ..." / "assistant: ...") – lưu cùng dữ liệu của cô ấy. */
    private List<String> history() {
        CompanionStore.Data d = ly.data();
        return d == null ? new ArrayList<>() : d.history;
    }

    private void remember(String s) {
        List<String> h = history();
        h.add(s);
        int max = Math.max(2, LyConfig.get().llm.maxHistory);
        while (h.size() > max) h.remove(0);
        CompanionStore.markDirty();
    }

    private void askLlm(ServerPlayer player) {
        if (waitingLlm) return;
        waitingLlm = true;
        var server = ((ServerLevel) player.level()).getServer();
        String context = "Người chơi tên " + player.getName().getString()
                + ", đang cách bạn " + (player.level() == ly.level() ? (int) player.distanceTo(ly) + " block" : "rất xa (khác thế giới)")
                + ". Chế độ hiện tại: " + ly.getMode() + ", đang " + ly.survival.currentName()
                + ", đói " + ly.getHunger() + "/20, túi đồ: " + com.khanhly.lymeowz.survival.Survival.summary(ly.inventoryWithHand()) + ". Trời đang " + (Compat.isNight(ly.level()) ? "tối" : "sáng") + ".";
        LlmClient.chat(List.copyOf(history()), context).whenComplete((text, err) -> server.execute(() -> {
            waitingLlm = false;
            if (ly.isRemoved()) return;
            if (err != null || text == null || text.isBlank()) {
                reply(Speech.Act.CONFUSED, Speech.Ctx.of());
                return;
            }
            String clean = text;
            Matcher m = TAG.matcher(text);
            while (m.find()) {
                switch (m.group(1).toUpperCase(Locale.ROOT)) {
                    case "FOLLOW" -> ly.setMode(LyMeowzEntity.Mode.FOLLOW);
                    case "STAY" -> ly.setMode(LyMeowzEntity.Mode.STAY);
                    case "HOME" -> ly.setMode(LyMeowzEntity.Mode.HOME);
                    case "COME" -> { ServerPlayer o = ly.getOwner(); if (o != null) come(o); }
                    case "WOOD" -> ly.survival.setFocus(com.khanhly.lymeowz.survival.Survival.Focus.WOOD);
                    case "STONE" -> ly.survival.setFocus(com.khanhly.lymeowz.survival.Survival.Focus.STONE);
                    case "FOOD" -> ly.survival.setFocus(com.khanhly.lymeowz.survival.Survival.Focus.FOOD);
                    case "GIVE" -> { ServerPlayer o = ly.getOwner(); if (o != null) ly.giveResourcesTo(o); }
                }
            }
            clean = TAG.matcher(clean).replaceAll("").trim();
            if (clean.length() > 256) clean = clean.substring(0, 253) + "...";
            ly.sayRaw(clean);
            remember("assistant: " + clean);
        }));
    }
}
