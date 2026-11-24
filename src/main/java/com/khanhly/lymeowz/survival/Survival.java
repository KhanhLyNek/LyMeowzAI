package com.khanhly.lymeowz.survival;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.task.ChopTreeTask;
import com.khanhly.lymeowz.survival.task.CollectTask;
import com.khanhly.lymeowz.survival.task.CraftTask;
import com.khanhly.lymeowz.survival.task.EatTask;
import com.khanhly.lymeowz.survival.task.HuntTask;
import com.khanhly.lymeowz.survival.task.MineTask;
import com.khanhly.lymeowz.survival.task.SmeltTask;
import com.khanhly.lymeowz.survival.task.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Bộ não sinh tồn": mỗi khi rảnh thì chọn việc quan trọng nhất, giống một người chơi mới vào thế giới:
 * <ol>
 *   <li>Đói → ăn.</li>
 *   <li>Có đồ rơi gần đó → nhặt.</li>
 *   <li>Đủ nguyên liệu nâng cấp → chế: đồ gỗ → đồ đá → lò nung → đồ sắt.</li>
 *   <li>Có thịt sống/quặng sắt + lò + chất đốt → nung.</li>
 *   <li>Sắp hết đồ ăn → đi săn.</li>
 *   <li>Thiếu gỗ → chặt cây. Thiếu đá/than/sắt → đào.</li>
 *   <li>Không còn gì cần làm → đứng nghỉ cạnh chủ.</li>
 * </ol>
 */
public class Survival {
    public enum Focus { AUTO, WOOD, STONE, FOOD }

    private final LyMeowzEntity ly;
    private Task current;
    private int cooldown;
    private final Set<BlockPos> blacklist = new HashSet<>();
    private int age;
    private Focus focus = Focus.AUTO;
    private int focusTicks;
    private String lastAnnounced = "";

    public Survival(LyMeowzEntity ly) {
        this.ly = ly;
    }

    public boolean busy() {
        return current != null;
    }

    public String currentName() {
        return current == null ? "nghỉ" : current.name();
    }

    public Focus focus() {
        return focus;
    }

    public void setFocus(Focus f) {
        focus = f;
        focusTicks = f == Focus.AUTO ? 0 : 20 * 60 * 5;
        cooldown = 0;
    }

    public void interrupt(ServerLevel level) {
        if (current != null) {
            current.stop(level);
            current = null;
        }
    }

    /** Chỉ lo ăn (dùng khi đang đứng đợi/ở nhà). */
    public void tickMaintenance(ServerLevel level) {
        if (current instanceof EatTask) {
            if (!current.tick(level)) { current.stop(level); current = null; }
            return;
        }
        if (current == null && ly.tickCount % 40 == 0 && needsToEat()) {
            current = new EatTask(ly);
            current.start(level);
        }
    }

    /** Một tick làm việc quanh {@code center} (chỗ chủ đứng), trong bán kính {@code radius}. */
    public void tick(ServerLevel level, BlockPos center, int radius) {
        if (focusTicks > 0 && --focusTicks == 0) focus = Focus.AUTO;
        if (++age % 6000 == 0) blacklist.clear();
        if (current != null) {
            if (!current.tick(level)) {
                current.stop(level);
                current = null;
                cooldown = 10;
            }
            return;
        }
        if (cooldown-- > 0) return;
        current = plan(level, center, radius);
        if (current != null) {
            current.start(level);
            announce(current.name());
        } else {
            cooldown = 60;
        }
    }

    private boolean needsToEat() {
        int h = ly.getHunger();
        if (h > 14) return false;
        boolean starving = h <= 4;
        return ly.inv().count(s -> Tools.isGoodFood(s) || (starving && Tools.isFood(s))) > 0;
    }

    // ------------------------------------------------------------------ lập kế hoạch

