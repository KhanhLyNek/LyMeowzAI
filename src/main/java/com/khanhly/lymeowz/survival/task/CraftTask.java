package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.survival.Crafting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Chế tạo một món: làm ván/que trên tay; món cần bàn 3x3 thì dùng bàn có sẵn gần đó,
 * hoặc đặt bàn của mình xuống, chế xong thì chặt bàn thu lại (không bỏ rác lại thế giới).
 */
public class CraftTask extends Task {
    private final Item target;
    private List<Crafting.Step> steps;
    private int index;
    private int wait;
    private BlockPos table;
    private boolean placedTable;
    private final BlockBreaker breaker = new BlockBreaker();
    private boolean cleaning;

    public CraftTask(LyMeowzEntity ly, Item target) {
        super(ly);
        this.target = target;
        this.timeout = 20 * 45;
    }

    @Override
    public boolean succeeded() {
        return steps != null && index >= steps.size();
    }

    @Override
    public String name() {
        return "chế tạo";
    }

    @Override
    public void start(ServerLevel level) {
        ly.stowHand();
        table = ly.findBlockNear(level, Blocks.CRAFTING_TABLE, 5);
        steps = Crafting.plan(Crafting.copy(ly.inv()), target, table != null);
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (cleaning) return cleanUp(level);
        if (steps == null) return false;
        if (index >= steps.size()) {
            ly.announceCrafted(target);
            if (placedTable && table != null) { cleaning = true; return true; }
            return false;
        }
        Crafting.Step step = steps.get(index);
        if (step.needsTable()) {
            if (table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
                table = null;
                if (ly.inv().takeOne(s -> s.is(Items.CRAFTING_TABLE)).isEmpty()) return false;
                table = ly.placeNear(level, Blocks.CRAFTING_TABLE.defaultBlockState());
                if (table == null) { ly.inv().put(new net.minecraft.world.item.ItemStack(Items.CRAFTING_TABLE)); return false; }
                placedTable = true;
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

    /** Chặt bàn chế tạo mình đã đặt để mang theo. */
    private boolean cleanUp(ServerLevel level) {
        if (!level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) return false;
        if (breaker.target() == null) {
            if (!BlockBreaker.inReach(ly, table)) {
                if (ly.getNavigation().isDone()) ly.walkTo(table, 1.0);
                return true;
            }
            ly.holdBestFor(level.getBlockState(table));
            breaker.start(table);
        }
        return breaker.tick(ly, level) == BlockBreaker.Result.WORKING;
    }

    @Override
    public void stop(ServerLevel level) {
        breaker.cancel(level, ly);
        // Bị ngắt giữa chừng mà bàn vẫn còn: thu bàn về luôn để không bỏ lại.
        if (placedTable && table != null && level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            level.removeBlock(table, false);
            ly.inv().put(new net.minecraft.world.item.ItemStack(Items.CRAFTING_TABLE));
        }
    }

    /** Có làm được không (đủ nguyên liệu)? */
    public static boolean canMake(LyMeowzEntity ly, ServerLevel level, Item target) {
        boolean table = ly.findBlockNear(level, Blocks.CRAFTING_TABLE, 5) != null;
        return Crafting.plan(Crafting.copy(ly.inventoryWithHand()), target, table) != null;
    }
}
