package com.khanhly.lymeowz.speech;

import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.mind.MindData;
import com.khanhly.lymeowz.mind.Personality;
import com.khanhly.lymeowz.survival.ItemNames;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * Tự ghép câu nói, không dùng câu viết sẵn và không cần mạng.
 * <p>
 * Mỗi câu được dựng từ: <b>ý muốn nói</b> (hành động, lý do, đồ vật, nơi chốn, chuyện đã nhớ)
 * + <b>cách xưng hô</b> theo độ thân thiết + <b>từ mở đầu / tiểu từ cuối câu</b> theo cảm xúc
 * + <b>mức độ</b> (hơi/khá/rất/siêu) theo cường độ cảm xúc + độ dài theo tính "nói nhiều".
 * "Mugyu~" là câu cửa miệng khi bối rối hoặc ngại.
 */
public final class Speech {
    private Speech() {}

    public enum Act {
        FIRST_MEET, GREET, WELCOME_BACK, START, DONE, FAILED, CRAFTED, DISCOVERY, FEELING, SMALLTALK,
        THANKS, GIFT_THANKS, PRAISED, INSULTED, CONFUSED, HURT_BY_OWNER, FAINT, FOUND_YOU, SEARCHING,
        NIGHT, ACK, REFUSE, OWNER_SLEEP, GO_HOME, ARRIVED_HOME, HOME_SET, STRANGER, HUNGRY, GIVE, GIVE_NOTHING,
        FIGHT, SCARED, BYE, NOT_CLOSE, WAITING, MET_AT_SPOT, NAMED, SAVED_THANKS, SHY, RELEASED, PLAN
    }

    /** Nội dung cần nói. */
    public static final class Ctx {
        public String action;      // chặt cây, đào đá, ...
        public String reason;      // WOOD_LOW, HUNGRY, BORED, ...
        public Item item;
        public int count;
        public Mind.PlaceKind place;
        public BlockPos pos;
        public @Nullable Player listener;
        public String mob;
        public String text;

        public static Ctx of() { return new Ctx(); }
        public Ctx action(String a) { action = a; return this; }
        public Ctx reason(String r) { reason = r; return this; }
        public Ctx item(Item i, int n) { item = i; count = n; return this; }
        public Ctx place(Mind.PlaceKind k, BlockPos p) { place = k; pos = p; return this; }
        public Ctx to(@Nullable Player p) { listener = p; return this; }
        public Ctx mob(String m) { mob = m; return this; }
        public Ctx text(String t) { text = t; return this; }
    }

    // ------------------------------------------------------------------ giọng nói

    /** Xưng hô + tâm trạng cho một lần nói. */
    public static final class Voice {
        final RandomSource r;
        final Personality p;
        final MindData m;
        public final String self, you, name;
        public final float affinity;
        public final String mood;

        public Voice(LyMeowzEntity ly, @Nullable Player listener) {
            r = ly.getRandom();
            m = ly.mind().data();
            p = m.personality;
            affinity = listener == null ? 0 : ly.mind().affinity(listener.getUUID());
            boolean close = affinity > 60;
            self = affinity < -30 ? "tôi" : (close && p.cheerful > 0.6f && r.nextInt(3) == 0 ? "tớ" : "mình");
            you = affinity < 40 ? "bạn" : "cậu";
            name = listener == null ? null : listener.getName().getString();
            mood = ly.mind().dominantFeeling();
        }

        public boolean chance(float pr) { return r.nextFloat() < pr; }

        public String pick(String... options) { return options[r.nextInt(options.length)]; }

        /** "hơi" / "khá" / "rất" / "siêu" theo giá trị 0–100. */
        String degree(float v) {
            if (v > 88) return pick("siêu", "cực kỳ", "quá trời");
            if (v > 72) return pick("rất", "thật sự", "cực");
            if (v > 55) return pick("khá", "cũng");
            return pick("hơi", "có chút");
        }

