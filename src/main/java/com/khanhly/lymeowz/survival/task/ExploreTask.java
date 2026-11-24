package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Đi khám phá một vùng chưa từng đến (trong bán kính tự do quanh chủ), vừa đi vừa nhìn & ghi nhớ,
 * rồi quay về. Thành công nếu phát hiện được nơi mới.
 */
public class ExploreTask extends Task {
    private final BlockPos target;
    private final int placesBefore;
    private int stuck;

    public ExploreTask(LyMeowzEntity ly, BlockPos target) {
        super(ly);
        this.target = target;
        this.placesBefore = ly.mind().data().places.size();
        this.timeout = 20 * 50;
    }

    @Override
    public String name() {
        return "khám phá";
    }

    @Override
    public void start(ServerLevel level) {
        ly.walkTo(target, 1.0);
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (ly.blockPosition().closerThan(target, 4)) return false;
        if (ly.getNavigation().isDone()) {
            if (++stuck > 4) return false;
            // Đi từng chặng ~20 block về phía điểm đến.
            var dir = net.minecraft.world.phys.Vec3.atBottomCenterOf(target).subtract(ly.position());
            var hop = ly.position().add(dir.normalize().scale(Math.min(20, dir.length())));
            BlockPos hp = BlockPos.containing(hop);
            if (!level.isLoaded(hp)) return false;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hp.getX(), hp.getZ());
            ly.walkTo(new BlockPos(hp.getX(), y, hp.getZ()), 1.0);
        }
        return true;
    }

    @Override
    public boolean succeeded() {
        return ly.mind().data().places.size() > placesBefore;
    }

    /** Chọn một vùng chưa đi qua, cách chủ ≤ min(60, followRadius), tránh chỗ từng gặp nguy hiểm. */
    public static BlockPos pickTarget(LyMeowzEntity ly, ServerLevel level, BlockPos owner) {
        int max = Math.min(60, LyConfig.get().stayNearPlayer + 20);
        BlockPos best = null;
        double bestScore = -1e9;
        for (int i = 0; i < 16; i++) {
            double ang = ly.getRandom().nextDouble() * Math.PI * 2;
            double d = 20 + ly.getRandom().nextDouble() * (max - 20);
            int x = owner.getX() + (int) (Math.cos(ang) * d), z = owner.getZ() + (int) (Math.sin(ang) * d);
            BlockPos col = new BlockPos(x, owner.getY(), z);
            if (!level.isLoaded(col)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - owner.getY()) > 20) continue;
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getFluidState(p.below()).isEmpty()) continue;
            double score = (ly.mind().explored(p) ? 0 : 10) - ly.mind().dangerNear(p) * 5 - d * 0.05;
            if (score > bestScore) { bestScore = score; best = p; }
        }
        return best;
    }

    @SuppressWarnings("unused")
    private static final Mind.PlaceKind[] NOTE = Mind.PlaceKind.values();
}
