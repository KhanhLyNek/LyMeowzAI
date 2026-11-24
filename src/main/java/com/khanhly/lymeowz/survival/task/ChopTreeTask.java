package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.survival.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chặt một cái cây tự nhiên: đi tới gốc, chặt từ dưới lên những khúc với tới được, rồi trồng lại cây con nếu có.
 * Chỉ chặt cây có lá tự mọc (không phải lá người chơi đặt) → không phá nhà gỗ của người chơi.
 */
public class ChopTreeTask extends Task {
    private final BlockPos base;
    private final List<BlockPos> logs;
    private final BlockBreaker breaker = new BlockBreaker();
    private int stuck;
    private int chopped;

    public ChopTreeTask(LyMeowzEntity ly, BlockPos base, List<BlockPos> logs) {
        super(ly);
        this.base = base;
        this.logs = new ArrayList<>(logs);
        this.timeout = 20 * 90;
    }

    @Override
    public boolean succeeded() {
        return chopped > 0;
    }

    @Override
    public String name() {
        return "chặt cây";
    }

    @Override
    public void start(ServerLevel level) {
        ly.holdBestFor(level.getBlockState(base));
        ly.walkTo(base, 1.0);
    }

    @Override
    protected boolean run(ServerLevel level) {
        // Đang chặt dở một khúc.
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            if (r == BlockBreaker.Result.DONE) chopped++;
            breaker.cancel(level, ly);
        }
        logs.removeIf(p -> !level.getBlockState(p).is(BlockTags.LOGS));
        if (logs.isEmpty()) return false;

        // Khúc thấp nhất trong tầm với.
        BlockPos next = logs.stream().filter(p -> BlockBreaker.inReach(ly, p))
                .min(Comparator.comparingInt(BlockPos::getY)).orElse(null);
        if (next != null) {
            stuck = 0;
            ly.holdBestFor(level.getBlockState(next));
            breaker.start(next);
            return true;
        }
        // Không với tới: đi lại gần khúc thấp nhất còn lại.
        BlockPos lowest = logs.stream().min(Comparator.comparingInt(BlockPos::getY)).get();
        if (lowest.getY() > ly.getBlockY() + 4) return false; // phần ngọn quá cao, thôi bỏ
        if (ly.getNavigation().isDone()) {
            if (++stuck > 3) return false;
            ly.walkTo(lowest, 1.0);
        }
        return true;
    }

    @Override
    public void stop(ServerLevel level) {
        breaker.cancel(level, ly);
        if (chopped > 0) replant(level);
    }

    /** Trồng lại cây con ở gốc cũ nếu có cây con trong túi. */
    private void replant(ServerLevel level) {
        if (!level.getBlockState(base).isAir() || !level.getBlockState(base.below()).is(BlockTags.DIRT)) return;
        ItemStack sapling = ly.inv().takeOne(s -> s.is(ItemTags.SAPLINGS));
        if (sapling.isEmpty()) return;
        if (sapling.getItem() instanceof BlockItem bi) {
            BlockState st = bi.getBlock().defaultBlockState();
            if (st.canSurvive(level, base)) {
                ly.placeBlock(level, base, st);
                return;
            }
        }
        ly.inv().put(sapling);
    }

    // ------------------------------------------------------------------ tìm cây

    /** Cây tự nhiên gần nhất (gốc + các khúc gỗ), hoặc null. */
    public static ChopTreeTask find(LyMeowzEntity ly, ServerLevel level, BlockPos center, int radius, Set<BlockPos> blacklist) {
        BlockPos feet = ly.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int x = center.getX() + dx, z = center.getZ() + dz;
            for (int y = feet.getY() - 3; y <= feet.getY() + 3; y++) {
                m.set(x, y, z);
                if (!level.isLoaded(m)) continue;
                BlockState st = level.getBlockState(m);
                if (!st.is(BlockTags.LOGS)) continue;
                if (level.getBlockState(m.below()).is(BlockTags.LOGS)) continue; // không phải gốc
                if (!level.getBlockState(m.below()).is(BlockTags.DIRT)) continue; // gốc phải mọc trên đất
                if (blacklist.contains(m)) continue;
                candidates.add(m.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
        for (BlockPos base : candidates) {
            List<BlockPos> tree = treeLogs(level, base);
            if (tree.size() >= 3 && hasNaturalLeaves(level, tree)) return new ChopTreeTask(ly, base, tree);
            blacklist.add(base);
        }
        return null;
    }

    /** Các khúc gỗ dính liền với gốc (tối đa 64). */
    private static List<BlockPos> treeLogs(ServerLevel level, BlockPos base) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(base);
        seen.add(base);
        while (!q.isEmpty() && out.size() < 64) {
            BlockPos p = q.poll();
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = p.offset(dx, dy, dz);
                if (seen.add(n) && Math.abs(n.getX() - base.getX()) <= 4 && Math.abs(n.getZ() - base.getZ()) <= 4
                        && level.getBlockState(n).is(BlockTags.LOGS)) q.add(n);
            }
        }
        return out;
    }

    /** Có ít nhất 4 khối lá tự mọc (không phải lá người chơi đặt) quanh các khúc trên cùng. */
    private static boolean hasNaturalLeaves(ServerLevel level, List<BlockPos> tree) {
        int top = tree.stream().mapToInt(BlockPos::getY).max().orElse(0);
        int natural = 0;
        for (BlockPos log : tree) {
            if (log.getY() < top - 2) continue;
            for (BlockPos p : BlockPos.betweenClosed(log.offset(-2, -1, -2), log.offset(2, 2, 2))) {
                BlockState st = level.getBlockState(p);
                if (st.is(BlockTags.LEAVES) && st.hasProperty(LeavesBlock.PERSISTENT) && !st.getValue(LeavesBlock.PERSISTENT)) {
                    if (++natural >= 4) return true;
                }
            }
        }
        return false;
    }

    public static double usefulTool(ItemStack s, BlockState log) {
        return Tools.isKind(s, Tools.Kind.AXE) ? Tools.scoreFor(s, log) : 0;
    }
}
