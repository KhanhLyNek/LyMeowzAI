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
    private BlockPos walkingTo;
    private int centerTries;
    private int walkTicks;

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
        // Đứng vào giữa ô trước đã (đứng lệch thì khối xây lên sẽ đè vào người).
        if (Math.abs(ly.getX() - (feet.getX() + 0.5)) > 0.2 || Math.abs(ly.getZ() - (feet.getZ() + 0.5)) > 0.2) {
            if (++centerTries > 3) ly.setPos(feet.getX() + 0.5, ly.getY(), feet.getZ() + 0.5);
            else ly.getNavigation().moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.6);
            cooldown = 4;
            return;
        }
        centerTries = 0;
        for (BlockPos w : walls) {
            BlockState st = level.getBlockState(w);
            if (!st.canBeReplaced()) continue;
            if (ly.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(w))) continue;
            ItemStack b = ly.inv().takeOne(HouseTask.FILL);
            if (b.isEmpty() || !(b.getItem() instanceof BlockItem bi)) return;
            ly.getNavigation().stop();
            ly.placeBlock(level, w, bi.getBlock().defaultBlockState());
            placed.add(w.immutable());
            save(ly, level);
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
            BlockPos p = placed.get(placed.size() - 1);
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.canBeReplaced() || st.hasBlockEntity() || !level.isLoaded(p)) { placed.remove(placed.size() - 1); walkingTo = null; save(ly, level); continue; }
            if (!BlockBreaker.inReach(ly, p)) {
                // Đã đi xa khỏi hộp (bị đẩy, đi đánh quái...): quay lại chỗ đó mà dỡ, đừng bỏ lại cái hộp đất giữa đồng.
                if (!p.equals(walkingTo)) { walkingTo = p; walkTicks = 0; }
                if (++walkTicks > 20 * 30) { placed.remove(placed.size() - 1); walkingTo = null; save(ly, level); continue; }
                if (ly.getNavigation().isDone() || walkTicks % 40 == 0) ly.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0);
                return true;
            }
            ly.getNavigation().stop();
            placed.remove(placed.size() - 1);
            walkingTo = null;
            save(ly, level);
            breaker.start(p);
            return true;
        }
        return false;
    }

    /** Ghi lại hộp trú vào dữ liệu lưu. */
    private void save(LyMeowzEntity ly, ServerLevel level) {
        var d = ly.data();
        if (d == null) return;
        String dim = com.khanhly.lymeowz.compat.Compat.dimId(level.dimension());
        d.campBlocks.clear();
        for (BlockPos p : placed) d.campBlocks.add(new com.khanhly.lymeowz.world.CompanionStore.Spot(dim, p.getX(), p.getY(), p.getZ()));
        com.khanhly.lymeowz.world.CompanionStore.markDirty();
    }

    /** Sau khi server khởi động lại: nhớ lại hộp trú còn dựng dở. @return true nếu có hộp cần dỡ. */
    public boolean restore(LyMeowzEntity ly, ServerLevel level) {
        var d = ly.data();
        if (d == null || d.campBlocks.isEmpty()) return false;
        String dim = com.khanhly.lymeowz.compat.Compat.dimId(level.dimension());
        placed.clear();
        for (var s : d.campBlocks) if (dim.equals(s.dim)) placed.add(new BlockPos(s.x, s.y, s.z));
        return !placed.isEmpty();
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
