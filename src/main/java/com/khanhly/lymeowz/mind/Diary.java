package com.khanhly.lymeowz.mind;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Nhật ký của cô ấy: cuối mỗi ngày (lúc chạng vạng hoặc khi đi ngủ) cô ấy viết một trang về những gì đã làm,
 * ai đã gặp, chuyện gì xảy ra và cảm thấy thế nào, rồi cất cuốn "Nhật ký" vào rương ở nhà cho người chơi đọc.
 * Câu chữ được ghép từ trí nhớ thật, không có trang viết sẵn.
 */
public final class Diary {
    private Diary() {}

    public static final String TITLE = "Nhật ký";
    private static final int MAX_PAGES = 100;

    /** Gọi mỗi giây; tự biết khi nào cần viết. */
    public static void tick(LyMeowzEntity ly, ServerLevel level) {
        MindData m = ly.mind().data();
        long clock = level.getOverworldClockTime();
        long day = clock / 24000, tod = Math.floorMod(clock, 24000);
        if (m.diaryDay < 0) { m.diaryDay = day; m.diaryMark = m.lifeTicks; return; }
        boolean evening = tod > 12300 || ly.isSleeping();
        // m.diaryDay = ngày chưa viết trang.
        if (day > m.diaryDay || (day == m.diaryDay && evening)) {
            write(ly, level, m, m.diaryDay);
            m.diaryDay = day == m.diaryDay ? day + 1 : day;
            m.diaryMark = m.lifeTicks;
        }
    }

    private static void write(LyMeowzEntity ly, ServerLevel level, MindData m, long day) {
        String page = compose(ly, level, m, day);
        m.diary.add(page);
        while (m.diary.size() > MAX_PAGES) m.diary.remove(0);
        storeBook(ly, level, m);
    }

    /** Ghép một trang nhật ký. */
    public static String compose(LyMeowzEntity ly, ServerLevel level, MindData m, long day) {
        var r = ly.getRandom();
        StringBuilder b = new StringBuilder("Ngày ").append(day + 1).append("\n\n");
        // Thời tiết.
        if (level.isThundering()) b.append(pick(r, "Hôm nay sấm chớp đùng đùng. ", "Bão to quá. "));
        else if (level.isRaining()) b.append(pick(r, "Hôm nay trời mưa. ", "Mưa cả ngày. "));
        else b.append(pick(r, "Trời nắng đẹp. ", "Hôm nay trời trong. ", ""));

        // Sáng nay định làm gì (tư duy của cô ấy).
        String plan = ly.thinker.plan();
        if (plan != null) b.append("Sáng nay tớ tính: ").append(plan.replaceFirst("^Hôm nay ", "")).append(" ");
        // Việc đã làm.
        ly.mind().rollDay();
        List<String> did = new ArrayList<>();
        for (var e : m.today.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(3).toList())
            did.add(activity(e.getKey(), e.getValue()));
        if (!did.isEmpty()) b.append("Tớ ").append(String.join(", ", did)).append(". ");
        if (!m.craftedToday.isEmpty()) {
            Set<String> made = new LinkedHashSet<>(m.craftedToday);
            b.append("Làm được ").append(String.join(", ", made.stream().limit(4).toList())).append(". ");
        }

        // Chuyện đáng nhớ trong ngày.
        int told = 0;
        for (MindData.Episode e : m.episodes) {
            if (e.time < m.diaryMark || told >= 3) continue;
            String s = episode(e);
            if (s != null) { b.append(s).append(" "); told++; }
        }

        // Người đã gặp hôm nay.
        List<String> met = new ArrayList<>();
        long now = level.getGameTime();
        for (MindData.Relation rel : m.relations.values()) {
            if (rel.seenAt >= 0 && now - rel.seenAt < 24000 && rel.name != null) met.add(rel.name + (rel.affinity >= 61 ? " ♥" : ""));
        }
        if (!met.isEmpty()) b.append("Hôm nay gặp ").append(String.join(", ", met.stream().limit(3).toList())).append(". ");
        else if (r.nextBoolean()) b.append(pick(r, "Cả ngày chẳng gặp ai. ", "Hôm nay một mình. "));

        // Cảm xúc cuối ngày.
        b.append(switch (ly.mind().dominantFeeling()) {
            case "JOY" -> pick(r, "Vui ghê~", "Một ngày tuyệt vời.", "Mong mai cũng vậy.");
            case "FEAR" -> pick(r, "Hơi sợ... mong đêm nay yên ổn.", "Tim vẫn còn đập thình thịch.");
            case "BORED" -> pick(r, "Chán chán...", "Mai phải đi đâu đó mới được.");
            case "CURIOUS" -> pick(r, "Không biết bên kia đồi có gì nhỉ?", "Mai đi khám phá thôi!");
            case "HUNGRY" -> pick(r, "Đói bụng quá...", "Phải kiếm thêm đồ ăn.");
            case "TIRED" -> pick(r, "Mệt rã rời, đi ngủ đây.", "Buồn ngủ quá...");
            case "LONELY" -> pick(r, "Hơi cô đơn...", "Ước gì có ai nói chuyện cùng.");
            case "SAD" -> pick(r, "Hôm nay tớ hơi buồn.", "...");
            default -> pick(r, "Bình thường thôi.", "Một ngày như mọi ngày.");
        });
        String s = b.toString();
        return s.length() > 700 ? s.substring(0, 697) + "..." : s;
    }