        String opener() {
            if (!chance(0.15f + 0.25f * p.talkative) || affinity < 15) return "";
            return switch (mood) {
                case "JOY" -> pick("Oa", "Hehe", "Yay", "A ha", "Hihi");
                case "FEAR" -> pick("Ơ...", "Á", "Hức", "U-ưm");
                case "BORED" -> pick("Haizz", "Ừm...", "Haa");
                case "CURIOUS" -> pick("Ơ kìa", "Hửm?", "Này này");
                case "SAD" -> pick("Ừ...", "Haa...", "Ưm");
                case "HUNGRY" -> pick("Ục ục...", "Ưm...");
                case "TIRED" -> pick("Phù...", "Oáp...");
                case "LONELY" -> pick("Ưm...", "À...");
                default -> "";
            };
        }

        public String ending() {
            String e = switch (mood) {
                case "JOY" -> pick("~", " nè~", " nha~", "!", " đó~");
                case "FEAR" -> pick("...", " đó...", "!");
                case "SAD", "TIRED", "LONELY" -> pick("...", " nha...", ".");
                case "BORED" -> pick("...", " ha.", ".");
                case "CURIOUS" -> pick("!", " nè!", "~");
                default -> pick(" nhé", " nha", ".", "~", " nè");
            };
            if (affinity < -30) e = pick(".", "...");
            if (mood.equals("JOY") && chance(0.15f * p.cheerful)) e += pick(" (≧▽≦)", " ♪", " (*^▽^*)");
            return e;
        }

        String addressYou() {
            return name != null && chance(0.15f) && affinity > 0 ? name : you;
        }
    }

    // ------------------------------------------------------------------ dựng câu

    public static String build(LyMeowzEntity ly, Act act, Ctx c) {
        Voice v = new Voice(ly, c.listener != null ? c.listener : ly.focusPlayer());
        String core = core(ly, v, act, c);
        if (core == null || core.isEmpty()) return "";
        String open = act == Act.CONFUSED || act == Act.PRAISED ? "" : v.opener();
        StringBuilder s = new StringBuilder();
        if (!open.isEmpty()) {
            s.append(open).append(open.endsWith("?") || open.endsWith("...") ? " " : ", ");
            // Sau từ mở đầu thì viết thường (trừ tên riêng).
            String nm = ly.displayName();
            boolean proper = (nm != null && core.startsWith(nm)) || (v.name != null && core.startsWith(v.name));
            if (!proper && open.endsWith(",") == false && !open.endsWith("?") && !open.endsWith("..."))
                core = Character.toLowerCase(core.charAt(0)) + core.substring(1);
        }
        s.append(core);
        if (!endsWithPunct(core)) {
            if (act == Act.CONFUSED) s.append(v.pick("?", "...", " ạ?"));
            else if (act == Act.PRAISED || act == Act.INSULTED || act == Act.FAINT) s.append(v.pick("...", "!"));
            else s.append(v.ending());
        }
        // Nói nhiều thì thêm một câu kèm theo (cảm xúc hoặc hỏi lại).
        boolean somber = switch (act) {
            case CONFUSED, PRAISED, INSULTED, REFUSE, FAILED, FAINT, HURT_BY_OWNER, SCARED, HUNGRY, GIVE_NOTHING, BYE, STRANGER -> true;
            default -> false;
        };
        if (!somber && v.affinity >= 40 && v.chance(0.12f * v.p.talkative)) {
            String extra = afterthought(ly, v, act);
            if (extra != null) s.append(" ").append(extra);
        }
        return capitalize(s.toString().replace("  ", " ").replace(" ,", ","));
    }

