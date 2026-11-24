package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.BlockBreaker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gom một loại block quanh đây như người chơi (hái hoa, xúc cát, đào sỏi lấy đá lửa...):
 * chỉ phá block nhìn thấy được, cho tới khi trong túi đủ số món cần.
 */
public class GatherTask extends Task {
    private final String name;
    private final Predicate<BlockState> block;
    private final Predicate<ItemStack> want;
    private final int count, maxBlocks, radius;
    private final BlockBreaker breaker = new BlockBreaker();
    private final Set<BlockPos> skip = new HashSet<>();
    private BlockPos target;
    private int broken, walk;

    public GatherTask(LyMeowzEntity ly, String name, Predicate<BlockState> block, Predicate<ItemStack> want, int count, int maxBlocks, int radius) {
        super(ly);
        this.name = name;
        this.block = block;
        this.want = want;
        this.count = count;
        this.maxBlocks = maxBlocks;
        this.radius = radius;
        this.timeout = 20 * 120;
    }

    @Override public String name() { return name; }

    @Override public boolean succeeded() { return broken > 0; }

    @Override
    protected boolean run(ServerLevel level) {
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            if (r == BlockBreaker.Result.DONE) broken++;
            else skip.add(breaker.target() != null ? breaker.target() : target);
            breaker.cancel(level, ly);
            target = null;
        }
        if (ly.countAll(want) >= count || broken >= maxBlocks) return false;
        if (target == null || !block.test(level.getBlockState(target))) {
            target = find(level);
            walk = 0;
            if (target == null) return false;
        }
        if (BlockBreaker.inReach(ly, target)) {
            ly.holdBestFor(level.getBlockState(target));
            breaker.start(target);
            return true;
        }
        if (ly.getNavigation().isDone()) {
            if (++walk > 4) { skip.add(target); target = null; return true; }
            ly.walkTo(target.above(), 1.0);   // đi tới đứng cạnh / trên block, không phải vào trong nó
        }
        return true;
    }

    private BlockPos find(ServerLevel level) {
        return nearest(ly, level, block, radius, skip);
    }

    /** Block phù hợp gần nhất (lộ ra ngoài, không thuộc công trình nào). */
    public static BlockPos nearest(LyMeowzEntity ly, ServerLevel level, Predicate<BlockState> block, int radius, Set<BlockPos> skip) {
        BlockPos c = ly.blockPosition();
        BlockPos best = null;
        double bd = 1e9;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-radius, -4, -radius), c.offset(radius, 4, radius))) {
            if (!block.test(level.getBlockState(p)) || skip.contains(p)) continue;
            if (!level.getBlockState(p.above()).isAir() && !level.getBlockState(p.above()).canBeReplaced()) continue;
            if (ly.isProtected(p)) continue;
            double d = p.distSqr(c);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    @Override
    public void stop(ServerLevel level) {
        breaker.cancel(level, ly);
    }
}
