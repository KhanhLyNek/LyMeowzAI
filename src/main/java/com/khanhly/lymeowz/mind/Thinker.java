package com.khanhly.lymeowz.mind;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.speech.Speech;
import com.khanhly.lymeowz.survival.LyInventory;
import com.khanhly.lymeowz.survival.Roadmap;
import com.khanhly.lymeowz.survival.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tư duy của cô ấy: tầng nằm trên bảng chấm điểm của {@link Decider}.
 * <p>
 * Decider chỉ trả lời câu "lúc này làm được những gì, mỗi việc cần cỡ nào". Thinker mới là chỗ cô ấy <i>quyết định</i>:
 * <ul>
 *   <li><b>Mong muốn (ý định):</b> nhìn lại hoàn cảnh (đồ ăn còn bao nhiêu, nhà xây tới đâu, đồ nghề, trời sắp tối,
 *       tâm trạng, tính cách) để biết mình đang muốn lo chuyện gì nhất. Một khi đã quyết lo chuyện gì thì theo đuổi nó
 *       cho tới khi xong hoặc có chuyện khác quan trọng hơn hẳn (không đổi ý liên tục).</li>
 *   <li><b>Kế hoạch trong ngày:</b> sáng ra tự sắp xếp hôm nay lo gì trước, gì sau.</li>
 *   <li><b>Cân nhắc khi chọn việc:</b> việc phục vụ điều mình đang muốn thì được ưu tiên; làm mãi một việc thì chán,
 *       muốn đổi gió; việc lần trước làm thấy vui thì thích làm lại; tâm trạng thoải mái thì tùy hứng hơn, gấp gáp
 *       thì làm đúng việc cần nhất.</li>
 *   <li><b>Suy nghĩ thành lời:</b> mỗi lựa chọn có lý do; có bạn ở gần thì thỉnh thoảng cô ấy nghĩ thành tiếng.
 *       Hỏi "đang nghĩ gì?", "sao lại làm thế?", "hôm nay định làm gì?" thì cô ấy kể.</li>
 * </ul>
 * Rẻ: suy ngẫm mỗi ~30 giây, chọn việc chỉ khi xong việc cũ.
 */
public final class Thinker {

    /** Những điều cô ấy có thể mong muốn, và những việc giúp đạt được điều đó. */
    public enum Aim {
        FOOD("lo cái ăn", "để dành ít đồ ăn",
                Set.of("đi săn", "câu cá", "hái quả", "làm ruộng", "thu hoạch", "chăn nuôi", "làm chuồng", "nung", "cất đồ")),
        SHELTER("lo chỗ ở", "có một mái nhà đàng hoàng",
                Set.of("tìm đất làm nhà", "xây nhà", "chặt cây", "đào đá", "đào mỏ", "nung", "xúc cát", "sửa nhà", "đặt giường")),
        TOOLS("làm đồ nghề", "có đồ nghề tốt hơn",
                Set.of("chế tạo", "đào mỏ", "đào đá", "nung", "chặt cây")),
        EXPLORE("đi khám phá", "đi xem quanh đây có gì",
                Set.of("khám phá", "hái hoa", "hái quả", "câu cá")),
        COSY("làm đẹp chỗ ở", "cho nhà cửa xinh xắn, gọn gàng",
                Set.of("trang trí nhà", "trồng cây", "hái hoa", "thắp đuốc", "cất đồ", "thu bàn chế tạo", "sửa nhà")),
        REST("nghỉ ngơi", "nghỉ cho lại sức",
                Set.of("nghỉ", "thư giãn")),
        FRIEND("ở cạnh bạn", "ở gần bạn một chút",
                Set.of("trò chuyện", "nghỉ")),
        SAFETY("về chỗ an toàn", "về nhà trước khi trời tối",
                Set.of("về nhà", "thắp đuốc")),
        ADVENTURE("chuẩn bị đi Nether", "đi tới những vùng đất xa hơn",
                Set.of("làm obsidian", "dựng cổng Nether", "đào sỏi", "đào mỏ"));

        public final String title, wish;
        public final Set<String> actions;

        Aim(String title, String wish, Set<String> actions) {
            this.title = title;
            this.wish = wish;
            this.actions = actions;
        }
    }

    /** Một suy nghĩ đã có (để kể lại khi được hỏi). */
    public record Thought(long time, String text) {}