    private static String core(LyMeowzEntity ly, Voice v, Act act, Ctx c) {
        String me = v.self, you = v.addressYou(), name = ly.displayName();
        return switch (act) {
            case FIRST_MEET -> v.pick("A... chào", "Ơ... bạn là ai", "...Chào bạn") + (name != null ? ". " + cap(me) + " là " + name : "");
            case GREET -> v.pick("Chào " + you, "Hi " + you, "A, " + you + " gọi " + me + " hả", cap(me) + " đây")
                    + (v.chance(0.5f) ? ". " + whatNow(ly, v) : "");
            case WELCOME_BACK -> v.pick(cap(you) + " về rồi", "A! " + cap(you) + " quay lại rồi", "Tìm thấy " + you + " rồi")
                    + ". " + v.pick(cap(me) + " đợi mãi đó", cap(me) + " nhớ " + you + " " + v.degree(v.m.social < 40 ? 85 : 60) + " luôn",
                    "Đừng bỏ " + me + " lại lâu vậy nữa") + (v.m.social < 40 ? "... Mugyu~" : "");
            case START -> startSentence(v, c);
            case DONE -> doneSentence(v, c);
            case FAILED -> "Mugyu~ " + me + " " + v.pick("không " + verb(v, c.action) + " được", "làm hỏng mất rồi", "chịu thua chỗ này")
                    + (c.text != null ? ", " + c.text : "") + ". " + v.pick("Lần sau " + me + " thử chỗ khác", cap(me) + " sẽ nhớ chỗ này");
            case CRAFTED -> v.pick("Làm xong " + ItemNames.of(c.item) + " rồi", "Có " + ItemNames.of(c.item) + " mới rồi",
                    cap(me) + " vừa chế được " + ItemNames.of(c.item)) + (upgradeRemark(v, c.item));
            case DISCOVERY -> discovery(ly, v, c);
            case FEELING -> feeling(ly, v);
            case SMALLTALK -> smalltalk(ly, v);
            case THANKS -> v.pick("Không có gì đâu", "Hehe, chuyện nhỏ mà", "Mugyu~ " + you + " khách sáo quá à");
            case GIFT_THANKS -> v.pick("Cho " + me + " hả? Cảm ơn " + you, cap(you) + " tốt với " + me + " ghê",
                    "Oa, " + (c.item != null ? ItemNames.of(c.item) : "quà") + "! Cảm ơn " + you)
                    + (c.item != null && v.chance(0.6f) ? ". " + cap(me) + " cất vào túi rồi" : "");
            case PRAISED -> v.pick("M-Mugyu~! ", "Mugyu~... ") + v.pick("Đ-đừng nói vậy chứ", "Mặt " + me + " nóng hết lên rồi",
                    cap(you) + " nói gì vậy chứ", cap(me) + " ngại quá à") + (v.affinity > 60 ? "... " + v.pick("nhưng " + me + " vui lắm", "cảm ơn " + you) : "");
            case INSULTED -> v.affinity > 40
                    ? v.pick("Sao " + you + " nói vậy", cap(me) + " làm gì sai hả", "Hức... " + me + " buồn đó") + "..."
                    : v.pick(cap(me) + " không thích bị nói vậy", "Hừm", "Thôi được rồi") + ".";
            case CONFUSED -> "Mugyu~? " + v.pick(cap(me) + " không hiểu lắm", cap(you) + " nói lại được không", "Cái đó là gì vậy",
                    "Đầu " + me + " xoay vòng vòng rồi", cap(me) + " chưa nghe cái đó bao giờ");
            case HURT_BY_OWNER -> v.pick("Mugyu~! Đau đó nha", "Á! " + cap(me) + " làm gì sai hả", "Sao " + you + " đánh " + me);
            case FAINT -> v.pick(cap(me) + " mệt quá", "Ui da", "Mugyu~...") + "... " + me + " về nhà nghỉ chút, "
                    + v.pick("đến đón " + me + " nha", you + " cẩn thận đó");
            case FOUND_YOU -> v.pick("Tìm thấy " + you + " rồi", "Phù... theo kịp rồi", "A, " + you + " đây rồi");
            case SEARCHING -> v.pick(cap(you) + " đâu rồi", cap(you) + " đi đâu mất rồi", "Đợi " + me + " với") + "... " + v.pick("để " + me + " tìm", me + " tới liền");
            case NIGHT -> v.pick("Trời tối rồi", "Đêm xuống rồi") + ", " + (v.p.brave < 0.4f
                    ? v.pick(me + " " + v.degree(60 + (1 - v.p.brave) * 30) + " sợ", "ở gần " + me + " nhé")
                    : v.pick("cẩn thận zombie đó", me + " sẽ canh cho " + you));
            case ACK -> ack(v, c.text);
            case REFUSE -> v.pick(cap(me) + " không muốn đâu", "Thôi, " + me + " không làm", "Không đâu") + (v.affinity < -30 ? "." : "... Mugyu~");
            case OWNER_SLEEP -> v.pick(cap(you) + " đi ngủ hả", "Ngủ ngon nha") + "? " + v.pick(cap(me) + " cũng về nhà đây", "Mai gặp lại");
            case GO_HOME -> v.pick(cap(me) + " về nhà trước nha", "Về nhà thôi", cap(me) + " về nhà đợi " + you);
            case ARRIVED_HOME -> v.pick("Về tới nhà rồi", "Nhà mình đây rồi") + (v.p.builder > 0.6f ? ", " + v.pick("ấm áp ghê", "phải sửa sang thêm mới được") : "");
            case HOME_SET -> v.pick("Đây là nhà mới của tụi mình hả", cap(me) + " nhớ chỗ này rồi", "Từ giờ đây là nhà nha")
                    + (v.p.builder > 0.6f ? ". " + cap(me) + " sẽ trồng thêm cây quanh đây" : "");
            case STRANGER -> v.pick("...?", "Ơ... bạn cần gì", "Mugyu~... " + me + " không quen bạn", "Ừm... chào");
            case SHY -> v.pick("...", "Mugyu~", "Ơ...", "Ừm...", "...Mugyu~");
            case NOT_CLOSE -> v.affinity < 0
                    ? v.pick("Không.", cap(me) + " không muốn.", "Đừng có ra lệnh cho " + me + ".")
                    : v.affinity < 15 ? v.pick("Mình còn chưa quen nhau mà", cap(me) + " đâu có biết bạn là ai", "Hả? Sao " + me + " phải nghe bạn") + "... Mugyu~"
                    : v.pick(cap(me) + " với bạn chưa thân tới mức đó đâu", "Để " + me + " suy nghĩ đã... chưa được đâu", "Hừm... từ từ đã, " + me + " còn đang bận việc của " + me);
            case WAITING -> v.pick(cap(me) + " đang ở ", "Ở ", "Toạ độ ") + c.text + (c.count > 0
                    ? ". " + v.pick(cap(me) + " đợi " + you + " ở đây", cap(you) + " tới đi, " + me + " đứng đợi", "Ừm, " + me + " sẽ ở yên đây đợi " + you)
                    : "");
            case MET_AT_SPOT -> v.pick(cap(you) + " tới rồi", "A, " + you + " đây rồi", "Lâu ghê đó") + v.pick("", ". " + cap(me) + " đợi nãy giờ", ". Đi thôi");
            case NAMED -> "M-Mugyu~! " + v.pick("Tên " + me + " là " + name + " hả", cap(name) + "... nghe cũng hay", "Từ giờ " + me + " là " + name + " à")
                    + v.pick("... cảm ơn " + you, "... " + me + " sẽ nhớ", "");
            case SAVED_THANKS -> v.pick("C-cảm ơn " + you + " đã cứu " + me, "Phù... may có " + you, cap(you) + " vừa cứu " + me + " đó hả");
            case RELEASED -> v.pick("Ừm, " + me + " đi làm việc của " + me + " đây", "Okie, lát gặp lại", cap(me) + " tự lo được mà");
            case PLAN -> c.text;
            case HUNGRY -> v.pick("Đói quá", "Bụng " + me + " kêu rồi", cap(me) + " " + v.degree(100 - ly.getHunger() * 5) + " đói")
                    + "... " + v.pick("Mugyu~", you + " có gì ăn không");
            case GIVE -> v.pick("Đây nè, cầm lấy", "Cho " + you + " hết nè", cap(me) + " đưa " + you + " đồ nè")
                    + (c.count > 0 ? " (" + c.count + " món)" : "");
            case GIVE_NOTHING -> "Mugyu~ " + v.pick(me + " chưa có gì để đưa " + you, "túi " + me + " toàn đồ nghề thôi à");
            case FIGHT -> v.p.brave > 0.5f ? v.pick("Tránh xa " + you + " ra", "Để " + me + " lo", "Hya") : v.pick("Đ-đừng lại gần", "Đi chỗ khác đi") + "!";
            case SCARED -> v.pick("Có " + (c.mob != null ? c.mob : "quái"), "Á, " + (c.mob != null ? c.mob : "quái") + " kìa")
                    + "! " + v.pick(cap(me) + " chạy đây", "Cứu " + me + " với", cap(you) + " ơi");
            case BYE -> v.pick("Tạm biệt " + you, "Gặp lại sau nha", cap(me) + " sẽ nhớ " + you);
        };
    }

