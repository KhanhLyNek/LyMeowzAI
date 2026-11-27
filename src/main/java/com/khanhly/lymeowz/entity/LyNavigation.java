package com.khanhly.lymeowz.entity;

import com.khanhly.lymeowz.util.Perf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Điều hướng của cô ấy: như mob thường, nhưng không tìm lại đường tới cùng một chỗ liên tục.
 * <p>
 * Nhiều việc gọi "đi tới X" mỗi tick khi đang đứng yên. Nếu X không tới được, đường tìm ra rỗng/đã xong → đứng yên →
 * tick sau lại tìm. Mỗi lần tìm đường có thể duyệt hàng trăm ô. Ở đây: cùng đích, cô ấy chưa đi được bao xa, mới tìm
 * cách đây chưa tới nửa giây → dùng lại kết quả cũ.
 */
public class LyNavigation extends GroundPathNavigation {
    private Set<BlockPos> lastTargets;
    private int lastAccuracy;
    private long lastAt = Long.MIN_VALUE;
    private Vec3 lastFrom = Vec3.ZERO;
    private Path lastPath;

    public LyNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange) {
        long now = level.getGameTime();
        if (now - lastAt < 10 && accuracy == lastAccuracy && targets.equals(lastTargets)
                && mob.position().distanceToSqr(lastFrom) < 1.0) return lastPath;
        long t = Perf.t();
        Path p = super.createPath(targets, regionOffset, offsetUpward, accuracy, followRange);
        Perf.add("tìm đường", t);
        lastTargets = Set.copyOf(targets);
        lastAccuracy = accuracy;
        lastAt = now;
        lastFrom = mob.position();
        lastPath = p;
        return p;
    }
}