    private static String activity(String action, int n) {
        String v = switch (action) {
            case "chặt cây" -> "chặt cây";
            case "đào đá" -> "đào đá";
            case "đào mỏ" -> "xuống mỏ";
            case "đi săn" -> "đi săn";
            case "nung" -> "nhóm lò";
            case "chế tạo" -> "chế đồ";
            case "khám phá" -> "đi dạo";
            case "trồng cây" -> "trồng cây";
            case "thu hoạch" -> "ra ruộng";
            case "chăn nuôi" -> "chăm thú";
            case "xây nhà", "sửa nhà" -> "sửa sang nhà cửa";
            case "thư giãn" -> "ngồi thuyền ngắm trời";
            default -> action;
        };
        return n > 2 ? v + " " + n + " lần" : v;
    }

    private static String episode(MindData.Episode e) {
        String who = e.who != null ? e.who : "ai đó";
        return switch (e.type) {
            case "GIFT" -> who + " tặng tớ " + (e.what != null ? e.what : "quà") + ".";
            case "FED" -> who + " cho tớ ăn" + (e.what != null ? " " + e.what : "") + ".";
            case "SAVED" -> who + " cứu tớ khỏi " + (e.what != null ? e.what : "quái") + "!";
            case "NAMED" -> who + " đặt tên cho tớ là " + e.what + ".";
            case "HURT_BY_MOB" -> "Bị " + (e.what != null ? e.what : "quái") + " tấn công, đau ghê.";
            case "FAINTED" -> "Tớ bị ngất...";
            case "KILLED_MOB" -> "Hạ được một con " + (e.what != null ? e.what : "quái") + ".";
            case "HIT_BY_PLAYER" -> who + " đánh tớ. Giận!";
            case "INSULTED" -> who + " mắng tớ...";
            case "BUILT" -> "Làm xong " + (e.what != null ? e.what : "một thứ") + "!";
            case "PRAISED" -> who + " khen tớ.";
            default -> null;
        };
    }

    private static String pick(net.minecraft.util.RandomSource r, String... s) {
        return s[r.nextInt(s.length)];
    }

    /** Thay cuốn nhật ký trong rương nhà bằng bản mới nhất. */
    private static void storeBook(LyMeowzEntity ly, ServerLevel level, MindData m) {
        BlockPos chest = ly.structure("chest");
        if (chest == null) return;
        BlockState st = level.getBlockState(chest);
        if (!(st.getBlock() instanceof ChestBlock cb)) return;
        Container c = ChestBlock.getContainer(cb, st, level, chest, true);
        if (c == null) return;
        ItemStack book = book(ly, m);
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            WrittenBookContent w = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (s.is(Items.WRITTEN_BOOK) && w != null && TITLE.equals(w.title().raw())) { c.setItem(i, book); c.setChanged(); return; }
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).isEmpty()) { c.setItem(i, book); c.setChanged(); return; }
        }
    }

    public static ItemStack book(LyMeowzEntity ly, MindData m) {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String p : m.diary) pages.add(Filterable.passThrough(Component.literal(p)));
        String author = ly.displayName() != null ? ly.displayName() : "Cô gái lạ";
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(TITLE), author, 0, pages, true));
        return book;
    }

    /** Một trang nhật ký để kể (khi được hỏi "nhật ký viết gì"). */
    public static String lastPage(MindData m) {
        return m.diary.isEmpty() ? null : m.diary.get(m.diary.size() - 1).replace("\n\n", ": ");
    }
}