    private final LyMeowzEntity ly;
    private final Map<Aim, Float> want = new EnumMap<>(Aim.class);
    private final Map<Aim, String> why = new EnumMap<>(Aim.class);
    private Aim focus, second;
    private long focusSince, nextReflect, planDay = Long.MIN_VALUE, lastAloud = -100000;
    private String plan, lastWhy, lastAction;
    private final Deque<String> recent = new ArrayDeque<>();      // những việc vừa chọn gần đây
    private final Deque<Thought> thoughts = new ArrayDeque<>();   // vài suy nghĩ gần nhất
    private float whimSeed;
    private long whimUntil;

    public Thinker(LyMeowzEntity ly) {
        this.ly = ly;
    }

    // ================================================================== suy ngẫm

    /** Gọi mỗi giây. Cứ ~30 giây (hoặc khi có chuyện) thì nhìn lại hoàn cảnh, xem mình đang muốn gì. */
    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        long day = level.getOverworldClockTime() / 24000L;
        long tod = level.getOverworldClockTime() % 24000L;
        if (day != planDay && tod < 2000) { reflect(level); makePlan(level, day); return; }
        if (now >= nextReflect) reflect(level);
    }

    /** Có chuyện bất ngờ (bị đánh, việc thất bại, gặp bạn...): nghĩ lại sớm. */
    public void nudge() {
        nextReflect = 0;
    }

    private void reflect(ServerLevel level) {
        long now = level.getGameTime();
        nextReflect = now + 20 * (25 + ly.getRandom().nextInt(15));
        MindData m = ly.mind().data();
        Personality p = ly.mind().p();
        LyInventory all = ly.inventoryWithHand();
        Roadmap.Stage stage = Roadmap.current(ly);
        want.clear();
        why.clear();

        // Cái ăn: đếm đồ ăn ngon trong túi (+ rương ở nhà) so với vài bữa cần.
        int food = all.count(Tools::isGoodFood) + all.count(Tools::isRawMeat) / 2;
        float foodNeed = clamp(1f - food / 10f) * 0.6f + clamp((14 - ly.getHunger()) / 14f) * 0.4f;
        put(Aim.FOOD, foodNeed * (0.8f + 0.3f * p.diligent),
                food == 0 ? "trong túi chẳng còn gì ăn" : food < 4 ? "đồ ăn còn có " + food + " miếng" : "để dành thêm đồ ăn");

        // Chỗ ở.
        boolean home = ly.builtHomeInside() != null;
        var d = ly.data();
        if (!home && stage.ordinal() >= Roadmap.Stage.SHELTER.ordinal()) {
            String r = d != null && d.blueprint != null && d.blueprintAt != null ? "nhà mới đang xây dở" : "mình chưa có nhà";
            // Đã sống tạm được một thời gian (lộ trình đã sang làm ruộng, chuồng...) thì vẫn lo nhà nhưng không gấp bằng lúc đầu.
            boolean settled = stage.ordinal() > Roadmap.Stage.SHELTER.ordinal();
            put(Aim.SHELTER, settled ? 0.4f + 0.2f * p.builder : 0.55f + 0.3f * p.builder, r);
        } else if (d != null && d.blueprint != null) {
            put(Aim.SHELTER, 0.35f + 0.25f * p.builder, "đang tích đồ xây căn nhà mới");
        }

        // Đồ nghề: còn bước nâng cấp nào trong lộ trình thì muốn làm.
        if (stage.ordinal() <= Roadmap.Stage.DIAMOND.ordinal() && stage != Roadmap.Stage.SHELTER)
            put(Aim.TOOLS, 0.35f + 0.25f * p.diligent, "đồ nghề còn yếu, muốn " + stage.title);
        if (stage.ordinal() >= Roadmap.Stage.NETHER.ordinal())
            put(Aim.ADVENTURE, 0.3f + 0.35f * p.curious + 0.15f * p.brave, "muốn xem Nether ra sao");

        // Khám phá: tò mò + chán.
        put(Aim.EXPLORE, (m.curiosity / 100f) * (0.3f + 0.4f * p.curious) + (m.boredom / 100f) * 0.25f,
                m.boredom > 60 ? "ở mãi một chỗ chán quá" : "tò mò không biết phía xa có gì");

        // Nhà đã có: làm đẹp.
        if (home) put(Aim.COSY, 0.2f + 0.3f * p.builder + (all.isFull() ? 0.2f : 0), all.isFull() ? "túi đầy ứ rồi, phải sắp xếp" : "nhà mình phải xinh chứ");

        // Nghỉ: mệt, buồn.
        put(Aim.REST, clamp((60 - m.energy) / 60f) * 0.8f + clamp((40 - m.joy) / 40f) * 0.2f,
                m.energy < 40 ? "mệt rã rời" : "hơi uể oải");

        // Bạn bè: cô đơn mà bạn đang ở gần.
        ServerPlayer friend = ly.nearestPlayer(level, 24);
        if (friend != null && ly.affinity(friend) >= LyMeowzEntity.FRIEND)
            put(Aim.FRIEND, clamp((70 - m.social) / 70f) * (0.4f + 0.5f * p.social), "nhớ " + new Speech.Voice(ly, friend).you + " quá");

        // An toàn: chiều tà mà đang ở xa nhà.
        long tod = level.getOverworldClockTime() % 24000L;
        BlockPos inside = ly.builtHomeInside();
        if (inside != null && tod > 10800 && tod < 13000 && inside.distSqr(ly.blockPosition()) > 40 * 40)
            put(Aim.SAFETY, 0.6f + 0.3f * (1 - p.brave), "trời sắp tối mà mình còn ở xa nhà");

        // Chọn điều muốn lo nhất. Đang theo đuổi điều gì thì giữ, trừ khi có chuyện khác cần hơn hẳn.
        Aim best = null, next = null;
        for (var e : want.entrySet()) {
            float v = e.getValue() + (e.getKey() == focus ? 0.15f : 0); // cam kết: đã quyết thì theo
            if (best == null || v > want.get(best) + (best == focus ? 0.15f : 0)) { next = best; best = e.getKey(); }
            else if (next == null || v > want.get(next)) next = e.getKey();
        }
        if (best != focus && best != null) {
            Aim old = focus;
            focus = best;
            focusSince = now;
            think(level, old == null
                    ? "Giờ mình muốn " + best.wish + ", vì " + why.get(best) + "."
                    : "Thôi, " + old.title + " để sau. Giờ phải " + best.title + " đã, vì " + why.get(best) + ".");
        }
        second = next;
        // Đêm nằm chờ sáng: nghĩ vẩn vơ về ngày mai.
        if (tod > 13500 && tod < 22500 && focus != null && ly.getRandom().nextFloat() < 0.25f) {
            Speech.Voice v = new Speech.Voice(ly, null);
            think(level, v.pick("Đêm nay yên tĩnh ghê... ", "Ngoài kia tối om... ", "Chưa buồn ngủ lắm... ", "Nghe tiếng quái rên xa xa... ")
                    + v.pick("mai phải " + focus.title + " tiếp mới được.", "mai dậy sớm " + focus.title + ".", "không biết mai có kịp " + focus.title + " không ta."));
        }
    }

    private void makePlan(ServerLevel level, long day) {
        planDay = day;
        List<Aim> order = new ArrayList<>(want.keySet());
        order.remove(Aim.SAFETY);
        order.remove(Aim.REST);
        order.sort((a, b) -> Float.compare(want.get(b), want.get(a)));
        if (order.isEmpty()) { plan = "Hôm nay chẳng có gì gấp, cứ thong thả thôi."; return; }
        StringBuilder b = new StringBuilder("Hôm nay ");
        b.append("trước hết ").append(order.get(0).title).append(" (").append(why.get(order.get(0))).append(")");
        if (order.size() > 1) b.append(", rồi ").append(order.get(1).title);
        if (order.size() > 2) b.append(", còn thời gian thì ").append(order.get(2).title);
        b.append(".");
        plan = b.toString();
        think(level, plan);
        askLlmPlan(level, order);
    }

    /** Có LLM thì nhờ nó giúp cô ấy tự nghĩ: chọn điều muốn lo nhất hôm nay và nói kế hoạch theo giọng của mình. */
    private void askLlmPlan(ServerLevel level, List<Aim> order) {
        var cfg = com.khanhly.lymeowz.config.LyConfig.get().llm;
        if (!cfg.enabled || !cfg.thinking || order.isEmpty()) return;
        MindData m = ly.mind().data();
        StringBuilder s = new StringBuilder("Sáng rồi. Tình hình của bạn: đói " + ly.getHunger() + "/20, năng lượng " + (int) m.energy
                + ", vui " + (int) m.joy + ", chán " + (int) m.boredom + ", tò mò " + (int) m.curiosity
                + ", túi đồ: " + com.khanhly.lymeowz.survival.Survival.summary(ly.inventoryWithHand())
                + ". Giai đoạn: " + Roadmap.current(ly).title + ". Những điều bạn đang muốn lo:");
        for (Aim a : order) s.append(" [").append(a.name()).append("] ").append(a.title).append(" (").append(why.get(a)).append(");");
        s.append(" Hãy tự quyết hôm nay lo gì trước. Bắt đầu câu trả lời bằng đúng MỘT thẻ trong các thẻ trên, rồi một câu ")
                .append("ngắn (tối đa 160 ký tự) như đang tự nhủ kế hoạch hôm nay, xưng \"mình\".");
        var server = level.getServer();
        com.khanhly.lymeowz.chat.LlmClient.think(s.toString()).whenComplete((text, err) -> server.execute(() -> {
            if (ly.isRemoved() || err != null || text == null || text.isBlank()) return;
            var mt = java.util.regex.Pattern.compile("\\[([A-Z]+)]").matcher(text);
            Aim pick = null;
            if (mt.find()) try { pick = Aim.valueOf(mt.group(1)); } catch (IllegalArgumentException ignored) {}
            String said = text.replaceAll("\\[[A-Z]+]", "").trim();
            if (said.length() > 200) said = said.substring(0, 200);
            if (pick != null && want.containsKey(pick)) { focus = pick; focusSince = level.getGameTime(); }
            if (!said.isEmpty()) { plan = said; think(level, said); }
        }));
    }

    // ================================================================== cân nhắc chọn việc

    /**
     * Chọn việc tiếp theo trong các lựa chọn Decider đưa ra. Không phải cứ điểm cao nhất là làm: cân nhắc điều
     * mình đang muốn, chán hay không, lần trước làm có vui không, và tâm trạng (thoải mái thì tùy hứng hơn).
     */
    public Decider.Option choose(ServerLevel level, List<Decider.Option> options) {
        if (options.isEmpty()) return null;
        // Chuyện gấp (chạy trốn, đói lả) thì không cần nghĩ nhiều.
        Decider.Option urgent = null;
        for (Decider.Option o : options) if (o.urgent() && (urgent == null || o.score() > urgent.score())) urgent = o;
        if (urgent != null && urgent.score() >= 0.85f) return remember(level, urgent, null, 0);

        MindData m = ly.mind().data();
        Personality p = ly.mind().p();
        long now = level.getGameTime();
        if (now >= whimUntil) { whimSeed = ly.getRandom().nextFloat() * 1000; whimUntil = now + 20 * 120; }

        float[] u = new float[options.size()];
        String[] note = new String[options.size()];
        float max = -9;
        for (int i = 0; i < options.size(); i++) {
            Decider.Option o = options.get(i);
            float v = o.score();
            String n = null;
            if (focus != null && focus.actions.contains(o.action())) {
                v += 0.12f + 0.08f * p.diligent;
                n = "đang muốn " + focus.title;
            } else if (second != null && second.actions.contains(o.action())) {
                v += 0.06f;
            }
            int times = 0;
            for (String r : recent) if (r.equals(o.action())) times++;
            if (times >= 2 && !o.urgent()) { v -= 0.04f * (times - 1) * (0.5f + m.boredom / 100f); if (n == null) n = null; }
            if (times == 0 && recent.size() >= 4 && m.boredom > 50) { v += 0.04f; if (n == null) n = "đổi gió chút"; }
            MindData.Stat st = m.learned.get(o.action());
            if (st != null) v += clampSigned(st.reward) * 0.06f;           // lần trước làm thấy vui / chán
            // Tùy hứng: mỗi vài phút một "cơn hứng" khác nhau, người tò mò, vui vẻ thì hứng nhiều hơn.
            float whim = (float) Math.sin(whimSeed + o.action().hashCode() * 0.37) * (0.02f + 0.04f * p.curious) * (0.5f + m.joy / 100f);
            v += whim;
            u[i] = v;
            note[i] = n;
            if (v > max) max = v;
        }
        // Thoải mái thì chọn tùy hứng giữa các việc gần bằng nhau; căng thẳng thì làm đúng việc cần nhất.
        float relaxed = clamp(m.joy / 100f) * clamp(1 - m.fear / 60f) * (ly.getHunger() > 12 ? 1 : 0.3f);
        float temp = 0.015f + 0.05f * relaxed;
        double total = 0;
        double[] w = new double[u.length];
        for (int i = 0; i < u.length; i++) {
            if (u[i] < max - 0.2f) continue;
            w[i] = Math.exp((u[i] - max) / temp);
            total += w[i];
        }
        double r = ly.getRandom().nextDouble() * total;
        int pick = 0;
        for (int i = 0; i < u.length; i++) {
            if (w[i] == 0) continue;
            r -= w[i];
            if (r <= 0) { pick = i; break; }
        }
        return remember(level, options.get(pick), note[pick], u[pick] - max);
    }

    private Decider.Option remember(ServerLevel level, Decider.Option o, String note, float behind) {
        String a = o.action();
        if (!Set.of("nhặt đồ", "ăn").contains(a)) {
            recent.addLast(a);
            while (recent.size() > 8) recent.removeFirst();
        }
        // Lý do bằng lời.
        Speech.Voice v = new Speech.Voice(ly, null);
        String base = Speech.reason(v, o.reason());
        StringBuilder why = new StringBuilder();
        if (base != null) why.append(base);
        if (note != null) why.append(why.length() > 0 ? ", mà mình cũng " : "").append(note);
        if (behind < -0.05f && why.length() == 0) why.append(v.pick("tự dưng thấy thích", "tự nhiên muốn vậy", "hứng lên thôi"));
        lastWhy = why.length() > 0 ? why.toString() : null;
        boolean changed = !a.equals(lastAction);
        lastAction = a;
        if (changed && !Set.of("nhặt đồ", "ăn", "chạy trốn").contains(a)) {
            String thought = (lastWhy != null ? cap(lastWhy) + "... " : "") + v.pick("thôi ", "", "để mình ") + Speech.verb(v, a) + v.pick(".", " đã.", " nào.");
            thoughtOnly(level, thought);
            maybeAloud(level, thought);
        }
        return o;
    }

    /** Có bạn bè ở gần thì thỉnh thoảng nghĩ thành tiếng (người hay nói thì nói nhiều hơn). */
    private void maybeAloud(ServerLevel level, String text) {
        long now = level.getGameTime();
        Personality p = ly.mind().p();
        if (now - lastAloud < 20L * 60 * (3 - 2 * p.talkative)) return;
        ServerPlayer friend = ly.nearestPlayer(level, 14);
        if (friend == null || ly.affinity(friend) < LyMeowzEntity.FRIEND || ly.getTarget() != null) return;
        if (ly.getRandom().nextFloat() > 0.25f + 0.45f * p.talkative) return;
        lastAloud = now;
        ly.sayRaw(text);
    }

    private void think(ServerLevel level, String text) {
        thoughtOnly(level, text);
        maybeAloud(level, text);
    }

    private void thoughtOnly(ServerLevel level, String text) {
        if (com.khanhly.lymeowz.command.SurvivalTest.running()) com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[survival]     💭 {}", text);
        thoughts.addLast(new Thought(level.getGameTime(), text));
        while (thoughts.size() > 8) thoughts.removeFirst();
    }

    // ================================================================== học từ trải nghiệm

    /** Xong một việc: thấy vui hay chán (dựa vào tâm trạng thay đổi + thành công hay không). */
    public void outcome(String action, boolean ok, float joyDelta) {
        MindData.Stat st = ly.mind().data().learned.computeIfAbsent(action, k -> new MindData.Stat());
        float r = (ok ? 0.3f : -0.4f) + clampSigned(joyDelta / 15f) * 0.7f;
        st.reward = st.reward * 0.8f + r * 0.2f;
        if (!ok) nudge();
    }

    // ================================================================== kể lại

    public Aim focus() { return focus; }

    /** "Đang nghĩ gì?" */
    public String currentThought() {
        if (thoughts.isEmpty()) return null;
        return thoughts.peekLast().text();
    }

    /** "Sao lại làm thế?" */
    public String explainCurrent(String doing) {
        if (lastWhy == null && focus == null) return null;
        StringBuilder b = new StringBuilder();
        if (lastWhy != null) b.append(cap(lastWhy));
        if (focus != null && (lastWhy == null || !lastWhy.contains(focus.title)))
            b.append(b.length() > 0 ? ". Mà dạo này mình đang muốn " : "Dạo này mình đang muốn ").append(focus.wish);
        if (focus != null && why.containsKey(focus)) b.append(b.length() > 0 ? ", " : "").append("vì ").append(why.get(focus));
        return b.append(".").toString();
    }

    /** "Hôm nay định làm gì?" */
    public String plan() {
        return plan;
    }

    // ================================================================== tiện ích

    private void put(Aim a, float v, String reason) {
        if (v <= 0.05f) return;
        want.put(a, v);
        why.put(a, reason);
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    private static float clampSigned(float v) { return Math.max(-1, Math.min(1, v)); }
    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