    // ------------------------------------------------------------------ các mảnh câu

    static String verb(Voice v, String action) {
        if (action == null) return v.pick("làm", "làm việc");
        return switch (action) {
            case "chặt cây" -> v.pick("đi chặt cây", "đi đốn ít gỗ", "kiếm thêm gỗ", "chặt vài cây");
            case "đào đá" -> v.pick("đi đào đá", "đào ít khoáng", "đi kiếm đá", "đào mỏ một chút");
            case "đi săn" -> v.pick("đi săn", "đi kiếm thịt", "kiếm gì đó để ăn");
            case "khám phá" -> v.pick("đi dạo một vòng", "đi khám phá quanh đây", "đi xem bên kia có gì", "đi thám hiểm");
            case "nung" -> v.pick("nhóm lò nướng đồ", "nướng ít đồ ăn", "nung đồ");
            case "chế tạo" -> v.pick("chế đồ", "làm đồ mới");
            case "nghỉ" -> v.pick("nghỉ một chút", "ngồi nghỉ", "đứng nghỉ chân");
            case "trồng cây" -> v.pick("trồng mấy cây con", "trồng thêm cây", "làm vườn một chút");
            case "thắp đuốc" -> v.pick("cắm đuốc cho sáng", "thắp đuốc quanh đây");
            case "ăn" -> v.pick("ăn chút gì đó", "ăn một miếng");
            case "nhặt đồ" -> v.pick("nhặt đồ", "lượm đồ rơi");
            case "trò chuyện" -> v.pick("nói chuyện", "tám chuyện");
            case "chạy trốn" -> v.pick("chạy", "trốn");
            default -> action;
        };
    }

