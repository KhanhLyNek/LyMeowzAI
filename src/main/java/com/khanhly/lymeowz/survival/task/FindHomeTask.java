package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.house.Blueprint;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Set;

/**
 * Đi tìm đất làm nhà: cô ấy chỉ ưng đồng bằng, đồng hoa hướng dương, rừng hoa, đồng cỏ núi hoặc rừng anh đào.
 * Đang ở chỗ như vậy thì tìm một bãi bằng phẳng gần đó; không thì đi bộ (chạy) tới vùng như vậy gần nhất.
 */
public class FindHomeTask extends Task {
    public static final Set<ResourceKey<Biome>> BIOMES = Set.of(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.FLOWER_FOREST,
            Biomes.CHERRY_GROVE, Biomes.MEADOW);

    private final Blueprint bp;
    private BlockPos dest;
    private int phase;      // 0 tìm hướng, 1 đi, 2 dò bãi đất
    private int hops, stuck;
    private Vec3 last = Vec3.ZERO;

    /** true: đã có nhà, tìm chỗ xây thêm nhà mới ngay cạnh (không dọn nhà). */
    private final boolean nearHome;

    public FindHomeTask(LyMeowzEntity ly, Blueprint bp, boolean nearHome) {
        super(ly);
        this.bp = bp;
        this.nearHome = nearHome;
        this.timeout = 20 * 60 * 12;
    }

    @Override public String name() { return "tìm đất làm nhà"; }

    @Override public boolean succeeded() { return ly.data() != null && ly.data().blueprintAt != null; }

    public static boolean goodBiome(ServerLevel level, BlockPos p) {
        Holder<Biome> b = level.getBiome(p);
        return BIOMES.stream().anyMatch(b::is);
    }

    @Override
    public void start(ServerLevel level) {
        if (nearHome || goodBiome(level, ly.blockPosition())) { phase = 2; return; }
        var found = level.findClosestBiome3d(h -> BIOMES.stream().anyMatch(h::is), ly.blockPosition(), 1600, 32, 64);
        if (found == null) { phase = 2; return; } // quanh đây không có: đành chọn chỗ tốt nhất tại đây
        dest = found.getFirst();
        CompanionStore.Data d = ly.data();
        if (d != null) for (CompanionStore.Spot r : d.rejectedSites) {
            if (new BlockPos(r.x, r.y, r.z).distSqr(dest) < 64 * 64) {
                // Vùng này đã xem rồi, không ưng: thử bước ra xa hơn theo hướng đó.
                Vec3 dir = Vec3.atCenterOf(dest).subtract(ly.position()).normalize().scale(96);
                dest = BlockPos.containing(Vec3.atCenterOf(dest).add(dir));
            }
        }
        phase = 1;
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (phase == 1) return travel(level);
        if (phase == 2) return scan(level);
        return false;
    }

