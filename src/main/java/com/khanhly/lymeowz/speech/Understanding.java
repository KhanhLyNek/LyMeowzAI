package com.khanhly.lymeowz.speech;

import com.khanhly.lymeowz.chat.Brain;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.mind.MindData;
import com.khanhly.lymeowz.survival.Survival;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hiểu câu người chơi nói mà không cần mạng: nhận ra câu hỏi, chủ đề và thái độ (khen/chê),
 * rồi trả lời bằng chính trạng thái, trí nhớ và cảm xúc của cô ấy.
 */
public final class Understanding {
    private Understanding() {}

    private static final String[] POSITIVE = {"de thuong", "cute", "xinh", "dep", "gioi", "tuyet", "ngoan", "yeu", "thuong",
            "thich cau", "thich em", "tot bung", "dang yeu", "kawaii", "pro", "hay qua", "lam tot", "gioi qua", "tuyet voi"};
    private static final String[] NEGATIVE = {"ngu", "ngoc", "dan don", "ghet", "cut", "xau", "te", "vo dung", "im di", "im mom",
            "do ngoc", "phien", "bien di", "dumb", "stupid", "hate", "useless"};
    private static final String[] QUESTION = {"khong", "chua", "gi", "dau", "sao", "nao", "may", "bao nhieu", "khi nao", "ai",
            "the nao", "phai khong", "a", "ha", "nhi"};

    /** @return true nếu đã trả lời. */
    public static boolean respond(LyMeowzEntity ly, ServerPlayer player, String raw) {
        String n = Brain.normalize(raw).replace(" buon ngu ", " buonngu ");
        boolean question = raw.trim().endsWith("?") || endsWithAny(n, QUESTION);
        int sentiment = score(n, POSITIVE) - score(n, NEGATIVE) * 2;
        Mind mind = ly.mind();

        // 1) Câu hỏi về bản thân / trạng thái / trí nhớ.
        if (question || has(n, "ke", "noi cho", "cho biet")) {
            String answer = answer(ly, player, n);
            if (answer != null) {
                mind.event(Mind.Event.CHATTED, player, null, null);
                ly.sayRaw(answer);
                return true;
            }
        }
        // 2) Khen / chê.
        if (sentiment > 0) {
            mind.event(Mind.Event.PRAISED, player, null, null);
            ly.speak(Speech.Act.PRAISED, Speech.Ctx.of().to(player));
            ly.hearts();
            return true;
        }
        if (sentiment < 0) {
            mind.event(Mind.Event.INSULTED, player, null, null);
            ly.speak(Speech.Act.INSULTED, Speech.Ctx.of().to(player));
            return true;
        }
        // 3) Kể chuyện / nói chuyện phiếm khi được rủ.
        if (has(n, "noi chuyen", "tam su", "ke chuyen", "buon qua", "chan qua", "chat")) {
            mind.event(Mind.Event.CHATTED, player, null, null);
            ly.speak(Speech.Act.SMALLTALK, Speech.Ctx.of().to(player));
            return true;
        }
        return false;
    }

