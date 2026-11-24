package com.khanhly.lymeowz.survival;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Decider;
import com.khanhly.lymeowz.survival.task.CraftTask;
import com.khanhly.lymeowz.survival.task.EatTask;
import com.khanhly.lymeowz.survival.task.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thực hiện việc do "tâm trí" ({@link Decider}, Utility AI) chọn: chạy việc hiện tại, mỗi giây xem có việc khẩn cấp
 * hơn không, làm xong thì ghi kết quả để học, rồi chọn việc tiếp theo.
 */
public class Survival {
    public enum Focus { AUTO, WOOD, STONE, FOOD }

    private final LyMeowzEntity ly;
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final Decider decider;
    private Task current;
    private float currentScore;
    private String currentReason;
    private int cooldown;
    private int age;
    private int instantFails;
    private Focus focus = Focus.AUTO;
    private int focusTicks;

    public Survival(LyMeowzEntity ly) {
        this.ly = ly;
        this.decider = new Decider(ly, blacklist);
    }

    public boolean busy() {
        return current != null;
    }

    public String currentName() {
        return current == null ? "nghỉ" : current.name();
    }

    public String currentReason() {
        return currentReason;
    }

    public Focus focus() {
        return focus;
    }

    public void setFocus(Focus f) {
        focus = f;
        focusTicks = f == Focus.AUTO ? 0 : 20 * 60 * 5;
        cooldown = 0;
        decider.invalidate();
    }

    /** Làm ngay một việc (không qua chấm điểm). */
    public void forceTask(ServerLevel level, Task t) {
        interrupt(level);
        current = t;
        currentScore = 1f;
        currentReason = null;
        t.start(level);
    }

    public void interrupt(ServerLevel level) {
        if (current != null) {
            Task t = current;
            current = null;
            t.stop(level);
        }
    }

    /** Chỉ lo ăn (dùng khi đang đứng đợi / ở nhà / chủ offline). */
    public void tickMaintenance(ServerLevel level) {
        if (current != null && !(current instanceof EatTask)) interrupt(level);
        if (current instanceof EatTask) {
            if (!current.tick(level)) { current.stop(level); current = null; }
            return;
        }
        if (ly.tickCount % 40 == 0 && needsToEat()) {
            current = new EatTask(ly);
            current.start(level);
        }
    }

    private boolean needsToEat() {
        int h = ly.getHunger();
        if (h > 14) return false;
        return ly.inv().count(s -> Tools.edible(s, h)) > 0;
    }

    /** Một tick làm việc quanh {@code center} (chỗ chủ đứng), trong bán kính {@code radius}. */
    public void tick(ServerLevel level, BlockPos center, int radius) {
        if (focusTicks > 0 && --focusTicks == 0) focus = Focus.AUTO;
        if (++age % 6000 == 0) blacklist.clear();
        if (current != null) {
            // Mỗi giây xem có việc gì khẩn cấp hơn không (đói lả, gặp quái khi đang yếu...).
            if (age % 20 == 0) {
                Decider.Option urgent = decider.best(decider.evaluate(level, center, radius, focus).stream()
                        .filter(o -> o.urgent() && !o.action().equals(current.name())).toList());
                if (urgent != null && urgent.score() > currentScore + 0.2f) {
                    finish(level);
                    begin(level, urgent);
                    return;
                }
            }
            if (!current.tick(level)) {
                finish(level);
                cooldown = 10;
            }
            return;
        }
        if (cooldown-- > 0) return;
        Decider.Option o = decider.best(decider.evaluate(level, center, radius, focus));
        if (o == null || o.score() < 0.1f) { cooldown = 40; return; }
        begin(level, o);
    }

    private void begin(ServerLevel level, Decider.Option o) {
        current = o.make().get();
        currentScore = o.score();
        currentReason = o.reason();
        current.start(level);
    }