    private boolean travel(ServerLevel level) {
        Vec3 to = Vec3.atBottomCenterOf(dest);
        double dist = Math.sqrt(Math.pow(to.x - ly.getX(), 2) + Math.pow(to.z - ly.getZ(), 2));
        if (dist < 24 || goodBiome(level, ly.blockPosition()) && dist < 80) { phase = 2; ly.getNavigation().stop(); return true; }
        if (ly.getNavigation().isDone() || age % 60 == 0) {
            ly.setSprinting(true);
            Vec3 dir = to.subtract(ly.position());
            dir = new Vec3(dir.x, 0, dir.z).normalize().scale(Math.min(dist, 24));
            Vec3 hop = ly.position().add(dir);
            BlockPos hp = BlockPos.containing(hop);
            if (!level.isLoaded(hp)) { Compat.keepChunk(level, hp, 1); return true; }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hp.getX(), hp.getZ());
            if (!ly.getNavigation().moveTo(hop.x, y, hop.z, 1.0)) stuck++;
            hops++;
        }
        if (age % 40 == 0) {
            if (ly.position().distanceToSqr(last) < 1) stuck++;
            last = ly.position();
        }
        if (stuck > 6) {
            stuck = 0;
            // Kẹt (sông, vách núi): nhảy qua chỗ khác như người chơi bơi / leo qua.
            ly.teleportNear(level, ly.blockPosition().offset((int) Math.signum(to.x - ly.getX()) * 6, 0, (int) Math.signum(to.z - ly.getZ()) * 6), 1, 4);
        }
        return true;
    }

    private boolean scan(ServerLevel level) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos c = ly.blockPosition();
        for (int i = 0; i < 260; i++) {
            int x = c.getX() + ly.getRandom().nextIntBetweenInclusive(-40, 40);
            int z = c.getZ() + ly.getRandom().nextIntBetweenInclusive(-40, 40);
            double s = score(level, x, z);
            if (s < bestScore) { bestScore = s; best = new BlockPos(x, (int) lastGround, z); }
        }
        CompanionStore.Data d = ly.data();
        if (d == null) return false;
        if (best == null) {
            d.rejectedSites.add(new CompanionStore.Spot(Compat.dimId(level.dimension()), c.getX(), c.getY(), c.getZ()));
            if (d.rejectedSites.size() > 12) d.rejectedSites.remove(0);
            CompanionStore.markDirty();
            return false;
        }
        String dim = Compat.dimId(level.dimension());
        d.blueprint = bp.name;
        d.blueprintAt = new CompanionStore.Spot(dim, best.getX(), best.getY(), best.getZ());
        d.structures.put("plan:home", d.blueprintAt);
        BlockPos center = best.offset(bp.width / 2, 1, bp.length / 2);
        if (nearHome) { CompanionStore.markDirty(); return false; }
        // Từ giờ đây là chỗ ở của cô ấy.
        d.home = new CompanionStore.Spot(dim, center.getX(), center.getY(), center.getZ());
        CompanionStore.markDirty();
        ly.mind().remember(Mind.PlaceKind.HOME, center, 1);
        ly.mind().event(Mind.Event.FOUND_PLACE, null, "HOME", center);
        return false;
    }

    private double lastGround;

    /** Điểm của một bãi đất (càng nhỏ càng tốt), MAX nếu không xây được. Ghi y nền vào {@link #lastGround}. */
    private double score(ServerLevel level, int x0, int z0) {
        if (!level.isLoaded(new BlockPos(x0, 0, z0)) || !level.isLoaded(new BlockPos(x0 + bp.width, 0, z0 + bp.length))) return Double.MAX_VALUE;
        int w = bp.width, l = bp.length;
        int[] hs = new int[w * l];
        int logs = 0;
        for (int x = 0; x < w; x++) for (int z = 0; z < l; z++) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + x, z0 + z);
            BlockPos ground = new BlockPos(x0 + x, y - 1, z0 + z);
            BlockState g = level.getBlockState(ground);
            if (!g.getFluidState().isEmpty() || g.is(BlockTags.ICE)) return Double.MAX_VALUE;
            if (g.is(BlockTags.LOGS)) { logs++; y = groundBelowTree(level, ground); }
            hs[x * l + z] = y;
            if (ly.isProtected(ground) || ly.ownsBlock(ground)) return Double.MAX_VALUE;
        }
        int[] sorted = hs.clone();
        Arrays.sort(sorted);
        int median = sorted[sorted.length / 2];
        double rough = 0;
        for (int h : hs) {
            int dh = Math.abs(h - median);
            if (dh > 3) return Double.MAX_VALUE;
            rough += dh;
        }
        if (logs > 20) return Double.MAX_VALUE;
        if (!goodBiome(level, new BlockPos(x0 + w / 2, median, z0 + l / 2))) rough += 400;
        lastGround = median - 1; // lớp y=0 của bản vẽ là lớp cỏ/đất
        return rough + logs * 3 + Math.sqrt((x0 - ly.getX()) * (x0 - ly.getX()) + (z0 - ly.getZ()) * (z0 - ly.getZ())) * 0.5;
    }

    private static int groundBelowTree(ServerLevel level, BlockPos log) {
        BlockPos p = log;
        while (p.getY() > level.getMinY() && (level.getBlockState(p).is(BlockTags.LOGS) || level.getBlockState(p).is(BlockTags.LEAVES))) p = p.below();
        return p.getY() + 1;
    }
}
