package com.khanhly.lymeowz.survival;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.task.HouseTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Cắm trại tại chỗ khi phải đợi ai đó hoặc khi đêm xuống mà không có giường:
 * đốt lửa trại / cắm đuốc cho sáng; có quái thì tự xây quanh mình một "hộp" đá 1×1 để trú,
 * hết nguy hiểm (hoặc người đợi tới nơi) thì đập ra lấy lại đá.
 */
public class Camp {
    private BlockPos center;
    private final List<BlockPos> placed = new ArrayList<>();
    private final BlockBreaker breaker = new BlockBreaker();
    private boolean lit;
    private int cooldown;

    public BlockPos center() { return center; }

    public boolean enclosed() { return !placed.isEmpty(); }

    public void reset(BlockPos at) {
        center = at == null ? null : at.immutable();
        lit = false;
    }

    /** Ở yên tại trại. {@code danger}: có quái / đêm tối ngoài trời. */
    public void hold(LyMeowzEntity ly, ServerLevel level, boolean dark, boolean danger) {
        if (center == null) center = ly.blockPosition();
        if (cooldown-- > 0) return;
        BlockPos feet = ly.blockPosition();
        if (dark && !lit) {
            lit = true;
            BlockPos spot = freeSpot(level, feet);
            if (spot != null) {
                if (ly.inv().has(Items.CAMPFIRE) || craft(ly, Items.CAMPFIRE)) {
                    ly.inv().consume(Items.CAMPFIRE, 1);
                    ly.placeBlock(level, spot, Blocks.CAMPFIRE.defaultBlockState());
                } else if (ly.inv().has(Items.TORCH) || craft(ly, Items.TORCH)) {
                    ly.inv().consume(Items.TORCH, 1);
                    ly.placeBlock(level, spot, Blocks.TORCH.defaultBlockState());
                }
                cooldown = 8;
                return;
            }
        }
        if (!danger) return;
        // Dựng hộp trú: 4 phía × 2 tầng + nóc.
        List<BlockPos> walls = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) { walls.add(feet.relative(d)); walls.add(feet.relative(d).above()); }
        walls.add(feet.above(2));
        for (BlockPos w : walls) {
            BlockState st = level.getBlockState(w);
            if (!st.canBeReplaced()) continue;
            ItemStack b = ly.inv().takeOne(HouseTask.FILL);
            if (b.isEmpty() || !(b.getItem() instanceof BlockItem bi)) return;
            ly.getNavigation().stop();
            ly.placeBlock(level, w, bi.getBlock().defaultBlockState());
            placed.add(w.immutable());
            cooldown = 3;
            return;
        }
    }

    /** Đập hộp trú lấy lại đá. @return true khi còn đang đập. */
    public boolean dismantle(LyMeowzEntity ly, ServerLevel level) {
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            breaker.cancel(level, ly);
        }
        while (!placed.isEmpty()) {
            BlockPos p = placed.remove(placed.size() - 1);
            if (level.getBlockState(p).isAir() || !BlockBreaker.inReach(ly, p)) continue;
            breaker.start(p);
            return true;
        }
        return false;
    }

    public void cancel(LyMeowzEntity ly, ServerLevel level) {
        breaker.cancel(level, ly);
    }

    private static BlockPos freeSpot(ServerLevel level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = feet.relative(d, 2);
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos q = p.above(dy);
                if (level.getBlockState(q).canBeReplaced() && level.getFluidState(q).isEmpty()
                        && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) return q;
            }
        }
        return null;
    }

    private static boolean craft(LyMeowzEntity ly, net.minecraft.world.item.Item item) {
        var steps = Crafting.plan(Crafting.copy(ly.inv()), item, false);
        if (steps == null) return false;
        for (var s : steps) if (!Crafting.execute(ly.inv(), s)) return false;
        return ly.inv().has(item);
    }
}