    private void finish(ServerLevel level) {
        Task t = current;
        current = null;
        t.stop(level);
        boolean ok = t.succeeded();
        if (com.khanhly.lymeowz.command.SurvivalTest.running() && !Set.of("nghỉ", "nhặt đồ", "ăn").contains(t.name()))
            com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[survival]     ({} {} sau {}s)", t.name(), ok ? "xong" : "thất bại", t.ageTicks() / 20);
        // Học: nhớ việc này ở vùng này thành công hay thất bại.
        if (!Set.of("nghỉ", "nhặt đồ", "ăn", "thư giãn").contains(t.name())) ly.mind().learn(t.name(), ly.blockPosition(), ok);
        // Thất bại ngay lập tức nhiều lần liền: có lẽ đang bị kẹt (trong chuồng, trong hố...).
        if (!ok && t.ageTicks() < 20) {
            if (++instantFails >= 3) { instantFails = 0; ly.unstuck(level); }
        } else instantFails = 0;
        decider.invalidate();
    }

    /** Món nâng cấp tiếp theo làm được ngay, theo thứ tự một người chơi sinh tồn hay làm. */
    public static Item nextCraft(LyMeowzEntity ly, ServerLevel level, LyInventory all, Roadmap.Stage stage) {
        int pick = bestTier(all, Tools.Kind.PICKAXE);
        int axe = bestTier(all, Tools.Kind.AXE);
        int sword = bestTier(all, Tools.Kind.SWORD);
        List<Item> wish = new ArrayList<>();
        if (pick < 1) wish.add(Items.WOODEN_PICKAXE);
        if (axe < 1) wish.add(Items.WOODEN_AXE);
        if (pick < 2) wish.add(Items.STONE_PICKAXE);
        if (sword < 1 && pick >= 1) wish.add(Items.WOODEN_SWORD);
        if (axe < 2) wish.add(Items.STONE_AXE);
        if (sword < 2) wish.add(Items.STONE_SWORD);
        if (!all.has(Items.FURNACE) && pick >= 2) wish.add(Items.FURNACE);
        if (stage.ordinal() >= Roadmap.Stage.FARM.ordinal() && all.count(net.minecraft.tags.ItemTags.HOES) == 0) {
            wish.add(Items.STONE_HOE);
            wish.add(Items.WOODEN_HOE);
        }
        if (pick < 3) wish.add(Items.IRON_PICKAXE);
        if (sword < 3) wish.add(Items.IRON_SWORD);
        if (!all.has(Items.SHIELD) && !ly.getOffhandItem().is(Items.SHIELD)) wish.add(Items.SHIELD);
        armor(ly, all, wish, net.minecraft.world.entity.EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE);
        if (!all.has(Items.BUCKET) && !all.has(Items.WATER_BUCKET)) wish.add(Items.BUCKET);
        armor(ly, all, wish, net.minecraft.world.entity.EquipmentSlot.HEAD, Items.IRON_HELMET, Items.DIAMOND_HELMET);
        armor(ly, all, wish, net.minecraft.world.entity.EquipmentSlot.LEGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS);
        armor(ly, all, wish, net.minecraft.world.entity.EquipmentSlot.FEET, Items.IRON_BOOTS, Items.DIAMOND_BOOTS);
        if (!all.has(Items.SHEARS) && Roadmap.has(ly, "pen")) wish.add(Items.SHEARS);
        if (axe < 3) wish.add(Items.IRON_AXE);
        if (pick < 4) wish.add(Items.DIAMOND_PICKAXE);
        if (sword < 4) wish.add(Items.DIAMOND_SWORD);
        for (Item it : wish) if (CraftTask.canMake(ly, level, it)) return it;
        return null;
    }

    /** Thêm giáp sắt (rồi kim cương) vào danh sách muốn làm nếu đang mặc đồ kém hơn. */
    private static void armor(LyMeowzEntity ly, LyInventory all, List<Item> wish, net.minecraft.world.entity.EquipmentSlot slot, Item iron, Item diamond) {
        int worn = Tools.armorRank(ly.getItemBySlot(slot));
        for (int i = 0; i < all.getContainerSize(); i++) {
            ItemStack s = all.getItem(i);
            var eq = s.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            if (eq != null && eq.slot() == slot) worn = Math.max(worn, Tools.armorRank(s));
        }
        if (worn < Tools.armorRank(new ItemStack(iron))) wish.add(iron);
        else if (worn < Tools.armorRank(new ItemStack(diamond))) wish.add(diamond);
    }

    public static int bestTier(LyInventory inv, Tools.Kind kind) {
        int best = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && Tools.isKind(s, kind)) best = Math.max(best, Tools.tier(s));
        }
        return best;
    }

    /** Tóm tắt túi đồ bằng tiếng Việt. */
    public static String summary(LyInventory inv) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
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
}
