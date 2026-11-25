package com.khanhly.lymeowz.chat;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.speech.Speech;
import com.khanhly.lymeowz.speech.Understanding;
import com.khanhly.lymeowz.survival.Survival;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hiểu lời người chơi nói. Cô ấy chỉ nghe người ở gần (hoặc gọi đúng tên cô ấy), và
 * làm theo hay không là tùy độ thân: người lạ thì cô ấy ngại, bạn bè mới nhờ được việc.
 */
public class Brain {
    public enum Intent {
        WHERE, SET_HOME, HOME, COME, FOLLOW, STAY, RELEASE, WOOD, STONE, FOOD, GIVE, WORK,
        INV, LOVE, THANKS, HOW_ARE_YOU, WHO, GREET, UNKNOWN
    }

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
        return " " + n.replace("?", " ? ").replaceAll("[^a-z0-9?]+", " ").trim() + " ";
    }

    private static boolean has(String n, String... phrases) {
        for (String p : phrases) if (n.contains(" " + p + " ")) return true;
        return false;
    }

    public static Intent parse(String raw) {
        String n = normalize(raw);
        boolean placeWord = has(n, "nha", "mo", "rung", "quang", "nuoc", "thu", "cay", "ruong", "chuong", "ruong do");
        if (has(n, "toa do", "coords", "coordinates", "where are you")
                || (!placeWord && has(n, "dang o dau", "o dau vay", "o dau the", "o dau roi", "o cho nao", "dau roi", "o dau"))) return Intent.WHERE;
        if (has(n, "sethome", "set home", "nha moi", "day la nha", "o day la nha", "lam nha o day")) return Intent.SET_HOME;
        if (has(n, "ve nha", "go home", "home", "di ve")) return Intent.HOME;
        if (has(n, "lai day", "den day", "toi day", "qua day", "come", "come here")) return Intent.COME;
        if (has(n, "di theo", "theo minh", "theo to", "theo anh", "theo em", "follow", "follow me", "di thoi", "di nao", "di cung")) return Intent.FOLLOW;
        if (has(n, "tu do", "di choi di", "lam viec cua cau", "khong can theo", "thoi khong can", "dung theo", "free", "dismiss")) return Intent.RELEASE;
        if (has(n, "chat cay", "chat go", "lay go", "kiem go", "don cay", "chop", "wood", "get wood")) return Intent.WOOD;
        if (has(n, "dao da", "lay da", "kiem da", "dao khoang", "dao quang", "kiem quang", "mine", "mining", "stone")) return Intent.STONE;
        if (has(n, "di san", "san thit", "kiem do an", "tim do an", "kiem thit", "hunt", "food")) return Intent.FOOD;
        if (has(n, "dua do", "dua cho", "dua het", "cho minh do", "cho to do", "give", "give me")) return Intent.GIVE;
        if (has(n, "tui do", "co gi", "dang co gi", "inventory", "bag", "trong tui")) return Intent.INV;
        if (has(n, "lam viec", "tu lo", "work", "sinh ton")) return Intent.WORK;
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
        LyConfig cfg = LyConfig.get();
        String n = normalize(raw);
        String myName = ly.displayName();
        boolean named = myName != null && n.contains(normalize(myName));
        boolean near = player.level() == ly.level() && player.distanceToSqr(ly) < (double) cfg.hearingDistance * cfg.hearingDistance;
        Intent intent = parse(raw);
        float a = ly.affinity(player);
        // Ở xa: chỉ nghe khi được gọi tên, hoặc người quen hỏi "cậu đang ở đâu".
        if (!named && !near && !(intent == Intent.WHERE && a >= LyMeowzEntity.ACQUAINTANCE)) return;
        if (ly.isSleeping()) return;

        if (ly.bond.onChat(player, n)) { remember("user: " + raw); return; }
        remember("user: " + raw);
        ly.mind().event(Mind.Event.CHATTED, player, null, null);
        Speech.Ctx to = Speech.Ctx.of().to(player);

        if (a < -30 && intent != Intent.WHERE) {
            if (ly.getRandom().nextFloat() < 0.4f) reply(player, Speech.Act.REFUSE, to);
            return;
        }
        switch (intent) {
            case WHERE -> where(player, a);
            case SET_HOME -> {
                if (a < LyMeowzEntity.CLOSE) { reply(player, Speech.Act.NOT_CLOSE, to); return; }
                ly.setHome((ServerLevel) player.level(), player.blockPosition());
                reply(player, Speech.Act.HOME_SET, to);
                ly.hearts();
            }
            case HOME -> ask(player, a, () -> { ly.setRequest("HOME", player, 20 * 60 * 10, null); reply(player, Speech.Act.ACK, to.text("HOME")); });
            case FOLLOW -> ask(player, a, () -> {
                ly.setRequest("FOLLOW", player, 0, null);
                reply(player, Speech.Act.ACK, to.text("FOLLOW"));
            });
            case COME -> ask(player, a, () -> {
                // "Lại đây": cô ấy đi bộ tìm đường tới. Bạn bè thì chỉ khi ở gần; rất thân thì xa mấy cũng tới.
                boolean same = player.level() == ly.level();
                double d = same ? player.distanceTo(ly) : Double.MAX_VALUE;
                if (!same || (d > cfg.comeDistance && a < LyMeowzEntity.CLOSE)) { reply(player, Speech.Act.TOO_FAR, to); return; }
                ly.setRequest("VISIT", player, 20L * 60 * 6, null);
                reply(player, Speech.Act.ACK, to.text("COME"));
            });
            case STAY -> ask(player, a, () -> {
                ly.setRequest("WAIT", player, 20L * 60 * cfg.waitMinutes, ly.blockPosition());
                reply(player, Speech.Act.ACK, to.text("STAY"));
            });
            case RELEASE -> {
                CompanionStore.Request r = ly.request();
                if (r != null && r.player.equals(player.getUUID().toString())) {
                    ly.clearRequest();
                    reply(player, Speech.Act.RELEASED, to);
                } else if (named || near) reply(player, Speech.Act.CONFUSED, to);
            }
            case WOOD, STONE, FOOD, WORK -> ask(player, a, () -> {
                ly.clearRequest();
                ly.survival.setFocus(switch (intent) {
                    case WOOD -> Survival.Focus.WOOD;
                    case STONE -> Survival.Focus.STONE;
                    case FOOD -> Survival.Focus.FOOD;
                    default -> Survival.Focus.AUTO;
                });
                reply(player, Speech.Act.ACK, to.text(intent.name()));
            });
            case GIVE -> {
                if (a < 55) { reply(player, Speech.Act.NOT_CLOSE, to); return; }
                int given = ly.giveResourcesTo(player);
                reply(player, given > 0 ? Speech.Act.GIVE : Speech.Act.GIVE_NOTHING, to.item(null, given));
            }
            case GREET -> reply(player, a < LyMeowzEntity.ACQUAINTANCE ? (ly.getRandom().nextBoolean() ? Speech.Act.STRANGER : Speech.Act.SHY) : Speech.Act.GREET, to);
            case THANKS -> reply(player, Speech.Act.THANKS, to);
            case HOW_ARE_YOU -> reply(player, a < LyMeowzEntity.ACQUAINTANCE ? Speech.Act.SHY : Speech.Act.FEELING, to);
            default -> {
                // Người lạ hỏi han linh tinh: cô ấy ngại, ít khi trả lời.
                if (a < LyMeowzEntity.ACQUAINTANCE && intent != Intent.WHO && intent != Intent.LOVE
                        && ly.getRandom().nextFloat() < 0.6f) {
                    if (named || n.contains("?")) reply(player, Speech.Act.SHY, to);
                    return;
                }
                if (Understanding.respond(ly, player, raw)) return;
                if (cfg.llm.enabled && a >= LyMeowzEntity.ACQUAINTANCE) askLlm(player);
                else if (named || n.contains("?")) reply(player, Speech.Act.CONFUSED, to);
            }
        }
    }

    /** Việc nhờ vả: chỉ bạn bè (độ thân ≥ 40) mới nhờ được. */
    private void ask(ServerPlayer player, float a, Runnable doIt) {
        if (a < LyMeowzEntity.FRIEND) {
            reply(player, Speech.Act.NOT_CLOSE, Speech.Ctx.of().to(player));
            return;
        }
        doIt.run();
    }

    /** "Cậu đang ở đâu?" – đọc toạ độ; người quen thì cô ấy đứng đợi (cắm trại) ở đó. */
    private void where(ServerPlayer player, float a) {
        String dim = switch (Compat.dimId(ly.level().dimension())) {
            case "minecraft:the_nether" -> " (Nether)";
            case "minecraft:the_end" -> " (The End)";
            default -> "";
        };
        String coords = ly.getBlockX() + " " + ly.getBlockY() + " " + ly.getBlockZ() + dim;
        boolean wait = a >= LyMeowzEntity.ACQUAINTANCE;
        if (wait) {
            int minutes = LyConfig.get().waitMinutes * (a >= LyMeowzEntity.FRIEND ? 2 : 1);
            ly.setRequest("WAIT", player, 20L * 60 * minutes, ly.blockPosition());
        }
        reply(player, Speech.Act.WAITING, Speech.Ctx.of().to(player).text(coords).item(null, wait ? 1 : 0));
    }

    private void reply(ServerPlayer player, Speech.Act act, Speech.Ctx ctx) {
        String line = Speech.build(ly, act, ctx.to(player));
        if (line.isEmpty()) return;
        ly.sayRaw(line, player);
        remember("assistant: " + line);
    }

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
        float a = ly.affinity(player);
        String context = "Người chơi tên " + player.getName().getString() + " (độ thân với bạn: " + (int) a + "/100"
                + (a < LyMeowzEntity.FRIEND ? ", chưa đủ thân để nhờ việc" : "") + ")"
                + ", đang cách bạn " + (player.level() == ly.level() ? (int) player.distanceTo(ly) + " block" : "rất xa (khác thế giới)")
                + ". Bạn đang " + ly.survival.currentName()
                + ", đói " + ly.getHunger() + "/20, túi đồ: " + Survival.summary(ly.inventoryWithHand())
                + ". Trời đang " + (Compat.isNight(ly.level()) ? "tối" : "sáng") + ". Trả lời ngắn.";
        LlmClient.chat(List.copyOf(history()), context).whenComplete((text, err) -> server.execute(() -> {
            waitingLlm = false;
            if (ly.isRemoved()) return;
            if (err != null || text == null || text.isBlank()) {
                reply(player, Speech.Act.CONFUSED, Speech.Ctx.of());
                return;
            }
            Matcher m = TAG.matcher(text);
            while (m.find()) {
                if (ly.affinity(player) < LyMeowzEntity.FRIEND) break;
                switch (m.group(1).toUpperCase(Locale.ROOT)) {
                    case "FOLLOW" -> ly.setRequest("FOLLOW", player, 0, null);
                    case "COME" -> {
                        if (player.level() == ly.level() && (player.distanceTo(ly) <= LyConfig.get().comeDistance || ly.affinity(player) >= LyMeowzEntity.CLOSE))
                            ly.setRequest("VISIT", player, 20L * 60 * 6, null);
                    }
                    case "STAY" -> ly.setRequest("WAIT", player, 20L * 60 * LyConfig.get().waitMinutes, ly.blockPosition());
                    case "HOME" -> ly.setRequest("HOME", player, 20 * 60 * 10, null);
                    case "WOOD" -> ly.survival.setFocus(Survival.Focus.WOOD);
                    case "STONE" -> ly.survival.setFocus(Survival.Focus.STONE);
                    case "FOOD" -> ly.survival.setFocus(Survival.Focus.FOOD);
                    case "GIVE" -> { if (ly.affinity(player) >= 55) ly.giveResourcesTo(player); }
                }
            }
            String clean = TAG.matcher(text).replaceAll("").trim();
            if (clean.length() > 256) clean = clean.substring(0, 253) + "...";
            ly.sayRaw(clean, player);
            remember("assistant: " + clean);
        }));
    }
}