    static String reason(Voice v, String r) {
        if (r == null) return null;
        String me = v.self, you = v.you;
        return switch (r) {
            case "WOOD_LOW" -> v.pick("gỗ sắp hết rồi", "túi hết gỗ rồi", "cần thêm gỗ");
            case "STONE_LOW" -> v.pick("cần thêm đá", "hết đá rồi");
            case "ORE" -> v.pick("thấy có quặng", "muốn kiếm than với sắt");
            case "FOOD_LOW" -> v.pick("sắp hết đồ ăn rồi", "túi hết đồ ăn");
            case "HUNGRY" -> v.pick("bụng đói quá", "đói rồi", "bụng kêu ục ục");
            case "BORED" -> v.pick("cho đỡ chán", "đứng mãi chán quá", "chán quá à");
            case "CURIOUS" -> v.pick("tò mò quá", "muốn biết bên đó có gì");
            case "UPGRADE" -> v.pick("để có đồ tốt hơn", "đồ cũ yếu quá");
            case "SCARED" -> v.pick("hơi sợ", "trời tối quá");
            case "TIRED" -> v.pick("mệt quá", "mỏi chân rồi");
            case "LONELY" -> v.pick("nhớ " + you, "muốn ở gần " + you);
            case "RAW_FOOD" -> v.pick("có thịt sống nè", "thịt sống ăn đau bụng lắm");
            case "BUILDER" -> v.pick(me + " thích vậy", "cho chỗ này đẹp hơn");
            case "DARK" -> v.pick("tối quá dễ có quái", "cho sáng sủa");
            case "NIGHT" -> v.pick("trời tối rồi", "đêm rồi", "tối rồi ở gần " + you + " cho an toàn");
            case "IDLE" -> v.pick("hết việc rồi", "rảnh quá");
            default -> null;
        };
    }

    private static String startSentence(Voice v, Ctx c) {
        String me = v.self;
        String modal = v.pick("đi", "sẽ", "định", "muốn");
        String vp = verb(v, c.action);
        if (vp.startsWith("đi ") && modal.equals("đi")) modal = "";
        String main = cap(me) + " " + (modal.isEmpty() ? "" : modal + " ") + vp;
        String why = reason(v, c.reason);
        if (why == null) return main;
        return switch (v.r.nextInt(3)) {
            case 0 -> main + ", " + why;
            case 1 -> cap(why) + ", " + me + " " + vp;
            default -> main + " " + v.pick("vì", "tại", "do") + " " + why;
        };
    }

    private static String doneSentence(Voice v, Ctx c) {
        String head = v.pick("Xong rồi", "Được rồi", "Xong việc", "Ok rồi");
        if (c.item != null && c.count > 0) return head + ", " + v.pick("được", "có thêm", "kiếm được") + " " + c.count + " " + ItemNames.of(c.item);
        return head;
    }

