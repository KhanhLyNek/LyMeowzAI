package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.survival.Crafting;
import com.khanhly.lymeowz.survival.Roadmap;
import com.khanhly.lymeowz.survival.Survival;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Chế tạo đồ như người chơi: đứng ở bàn thì làm luôn một lượt mọi thứ đang cần (rìu, cuốc, kiếm...),
 * không đặt bàn → làm một món → đập bàn → đặt lại. Bàn mình đặt thì để đó dùng tiếp; khi nào đi xa
 * (khám phá, tìm đất làm nhà...) mới quay lại thu bàn ({@link PickupTable}).
 */
public class CraftTask extends Task {
    private final Item target;
    private Item current;
    private List<Crafting.Step> steps;
    private int index;
    private int wait;
    private BlockPos table;
    private int extra;
    private final java.util.Set<Item> made = new java.util.LinkedHashSet<>();

    public CraftTask(LyMeowzEntity ly, Item target) {
        super(ly);
        this.target = target;
        this.current = target;
        this.timeout = 20 * 90;
    }

    @Override
    public boolean succeeded() {
        return made.contains(target);
    }

    @Override
    public String name() {
        return "chế tạo";
    }

    @Override
    public void start(ServerLevel level) {
        ly.stowHand();
        table = findTable(ly, level);
        steps = Crafting.plan(Crafting.copy(ly.inv()), target, table != null);
        if (steps == null && table == null) steps = Crafting.plan(Crafting.copy(ly.inv()), target, false);
    }

    /** Bàn gần đây, hoặc bàn mình để lại chỗ làm việc (trong 32 ô thì đi tới đó dùng). */
    static BlockPos findTable(LyMeowzEntity ly, ServerLevel level) {
        BlockPos t = ly.findBlockNear(level, Blocks.CRAFTING_TABLE, 8);
        if (t != null) return t;
        BlockPos w = ly.workTable();
        if (w != null && !level.getBlockState(w).is(Blocks.CRAFTING_TABLE)) { ly.setWorkTable(null); w = null; }
        return w != null && w.closerThan(ly.blockPosition(), 32) ? w : null;
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (steps == null) return false;
        if (index >= steps.size()) {
            made.add(current);
            ly.announceCrafted(current);
            // Đã đứng ở bàn rồi thì làm luôn những món khác đang cần.
            if (table != null && extra < 6 && level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
                Item next = Survival.nextCraft(ly, level, ly.inventoryWithHand(), Roadmap.current(ly));
                if (next != null && !made.contains(next)) {
                    List<Crafting.Step> more = Crafting.plan(Crafting.copy(ly.inv()), next, true);
                    if (more != null && !more.isEmpty()) {
                        extra++;
                        current = next;
                        steps = more;
                        index = 0;
                        wait = 8;
                        return true;
                    }
                }
            }
            return false;
        }
        Crafting.Step step = steps.get(index);
        if (step.needsTable()) {
            if (table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
                table = null;
                if (ly.inv().takeOne(s -> s.is(Items.CRAFTING_TABLE)).isEmpty()) return false;
                table = ly.placeNear(level, Blocks.CRAFTING_TABLE.defaultBlockState());
                if (table == null) { ly.inv().put(new net.minecraft.world.item.ItemStack(Items.CRAFTING_TABLE)); return false; }
                ly.setWorkTable(table); // để đó làm việc tiếp, đi xa mới thu
                wait = 10;
                return true;
            }
            if (ly.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(table)) > 3.5 * 3.5) {
                if (ly.getNavigation().isDone()) ly.walkTo(table, 1.0);
                return true;
            }
            ly.getLookControl().setLookAt(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5);
        }
        if (wait-- > 0) return true;
        ly.getNavigation().stop();
        ly.swingArm();
        if (!Crafting.execute(ly.inv(), step)) return false;
        level.playSound(null, ly.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4f, 1.6f);
        index++;
        wait = 12;
        return true;
    }

    /** Có làm được không (đủ nguyên liệu)? */
    public static boolean canMake(LyMeowzEntity ly, ServerLevel level, Item target) {
        boolean table = findTable(ly, level) != null;
        return Crafting.plan(Crafting.copy(ly.inventoryWithHand()), target, table) != null;
    }

    /** Sắp đi xa: quay lại thu bàn chế tạo mình đã đặt (như người chơi đập bàn mang theo). */
    public static class PickupTable extends Task {
        private final BlockPos table;
        private final BlockBreaker breaker = new BlockBreaker();

        public PickupTable(LyMeowzEntity ly, BlockPos table) {
            super(ly);
            this.table = table;
            this.timeout = 20 * 40;
        }

        @Override public String name() { return "thu bàn chế tạo"; }

        @Override public boolean succeeded() { return !(ly.level().getBlockState(table).is(Blocks.CRAFTING_TABLE)); }

        @Override
        protected boolean run(ServerLevel level) {
            if (!level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) { done(); return false; }
            if (breaker.target() == null) {
                if (!BlockBreaker.inReach(ly, table)) {
                    if (ly.getNavigation().isDone()) ly.walkTo(table, 1.0);
                    return true;
                }
                ly.getNavigation().stop();
                ly.holdBestFor(level.getBlockState(table));
                breaker.start(table);
            }
            if (breaker.tick(ly, level) == BlockBreaker.Result.WORKING) return true;
            if (!level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) done();
            return false;
        }

        private void done() {
            if (table.equals(ly.workTable())) ly.setWorkTable(null);
            ly.forgetOldTable(table);
        }

        @Override
        public void stop(ServerLevel level) {
            breaker.cancel(level, ly);
            if (age >= timeout && level.getBlockState(table).is(Blocks.CRAFTING_TABLE) && ly.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(table)) < 64) {
                level.removeBlock(table, false);
                ly.inv().put(new net.minecraft.world.item.ItemStack(Items.CRAFTING_TABLE));
                done();
            }
        }
    }
}
