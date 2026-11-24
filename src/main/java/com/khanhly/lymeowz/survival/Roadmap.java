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
        SHELTER("dựng nhà", "gom đá với gỗ để xây một căn nhà nhỏ có cửa, đuốc, rương"),
        FARM("làm ruộng", "nhặt hạt giống, làm cuốc, cày đất trồng lúa để làm bánh mì"),
        PEN("làm chuồng", "dựng hàng rào, dụ bò cừu về nuôi cho có thịt với len"),
        BED("làm giường", "kiếm 3 cục len làm giường để tối ngủ trong nhà"),
        IRON("đồ sắt", "đào mỏ xuống tầng sắt, nung sắt làm cuốc, kiếm, giáp, khiên, xô"),
        DIAMOND("kim cương", "đào xuống tận đáy tìm kim cương làm cuốc với kiếm kim cương"),
        NETHER("Nether", "xây cổng Nether (cô ấy chưa biết làm phần này)"),
        END("rồng Ender", "tìm thành trì, vào End đánh rồng (cô ấy chưa biết làm phần này)");

        public final String title, detail;
        Stage(String title, String detail) { this.title = title; this.detail = detail; }
    }

    public static boolean has(LyMeowzEntity ly, String structure) {
        var d = ly.data();
        return d != null && d.structures.containsKey(structure);
    }

    public static Stage current(LyMeowzEntity ly) {
        LyInventory all = ly.inventoryWithHand();
        int pick = Survival.bestTier(all, Tools.Kind.PICKAXE);
        int sword = Survival.bestTier(all, Tools.Kind.SWORD);
        if (pick < 1) return Stage.WOOD;
        if (pick < 2 || sword < 2) return Stage.STONE;
        if (!has(ly, "house")) return Stage.SHELTER;
        if (!has(ly, "farm")) return Stage.FARM;
        if (!has(ly, "pen")) return Stage.PEN;
        if (!has(ly, "bed")) return Stage.BED;
        boolean chest = !ly.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && Tools.armorRank(ly.getItemBySlot(EquipmentSlot.CHEST)) >= 3;
        boolean shield = ly.getOffhandItem().is(Items.SHIELD) || all.has(Items.SHIELD);
        if (pick < 3 || sword < 3 || !chest || !shield) return Stage.IRON;
        if (pick < 4) return Stage.DIAMOND;
        return Stage.NETHER;
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
        return b.toString();
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