    private Task plan(ServerLevel level, BlockPos center, int radius) {
        LyInventory all = ly.inventoryWithHand();

        if (needsToEat()) return new EatTask(ly);

        ItemEntity drop = CollectTask.find(ly, level, 8);
        if (drop != null) return new CollectTask(ly, drop);

        Item craft = nextCraft(level, all);
        if (craft != null) return new CraftTask(ly, craft);

        int rawFood = all.count(Tools::isRawMeat);
        int rawIron = all.count(Items.RAW_IRON);
        if (SmeltTask.canSmelt(ly) && (rawFood >= 2 || rawIron >= 3 || (rawFood > 0 && ly.getHunger() <= 14))) {
            return new SmeltTask(ly);
        }

        int food = all.count(Tools::isGoodFood) + rawFood;
        if (food < 4 || focus == Focus.FOOD) {
            Animal prey = HuntTask.find(ly, level, radius);
            if (prey != null) return new HuntTask(ly, prey);
        }

        int pickTier = bestTier(all, Tools.Kind.PICKAXE);
        int logs = all.count(ItemTags.LOGS) + all.count(ItemTags.PLANKS) / 4;
        int woodTarget = focus == Focus.WOOD ? 64 : (pickTier >= 2 ? 16 : 8);
        if (logs < woodTarget && focus != Focus.STONE && focus != Focus.FOOD) {
            ChopTreeTask t = ChopTreeTask.find(ly, level, center, radius, blacklist);
            if (t != null) return t;
        }

        if (pickTier >= 1 && focus != Focus.WOOD && focus != Focus.FOOD) {
            int cobble = all.count(ItemTags.STONE_TOOL_MATERIALS);
            int coal = all.count(ItemTags.COALS);
            boolean ironDone = bestTier(all, Tools.Kind.PICKAXE) >= 3 && bestTier(all, Tools.Kind.SWORD) >= 3
                    && bestTier(all, Tools.Kind.AXE) >= 3;
            int iron = rawIron + all.count(Items.IRON_INGOT);
            boolean wantMore = cobble < (focus == Focus.STONE ? 64 : 16) || coal < 8 || (pickTier >= 2 && !ironDone && iron < 8);
            if (wantMore && MineTask.findTarget(ly, level, center, radius, blacklist) != null) {
                return new MineTask(ly, 8, center, radius, blacklist);
            }
        }
        return null;
    }

    /** Món nâng cấp tiếp theo làm được ngay, theo thứ tự một người chơi sinh tồn hay làm. */
    private Item nextCraft(ServerLevel level, LyInventory all) {
        int pick = bestTier(all, Tools.Kind.PICKAXE);
        int axe = bestTier(all, Tools.Kind.AXE);
        int sword = bestTier(all, Tools.Kind.SWORD);
        List<Item> wish = new java.util.ArrayList<>();
        if (pick < 1) wish.add(Items.WOODEN_PICKAXE);
        if (axe < 1) wish.add(Items.WOODEN_AXE);
        if (pick < 2) wish.add(Items.STONE_PICKAXE);
        if (sword < 1 && pick >= 1) wish.add(Items.WOODEN_SWORD);
        if (axe < 2) wish.add(Items.STONE_AXE);
        if (sword < 2) wish.add(Items.STONE_SWORD);
        if (!all.has(Items.FURNACE) && pick >= 2) wish.add(Items.FURNACE);
        if (pick < 3) wish.add(Items.IRON_PICKAXE);
        if (sword < 3) wish.add(Items.IRON_SWORD);
        if (axe < 3) wish.add(Items.IRON_AXE);
        for (Item it : wish) if (CraftTask.canMake(ly, level, it)) return it;
        return null;
    }

    public static int bestTier(LyInventory inv, Tools.Kind kind) {
        int best = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && Tools.isKind(s, kind)) best = Math.max(best, Tools.tier(s));
        }
        return best;
    }

    private void announce(String task) {
        if (task.equals(lastAnnounced)) return;
        lastAnnounced = task;
        switch (task) {
            case "chặt cây" -> ly.sayWork("chop", "Mình đi chặt cây nhé~");
            case "đào đá" -> ly.sayWork("mine", "Đào ít đá thôi~");
            case "đi săn" -> ly.sayWork("hunt", "Đi kiếm đồ ăn nè~");
            case "ăn" -> ly.sayWork("eat", "Đói quá... mình ăn chút nhé~");
            default -> {}
        }
    }

    /** Dùng cho chat / lệnh: tóm tắt túi đồ. */
    public static String summary(LyInventory inv) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            counts.merge(ItemNames.of(s.getItem()), s.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return "túi trống trơn";
        StringBuilder b = new StringBuilder();
        counts.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(8)
                .forEach(e -> b.append(b.length() == 0 ? "" : ", ").append(e.getValue()).append(" ").append(e.getKey()));
        return b.toString();
    }

    public boolean isNight(ServerLevel level) {
        return Compat.isNight(level);
    }
}
