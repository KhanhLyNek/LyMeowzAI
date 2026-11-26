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
    private int hidden;
    private int lookAgain;

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
            if (r == BlockBreaker.Result.NOT_VISIBLE || r == BlockBreaker.Result.OUT_OF_REACH) {
                // Không nhìn thấy mặt block từ chỗ đang đứng: bước tới trước mặt lộ ra của nó rồi thử lại.
                if (++hidden > 2) { blacklist.add(target); target = null; hidden = 0; return true; }
                BlockPos face = exposedFace(level, target);
                if (face != null) ly.walkTo(face, 1.0);
                lookAgain = 30;
                return true;
            }
            target = null;
            hidden = 0;
        }
        if (lookAgain > 0) {
            lookAgain--;
            if (target != null && (ly.getNavigation().isDone() || lookAgain == 0) && BlockBreaker.inReach(ly, target)) {
                lookAgain = 0;
                ly.holdBestFor(level.getBlockState(target));
                breaker.start(target);
            }
            return true;
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
        // Phải lộ ra (một mặt giáp không khí). (Kiểm tra rẻ trước, đắt sau.)
        boolean exposed = false;
        for (Direction d : Direction.values()) if (level.getBlockState(p.relative(d)).isAir()) { exposed = true; break; }
        if (!exposed) return false;
        // Phải có dụng cụ đào được ra đồ.
        ItemStack tool = ly.bestToolFor(st);
        if (st.requiresCorrectToolForDrops() && (tool.isEmpty() || !tool.isCorrectToolForDrops(st))) return false;
        return !ly.isProtected(p);                             // gần nhà
    }

    public static BlockPos findTarget(LyMeowzEntity ly, ServerLevel level, BlockPos center, int radius, Set<BlockPos> blacklist) {
        BlockPos feet = ly.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int r = Math.min(radius, 16);
        BlockPos[] out = {null};
        double[] bs = {Double.MAX_VALUE};
        com.khanhly.lymeowz.util.BlockScan.forEach(level, feet.offset(-r, 0, -r), feet.offset(r, 3, r),
                st -> st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockItemTags.COAL_ORES.block()) || st.is(BlockTags.IRON_ORES),
                (m, st) -> {
                    boolean ore = !st.is(BlockTags.BASE_STONE_OVERWORLD);
                    double score = m.distSqr(feet) - (ore ? 400 : 0); // ưu tiên quặng
                    if (score >= bs[0]) return true;                  // không hơn được thì khỏi xét kỹ
                    if (m.distSqr(center) > (double) radius * radius || blacklist.contains(m) || !isMineable(ly, level, m)) return true;
                    if (!BlockBreaker.canSee(ly, m)) score += 300;   // ưu tiên chỗ đang nhìn thấy
                    if (score < bs[0]) { bs[0] = score; out[0] = m.immutable(); }
                    return true;
                });
        return out[0];
    }

    /** Ô không khí cạnh block (chỗ đứng nhìn vào mặt lộ ra), gần cô ấy nhất. */
    private BlockPos exposedFace(ServerLevel level, BlockPos p) {
        BlockPos best = null;
        double bd = 1e9;
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (!level.getBlockState(n).isAir()) continue;
            BlockPos stand = n;
            while (stand.getY() > p.getY() - 3 && level.getBlockState(stand.below()).isAir()) stand = stand.below();
            double dist = stand.distSqr(ly.blockPosition());
            if (dist < bd) { bd = dist; best = stand; }
        }
        return best;
    }

    public static boolean hasPickaxe(LyMeowzEntity ly) {
        return ly.countAll(s -> Tools.isKind(s, Tools.Kind.PICKAXE)) > 0;
    }
}
