package com.khanhly.lymeowz.survival;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Lộ trình sinh tồn dài hạn của cô ấy – giống cách một người chơi thật đi từ tay trắng tới cuối game.
 * Giai đoạn được tính lại từ những gì cô ấy đang có (đồ trong túi, đồ đang mặc, công trình đã xây),
 * nên mất đồ thì cô ấy tự quay lại giai đoạn trước.
 */
public final class Roadmap {
    private Roadmap() {}

    public enum Stage {
        WOOD("làm đồ gỗ", "chặt cây, làm bàn chế tạo, cuốc và rìu gỗ"),
        STONE("làm đồ đá", "đào đá, làm cuốc, rìu, kiếm đá, làm lò nung"),
        SHELTER("dựng nhà", "đi tìm đất ở đồng bằng, đồng hoa hay rừng anh đào, rồi gom gỗ, đá, kính để xây dần căn nhà theo bản vẽ"),
        FARM("làm ruộng", "nhặt hạt giống, làm cuốc, cày đất trồng lúa để làm bánh mì"),
        PEN("làm chuồng", "dựng hàng rào, dụ bò cừu về nuôi cho có thịt với len"),
        BED("làm giường", "kiếm 3 cục len làm giường để tối ngủ trong nhà"),
        IRON("đồ sắt", "đào mỏ xuống tầng sắt, nung sắt làm cuốc, kiếm, giáp, khiên, xô"),
        DIAMOND("kim cương", "đào xuống tận đáy tìm kim cương làm cuốc với kiếm kim cương"),
        NETHER("cổng Nether", "múc nước đổ lên nham thạch làm obsidian, đào sỏi lấy đá lửa làm bật lửa, dựng và thắp cổng Nether"),
        END("Nether và End", "rủ bạn bè cùng vào Nether, rồi cùng đi End đánh rồng (đi theo bạn, đánh quái cùng bạn)");

        public final String title, detail;
        Stage(String title, String detail) { this.title = title; this.detail = detail; }
    }

    public static boolean has(LyMeowzEntity ly, String structure) {
        var d = ly.data();
        return d != null && d.structures.containsKey(structure);
    }

    /** Giai đoạn hiện tại; kẹt ở một giai đoạn (ruộng, chuồng, giường) quá một ngày thì tạm bỏ qua, làm tiếp bước sau. */
    public static Stage current(LyMeowzEntity ly) {
        Stage s = raw(ly);
        var d = ly.data();
        if (d == null) return s;
        long now = ly.level().getGameTime();
        for (int guard = 0; guard < 3 && d.skipped.contains(s.name()); guard++) s = rawAfter(ly, s);
        if (d.stage != s.ordinal()) { d.stage = s.ordinal(); d.stageSince = now; }
        else if ((s == Stage.FARM || s == Stage.PEN || s == Stage.BED || (s == Stage.SHELTER && d.blueprintAt != null))
                && now - d.stageSince > 24000) {
            // Nhà to xây lâu: đã chọn được đất thì vừa xây dần vừa làm ruộng, làm chuồng như người chơi.
            d.skipped.add(s.name());
            com.khanhly.lymeowz.world.CompanionStore.markDirty();
        }
        return s;
    }

    private static Stage rawAfter(LyMeowzEntity ly, Stage skipped) {
        var d = ly.data();
        Stage s = raw(ly);
        Stage[] all = Stage.values();
        for (int i = skipped.ordinal() + 1; i < all.length; i++) {
            Stage cand = all[i];
            if (d != null && d.skipped.contains(cand.name())) continue;
            if (cand.ordinal() > s.ordinal() && isDone(ly, cand)) continue;
            return firstFrom(ly, cand);
        }
        return s;
    }

    /** Bước đầu tiên chưa xong tính từ {@code from}. */
    private static Stage firstFrom(LyMeowzEntity ly, Stage from) {
        Stage[] all = Stage.values();
        var d = ly.data();
        for (int i = from.ordinal(); i < all.length; i++) {
            if (d != null && d.skipped.contains(all[i].name()) && !isDone(ly, all[i])) continue;
            if (!isDone(ly, all[i])) return all[i];
        }
        return Stage.END;
    }

    private static boolean isDone(LyMeowzEntity ly, Stage s) {
        LyInventory all = ly.inventoryWithHand();
        int pick = Survival.bestTier(all, Tools.Kind.PICKAXE);
        return switch (s) {
            case WOOD -> pick >= 1;
            case STONE -> pick >= 2 && Survival.bestTier(all, Tools.Kind.SWORD) >= 2;
            case SHELTER -> has(ly, "home");
            case FARM -> has(ly, "farm");
            case PEN -> has(ly, "pen");
            case BED -> has(ly, "bed");
            case IRON -> raw(ly).ordinal() > Stage.IRON.ordinal() || (pick >= 3 && Survival.bestTier(all, Tools.Kind.SWORD) >= 3
                    && Tools.armorRank(ly.getItemBySlot(EquipmentSlot.CHEST)) >= 3);
            case DIAMOND -> pick >= 4;
            case NETHER -> has(ly, "portal");
            case END -> false;
        };
    }

