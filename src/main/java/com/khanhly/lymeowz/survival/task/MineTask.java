package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.survival.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Đào đá/quặng lộ thiên: chỉ đào đá tự nhiên (đá, đá hoa cương, đá phiến sâu…) và quặng than/sắt,
 * chỉ đào ngang tầm hoặc cao hơn chân mình (không đào xuống dưới), không đào gần nhà.
 */
public class MineTask extends Task {
    private final int wanted;
    private final BlockPos center;
    private final int radius;
    private final Set<BlockPos> blacklist;
    private final BlockBreaker breaker = new BlockBreaker();
    private BlockPos target;
    private int mined;
    private int walkTries;

    public MineTask(LyMeowzEntity ly, int wanted, BlockPos center, int radius, Set<BlockPos> blacklist) {
        super(ly);
        this.wanted = wanted;
        this.center = center;
        this.radius = radius;
        this.blacklist = blacklist;
        this.timeout = 20 * 120;
    }

    @Override
    public boolean succeeded() {
        return mined > 0;
    }

    @Override
    public String name() {
        return "đào đá";
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            if (r == BlockBreaker.Result.DONE) mined++;
            if (r == BlockBreaker.Result.FAILED) blacklist.add(target);
            breaker.cancel(level, ly);
            target = null;
        }
        if (mined >= wanted) return false;

        if (target == null || !isMineable(ly, level, target)) {
            target = findTarget(ly, level, center, radius, blacklist);
            walkTries = 0;
            if (target == null) return false;
        }
        if (BlockBreaker.inReach(ly, target)) {
            ly.holdBestFor(level.getBlockState(target));
            breaker.start(target);
            return true;
        }
        if (ly.getNavigation().isDone()) {
            if (++walkTries > 3) { blacklist.add(target); target = null; return true; }
            ly.walkTo(target, 1.0);
        }
        return true;
    }

    @Override
    public void stop(ServerLevel level) {
        breaker.cancel(level, ly);
    }

    // ------------------------------------------------------------------ chọn block

    public static boolean isMineable(LyMeowzEntity ly, ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        boolean ore = st.is(BlockItemTags.COAL_ORES.block()) || st.is(BlockTags.IRON_ORES);
        if (!ore && !st.is(BlockTags.BASE_STONE_OVERWORLD)) return false;
        if (p.getY() < ly.getBlockY()) return false;          // không đào xuống dưới chân
        if (p.getY() > ly.getBlockY() + 3) return false;
        if (ly.isProtected(p)) return false;                   // gần nhà
        // Phải có dụng cụ đào được ra đồ.
        ItemStack tool = ly.bestToolFor(st);
        if (st.requiresCorrectToolForDrops() && (tool.isEmpty() || !tool.isCorrectToolForDrops(st))) return false;
        // Phải lộ ra (một mặt giáp không khí).
        for (Direction d : Direction.values()) if (level.getBlockState(p.relative(d)).isAir()) return true;
        return false;
    }

    public static BlockPos findTarget(LyMeowzEntity ly, ServerLevel level, BlockPos center, int radius, Set<BlockPos> blacklist) {
        BlockPos feet = ly.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int r = Math.min(radius, 16);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = 0; dy <= 3; dy++) {
            m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
            if (m.distSqr(center) > (double) radius * radius || !level.isLoaded(m) || blacklist.contains(m)) continue;
            if (!isMineable(ly, level, m)) continue;
            BlockState st = level.getBlockState(m);
            double score = m.distSqr(feet);
            if (st.is(BlockItemTags.COAL_ORES.block()) || st.is(BlockTags.IRON_ORES)) score -= 400; // ưu tiên quặng
            if (score < bestScore) { bestScore = score; best = m.immutable(); }
        }
        return best;
    }

    public static boolean hasPickaxe(LyMeowzEntity ly) {
        return ly.countAll(s -> Tools.isKind(s, Tools.Kind.PICKAXE)) > 0;
    }
}