    private static String answer(LyMeowzEntity ly, ServerPlayer player, String n) {
        Speech.Voice v = new Speech.Voice(ly, player);
        MindData m = ly.mind().data();
        String me = v.self, you = v.you;

        if (has(n, "doi", "an chua", "an gi", "hungry")) {
            int h = ly.getHunger();
            String s = h >= 17 ? v.pick(cap(me) + " no rồi", "Không đói đâu", cap(me) + " ăn rồi")
                    : h >= 10 ? cap(me) + " " + v.pick("hơi đói", "đói đói một chút")
                    : cap(me) + " " + v.degree(100 - h * 5) + " đói" + v.pick("... Mugyu~", ", " + you + " có gì ăn không");
            return s + " (" + h + "/20)" + v.ending();
        }
        if (has(n, "met", "buonngu", "tired")) {
            return (m.energy > 60 ? cap(me) + " vẫn khỏe" : cap(me) + " " + v.degree(100 - m.energy) + " mệt") + v.ending();
        }
        if (has(n, "so khong", "so gi", "co so", "scared")) {
            return (m.fear > 40 ? cap(me) + " " + v.degree(m.fear) + " sợ" + (ly.level().isDarkOutside() ? ", trời tối quá" : "")
                    : cap(me) + " không sợ" + (m.personality.brave > 0.6f ? ", có " + me + " đây rồi" : " lắm đâu")) + v.ending();
        }
        if (has(n, "thay sao", "the nao", "on khong", "khoe khong", "vui khong", "buon khong", "cam thay")) {
            return Speech.build(ly, Speech.Act.FEELING, Speech.Ctx.of().to(player));
        }
        if (has(n, "dang lam gi", "lam gi vay", "lam gi the", "doing")) {
            String doing = ly.survival.currentName();
            return (doing.equals("nghỉ") ? cap(me) + " đang " + v.pick("nghỉ", "rảnh", "đứng ngắm trời") : cap(me) + " đang " + Speech.verb(v, doing)) + v.ending();
        }
        if (has(n, "tui", "co gi", "inventory", "trong tui")) {
            return v.pick("Trong túi " + me + " có ", cap(me) + " đang có ") + Survival.summary(ly.inventoryWithHand()) + v.ending();
        }
        if (has(n, "nha", "home") && has(n, "dau", "huong nao", "o dau")) {
            BlockPos home = ly.getHomePos();
            if (home == null) return cap(me) + " chưa có nhà, " + v.pick("đang để dành đá để xây", "đi đâu ngủ đó thôi") + v.ending();
            return "Nhà " + Speech.at(ly, home) + ", " + Speech.distance(ly, home) + v.ending();
        }
        Mind.PlaceKind asked = has(n, "mo", "da", "quang", "khoang") ? (has(n, "quang", "sat", "than") ? Mind.PlaceKind.ORE : Mind.PlaceKind.STONE)
                : has(n, "cay", "rung", "go") ? Mind.PlaceKind.TREES
                : has(n, "thu", "bo", "cuu", "heo", "ga") ? Mind.PlaceKind.ANIMALS
                : has(n, "nuoc", "ho", "song") ? Mind.PlaceKind.WATER
                : has(n, "nguy hiem", "quai") ? Mind.PlaceKind.DANGER : null;
        if (asked != null && has(n, "dau", "o dau", "huong nao", "biet", "thay")) {
            MindData.Place pl = ly.mind().nearest(asked, ly.blockPosition());
            if (pl == null) return "Mugyu~ " + me + " chưa thấy " + Speech.placeDesc(v, asked) + " nào hết" + v.ending();
            BlockPos p = new BlockPos(pl.x, pl.y, pl.z);
            return cap(me) + " nhớ có " + Speech.placeDesc(v, asked) + " " + Speech.at(ly, p) + ", " + Speech.distance(ly, p) + v.ending();
        }
        if (has(n, "ten gi", "la ai", "who are you", "ten cau")) {
            String nm = ly.displayName();
            if (nm != null) return cap(me) + " là " + nm + v.ending();
            return v.affinity >= 15 ? cap(me) + " chưa có tên... " + v.pick("ai đặt cho " + me + " một cái thì hay biết mấy", "nếu " + you + " có bảng tên thì...") + " Mugyu~"
                    : v.pick("...Không có tên.", cap(me) + " không có tên đâu.");
        }
        if (has(n, "thich gi", "so thich", "tinh cach", "la nguoi", "uoc mo", "ly tuong")) {
            return cap(me) + " là đứa " + m.personality.describe() + ". " + v.pick("Điều " + me + " muốn nhất là ", cap(me) + " mơ được ") + m.personality.idealText() + v.ending();
        }
        if (has(n, "thich minh", "thich to", "yeu minh", "yeu to", "quy minh", "ghet minh")) {
            float a = ly.mind().affinity(player.getUUID());
            if (a > 70) return "M-Mugyu~! " + v.pick(cap(me) + " quý " + you + " nhất đó", "Đ-đương nhiên rồi... đừng hỏi nữa");
            if (a > 20) return cap(me) + " quý " + you + v.ending();
            if (a > -20) return cap(me) + " chưa biết nữa" + v.ending();
            return cap(me) + " vẫn còn giận " + you + " đó.";
        }
        if (has(n, "hom nay", "lam duoc gi", "da lam gi")) {
            ly.mind().rollDay();
            if (m.today.isEmpty() && m.craftedToday.isEmpty()) return v.pick("Hôm nay " + me + " chưa làm được gì hết", "Chưa có gì cả") + "... Mugyu~";
            StringBuilder b = new StringBuilder("Hôm nay " + me + " ");
            int i = 0;
            for (var e : m.today.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(4).toList()) {
                if (i++ > 0) b.append(", ");
                b.append(Speech.verb(v, e.getKey()).replaceFirst("^đi ", "")).append(e.getValue() > 1 ? " " + e.getValue() + " lần" : "");
            }
            if (!m.craftedToday.isEmpty()) b.append(i > 0 ? ", còn " : "").append("làm được ").append(String.join(", ", m.craftedToday.stream().distinct().limit(5).toList()));
            return b + v.ending();
        }
        if (has(n, "nho gi", "ke chuyen", "chuyen gi", "nho khong")) {
            if (m.episodes.isEmpty()) return cap(me) + " chưa có chuyện gì để kể hết" + v.ending();
            for (int k = 0; k < 8; k++) {
                MindData.Episode e = m.episodes.get(m.episodes.size() - 1 - v.r.nextInt(Math.min(8, m.episodes.size())));
                String s = Speech.recall(ly, v, e);
                if (s != null) return s + v.ending();
            }
            return cap(me) + " không nhớ chuyện gì đặc biệt" + v.ending();
        }
        if (has(n, "ke hoach", "lo trinh", "muc tieu", "dinh lam gi", "se lam gi", "tiep theo", "plan")) {
            return com.khanhly.lymeowz.survival.Roadmap.describe(ly, v.affinity >= 15);
        }
        if (has(n, "giau", "co bao nhieu", "tai san", "kim cuong", "sat")) {
            return com.khanhly.lymeowz.survival.Roadmap.wealth(ly, v.affinity >= 40) + v.ending();
        }
        if (has(n, "may gio", "troi", "ngay hay dem")) {
            return (ly.level().isDarkOutside() ? v.pick("Tối rồi đó", "Đang đêm") : v.pick("Đang ban ngày", "Trời sáng mà")) + v.ending();
        }
        return null;
    }

    // ------------------------------------------------------------------

    private static boolean has(String n, String... words) {
        for (String w : words) if (n.contains(" " + w + " ")) return true;
        return false;
    }

    private static int score(String n, String[] words) {
        int s = 0;
        for (String w : words) if (n.contains(" " + w + " ")) s++;
        return s;
    }

    private static boolean endsWithAny(String n, String[] words) {
        String t = n.trim();
        for (String w : words) if (t.endsWith(" " + w) || t.equals(w)) return true;
        return false;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