    private static String upgradeRemark(Voice v, Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.startsWith("iron_")) return v.pick(". Giờ " + v.self + " mạnh hơn rồi", ". Sắt xịn ghê");
        if (path.startsWith("stone_")) return v.pick(". Bền hơn đồ gỗ nhiều", "");
        if (path.equals("furnace")) return v.pick(". Giờ nướng thịt được rồi", ". Có lò rồi");
        return "";
    }

    private static String discovery(LyMeowzEntity ly, Voice v, Ctx c) {
        String where = direction(ly, c.pos);
        String what = placeDesc(v, c.place);
        String loc = at(ly, c.pos);
        String s = v.pick(cap(loc) + " có " + what, v.self + " thấy " + what + " " + loc);
        String dist = distance(ly, c.pos);
        if (dist != null) s += " " + dist;
        String plan = switch (c.place) {
            case TREES -> v.pick(", lần sau " + v.self + " qua đó lấy gỗ", "");
            case STONE, ORE -> v.pick(", đào được nhiều lắm", ", " + v.self + " nhớ chỗ đó rồi");
            case ANIMALS -> v.pick(", có đồ ăn rồi", "");
            case WATER -> v.pick(", mát ghê", "");
            case VIEW -> v.pick(", ngắm cảnh đẹp lắm", "");
            case CAVE, FLOWERS -> "";
            default -> "";
        };
        return s + plan;
    }

    static String placeDesc(Voice v, Mind.PlaceKind k) {
        if (k == null) return "gì đó";
        return switch (k) {
            case TREES -> v.pick("một khu rừng", "nhiều cây lắm", "cả một đám cây");
            case STONE -> v.pick("một bãi đá", "vách đá", "chỗ đào đá được");
            case ORE -> v.pick("quặng", "quặng lộ ra ngoài", "khoáng sản");
            case ANIMALS -> v.pick("một đàn thú", "mấy con vật", "bò với cừu");
            case DANGER -> v.pick("chỗ nguy hiểm", "chỗ đáng sợ");
            case WATER -> v.pick("một hồ nước", "nước");
            case VIEW -> v.pick("chỗ ngắm cảnh đẹp", "một đỉnh đồi cao");
            case CAVE -> v.pick("một cái hang", "cửa hang tối om");
            case FLOWERS -> v.pick("một cánh đồng hoa", "nhiều hoa lắm");
            case HOME -> "nhà";
        };
    }

    /** Hướng từ chỗ người nghe (hoặc cô ấy) tới vị trí. */
    static String direction(LyMeowzEntity ly, BlockPos pos) {
        if (pos == null) return "kia";
        var fp = ly.focusPlayer();
        var from = fp != null && fp.level() == ly.level() ? fp.blockPosition() : ly.blockPosition();
        double dx = pos.getX() - from.getX(), dz = pos.getZ() - from.getZ();
        if (dx * dx + dz * dz < 36) return "ngay đây";
        double ang = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = bắc (-Z), 90 = đông (+X)
        String[] dirs = {"bắc", "đông bắc", "đông", "đông nam", "nam", "tây nam", "tây", "tây bắc"};
        return dirs[(int) Math.floorMod(Math.round(ang / 45.0), 8L)];
    }

    /** "ở phía bắc" hoặc "ở ngay đây". */
    static String at(LyMeowzEntity ly, BlockPos pos) {
        String d = direction(ly, pos);
        return d.equals("ngay đây") ? "ở ngay đây" : "ở phía " + d;
    }

    static String distance(LyMeowzEntity ly, BlockPos pos) {
        if (pos == null) return null;
        double d = Math.sqrt(ly.blockPosition().distSqr(pos));
        if (d < 12) return "gần lắm";
        if (d < 40) return "cách đây chừng " + (int) (Math.round(d / 5.0) * 5) + " bước";
        return "hơi xa, chừng " + (int) (Math.round(d / 10.0) * 10) + " bước";
    }

    private static String feeling(LyMeowzEntity ly, Voice v) {
        MindData m = v.m;
        String me = v.self;
        return switch (v.mood) {
            case "JOY" -> cap(me) + " đang " + v.degree(m.joy) + " vui" + v.pick("", " luôn", ", đi với " + v.you + " vui lắm");
            case "FEAR" -> cap(me) + " " + v.degree(m.fear) + " sợ" + v.pick("", ", " + v.you + " ở gần " + me + " nha", ", nhỡ có quái thì sao");
            case "BORED" -> cap(me) + " " + v.degree(m.boredom) + " chán" + v.pick("", ", mình đi đâu chơi không", ", muốn đi khám phá quá");
            case "CURIOUS" -> cap(me) + " " + v.degree(m.curiosity) + " tò mò" + v.pick(" không biết quanh đây có gì", ", muốn đi xem xung quanh");
            case "HUNGRY" -> cap(me) + " " + v.degree(100 - ly.getHunger() * 5) + " đói";
            case "TIRED" -> cap(me) + " " + v.degree(100 - m.energy) + " mệt" + v.pick("", ", nghỉ chút được không");
            case "LONELY" -> cap(me) + " " + v.degree(100 - m.social) + " cô đơn" + v.pick("", ", " + v.you + " nói chuyện với " + me + " đi");
            case "SAD" -> cap(me) + " " + v.degree(100 - m.joy) + " buồn" + v.pick("", ", không biết tại sao nữa");
            default -> cap(me) + " ổn" + v.pick("", " lắm", " mà");
        };
    }

    /** Chuyện phiếm: nhớ lại chuyện cũ, nói cảm xúc, nói về nơi đã thấy, hoặc hỏi lại chủ. */
    private static String smalltalk(LyMeowzEntity ly, Voice v) {
        MindData m = v.m;
        int pick = v.r.nextInt(5);
        if (pick == 0 && !m.episodes.isEmpty()) {
            String s = recall(ly, v, m.episodes.get(v.r.nextInt(m.episodes.size())));
            if (s != null) return s;
        }
        if (pick == 1 && !m.places.isEmpty()) {
            MindData.Place pl = m.places.get(v.r.nextInt(m.places.size()));
            Mind.PlaceKind k = Mind.PlaceKind.valueOf(pl.kind);
            if (k != Mind.PlaceKind.HOME) return v.pick(cap(v.self) + " còn nhớ ", cap(v.you) + " biết không, ")
                    + placeDesc(v, k) + " " + at(ly, new BlockPos(pl.x, pl.y, pl.z));
        }
        if (pick == 2) return feeling(ly, v);
        if (pick == 3) return v.pick("Ước gì " + v.self + " được " + v.p.idealText(), cap(v.self) + " thích nhất là " + v.p.idealText());
        return v.pick(cap(v.you) + " muốn " + v.self + " làm gì không", "Hôm nay mình làm gì tiếp đây", cap(v.you) + " thấy thế giới này đẹp không");
    }

    /** Kể lại một chuyện trong nhật ký. */
    public static String recall(LyMeowzEntity ly, Voice v, MindData.Episode e) {
        long ago = v.m.lifeTicks - e.time;
        String when = ago < 2400 ? v.pick("vừa nãy", "mới đây") : ago < 24000 ? v.pick("lúc nãy", "hồi nãy") : ago < 96000 ? "hôm trước" : "mấy hôm trước";
        String me = v.self, you = v.you;
        String where = " " + at(ly, new BlockPos(e.x, ly.getBlockY(), e.z));
        String body = switch (e.type) {
            case "HURT_BY_MOB" -> me + " bị " + (e.what != null ? e.what : "quái") + " tấn công" + where + ", " + v.pick("sợ ghê", "đau lắm");
            case "FAINTED" -> me + " bị ngất" + where + v.pick(", may mà không sao", "... nhớ lại vẫn còn sợ");
            case "GIFT" -> (e.who != null ? e.who : you) + " tặng " + me + " " + (e.what != null ? e.what : "quà") + v.pick(", vui ghê", ", " + me + " vẫn giữ đó");
            case "KILLED_MOB" -> me + " hạ được một con " + (e.what != null ? e.what : "quái") + v.pick(", ngầu không", "");
            case "FOUND_PLACE" -> me + " tìm thấy " + placeDesc(v, safeKind(e.what)) + where;
            case "CRAFTED" -> me + " làm được " + (e.what != null ? e.what : "đồ mới");
            case "INSULTED" -> (e.who != null ? e.who : "ai đó") + " mắng " + me + "... " + me + " vẫn còn buồn";
            case "HIT_BY_PLAYER" -> (e.who != null ? e.who : "ai đó") + " đánh " + me + ", đau lắm đó";
            case "OWNER_RETURNED" -> (e.who != null ? e.who : you) + " tới gặp " + me + ", " + me + " vui ghê";
            case "SAVED" -> (e.who != null ? e.who : you) + " cứu " + me + " khỏi " + (e.what != null ? "lũ " + e.what : "quái") + where;
            case "NAMED" -> (e.who != null ? e.who : you) + " đặt tên cho " + me + " là " + e.what;
            case "BUILT" -> me + " làm xong " + (e.what != null ? e.what : "một thứ");
            case "TASK_FAIL" -> me + " " + verb(v, e.what) + " mà không được" + where;
            default -> null;
        };
        return body == null ? null : cap(when) + " " + body;
    }

    private static Mind.PlaceKind safeKind(String s) {
        try { return Mind.PlaceKind.valueOf(s); } catch (Exception e) { return null; }
    }

    private static String whatNow(LyMeowzEntity ly, Voice v) {
        String doing = ly.survival.currentName();
        if (doing.equals("nghỉ")) return v.pick("Mình đi đâu chơi đây", cap(v.self) + " đang rảnh nè", "Có cần " + v.self + " giúp gì không");
        return cap(v.self) + " đang " + verb(v, doing);
    }

    private static String ack(Voice v, String cmd) {
        String me = v.self, you = v.you;
        if (cmd == null) return v.pick("Okie", "Được", "Rõ");
        return switch (cmd) {
            case "FOLLOW" -> v.pick("Okie, " + me + " đi theo " + you, "Đi thôi", cap(you) + " đi đâu " + me + " đi đó");
            case "STAY" -> v.pick(cap(me) + " đứng đây đợi " + you, "Ừm, " + me + " đợi ở đây", cap(me) + " sẽ ngoan ngoãn đứng đây") + (v.p.brave < 0.4f ? ", đừng đi lâu nha" : "");
            case "COME" -> v.pick("Tới liền", "Đợi " + me + " với", "Đây đây");
            case "WOOD" -> v.pick("Để " + me + " lo gỗ", "Okie, " + me + " đi chặt cây");
            case "STONE" -> v.pick(cap(me) + " đi đào đá", "Đào đá thôi") + (v.p.brave < 0.4f ? ", mà hang tối hơi sợ" : "");
            case "FOOD" -> v.pick(cap(me) + " đi kiếm đồ ăn", "Đi săn thôi");
            case "WORK" -> v.pick("Để " + me + " tự lo", "Okie, " + me + " xem cần làm gì");
            case "HOME" -> v.pick(cap(me) + " về nhà trước", "Về nhà thôi");
            default -> v.pick("Okie", "Được");
        };
    }

    private static String afterthought(LyMeowzEntity ly, Voice v, Act act) {
        if (act == Act.FEELING || act == Act.SMALLTALK) return null;
        return switch (v.r.nextInt(3)) {
            case 0 -> cap(v.you) + " thấy sao?";
            case 1 -> switch (v.mood) {
                case "JOY" -> v.pick("Vui ghê!", "Hôm nay thật tuyệt.");
                case "FEAR" -> v.pick("Ở gần " + v.self + " nha.", "Hơi sợ...");
                case "BORED" -> "Chán quá à.";
                case "HUNGRY" -> "Đói ghê...";
                default -> null;
            };
            default -> null;
        };
    }

    // ------------------------------------------------------------------ tên quái

    public static String mobName(EntityType<?> type) {
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        return switch (path) {
            case "zombie", "husk", "drowned", "zombie_villager" -> "zombie";
            case "skeleton", "stray", "bogged" -> "bộ xương";
            case "spider", "cave_spider" -> "nhện";
            case "creeper" -> "creeper";
            case "enderman" -> "enderman";
            case "witch" -> "phù thủy";
            case "slime" -> "slime";
            case "phantom" -> "phantom";
            default -> path.replace('_', ' ');
        };
    }

    // ------------------------------------------------------------------

    private static String cap(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String capitalize(String s) {
        s = s.trim();
        return cap(s);
    }

    private static boolean endsWithPunct(String s) {
        return s.endsWith("?") || s.endsWith("!") || s.endsWith(".") || s.endsWith("~") || s.endsWith("...");
    }
}