    private static Stage raw(LyMeowzEntity ly) {
        LyInventory all = ly.inventoryWithHand();
        int pick = Survival.bestTier(all, Tools.Kind.PICKAXE);
        int sword = Survival.bestTier(all, Tools.Kind.SWORD);
        if (pick < 1) return Stage.WOOD;
        if (pick < 2 || sword < 2) return Stage.STONE;
        if (!has(ly, "home")) return Stage.SHELTER;
        if (!has(ly, "farm")) return Stage.FARM;
        if (!has(ly, "pen")) return Stage.PEN;
        if (!has(ly, "bed")) return Stage.BED;
        boolean chest = !ly.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && Tools.armorRank(ly.getItemBySlot(EquipmentSlot.CHEST)) >= 3;
        boolean shield = ly.getOffhandItem().is(Items.SHIELD) || all.has(Items.SHIELD);
        if (pick < 3 || sword < 3 || !chest || !shield) return Stage.IRON;
        if (pick < 4) return Stage.DIAMOND;
        if (!has(ly, "portal")) return Stage.NETHER;
        return Stage.END;
    }

    /** Trả lời câu "kế hoạch của cậu là gì?". */
    public static String describe(LyMeowzEntity ly, boolean friendly) {
        Stage s = current(ly);
        if (!friendly) return "Mình đang " + s.title + ". Vậy thôi.";
        StringBuilder b = new StringBuilder("Giờ mình đang ở bước \"" + s.title + "\": " + s.detail + ".");
        Stage[] all = Stage.values();
        if (s.ordinal() + 1 < all.length) {
            b.append(" Xong thì tới ").append(all[s.ordinal() + 1].title);
            if (s.ordinal() + 2 < all.length) b.append(", rồi ").append(all[s.ordinal() + 2].title);
            b.append(".");
        }
        b.append(" (bước ").append(s.ordinal() + 1).append("/").append(all.length).append(")");
        String house = houseProgress(ly);
        if (house != null) b.append(" ").append(house);
        return b.toString();
    }

    /** Nhà theo bản vẽ đang xây tới đâu, còn thiếu gì (null nếu không xây gì). */
    public static String houseProgress(LyMeowzEntity ly) {
        var d = ly.data();
        if (d == null || d.blueprint == null || !(ly.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;
        var bp = com.khanhly.lymeowz.house.Blueprint.byName(d.blueprint);
        if (bp == null) return null;
        if (d.blueprintAt == null) return "Mình đang để dành đồ xây căn nhà \"" + bp.name + "\", còn phải tìm chỗ đất đẹp nữa.";
        var o = new net.minecraft.core.BlockPos(d.blueprintAt.x, d.blueprintAt.y, d.blueprintAt.z);
        int[] left = com.khanhly.lymeowz.survival.task.BlueprintTask.remaining(level, bp, o);
        int total = 0;
        for (var p : bp.pieces) if (!com.khanhly.lymeowz.house.Materials.optional(p.state())) total++;
        int done = Math.max(0, total - left[0]);
        StringBuilder b = new StringBuilder("Nhà mới (\"" + bp.name + "\") xây được " + (total == 0 ? 0 : done * 100 / total) + "%");
        var need = com.khanhly.lymeowz.survival.task.BlueprintTask.need(level, bp, o, false);
        var inv = com.khanhly.lymeowz.house.Materials.amounts(ly.inv());
        StringBuilder miss = new StringBuilder();
        for (var e : need.entrySet()) {
            int lack = (int) Math.ceil(e.getValue() - inv.getOrDefault(e.getKey(), 0f));
            if (lack > 0) miss.append(miss.length() == 0 ? "" : ", ").append(lack).append(" ").append(e.getKey().vn);
        }
        if (miss.length() > 0) b.append(", còn thiếu ").append(miss);
        return b.append(".").toString();
    }

    /** Cô ấy có bao nhiêu của cải (túi + rương ở nhà). */
    public static String wealth(LyMeowzEntity ly, boolean tell) {
        if (!tell) return "Bí mật";
        LyInventory all = ly.inventoryWithHand();
        int diamond = all.count(Items.DIAMOND), iron = all.count(Items.IRON_INGOT) + all.count(Items.RAW_IRON);
        int stored = ly.storedCount();
        StringBuilder b = new StringBuilder("Trong túi có ");
        b.append(diamond > 0 ? diamond + " kim cương, " : "").append(iron).append(" sắt, ")
                .append(all.count(ItemTags.LOGS) + all.count(ItemTags.PLANKS)).append(" gỗ");
        if (stored > 0) b.append(", còn rương ở nhà cất ").append(stored).append(" món nữa");
        return b.toString();
    }

    /** Có nên đeo/mặc món này thay món đang mặc không. */
    public static boolean better(ItemStack candidate, ItemStack worn) {
        return Tools.armorRank(candidate) > Tools.armorRank(worn);
    }
}
