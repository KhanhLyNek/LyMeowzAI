package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.survival.Builder;
import com.khanhly.lymeowz.survival.Crafting;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Dựng chuồng hàng rào 6×6 (trong lòng 4×4) có cổng ở phía bắc, cạnh nhà.
 * Thiếu hàng rào thì tự chế từ ván và que.
 */
public class PenTask extends Task {
    public static final int SIZE = 6;
    public static final int FENCES = 4 * (SIZE - 1) - 1;

    private final BlockPos corner;
    private final Builder builder = new Builder();
    private boolean crafted;

    public PenTask(LyMeowzEntity ly, BlockPos corner) {
        super(ly);
        this.corner = corner.immutable();
        this.timeout = 20 * 60 * 4;
    }

    @Override public String name() { return "làm chuồng"; }

    @Override public boolean succeeded() { return builder.done() && builder.placed() > 0; }

    public static BlockPos gate(BlockPos c) { return c.offset(2, 0, 0); }

    public static AABB inside(BlockPos c) {
        return new AABB(c.getX() + 1, c.getY() - 1, c.getZ() + 1, c.getX() + SIZE - 1, c.getY() + 3, c.getZ() + SIZE - 1);
    }

    /** Ván cần để chế đủ hàng rào + cổng (tính cả que). */
    public static int planksNeeded(LyMeowzEntity ly) {
        int fences = ly.inv().count(Items.OAK_FENCE);
        int crafts = Math.max(0, (FENCES - fences + 2) / 3);
        return crafts * 5 + (ly.inv().has(Items.OAK_FENCE_GATE) ? 0 : 4);
    }

    @Override
    public void start(ServerLevel level) {
        CompanionStore.Data d = ly.data();
        if (d != null) {
            d.structures.put("plan:pen", new CompanionStore.Spot(Compat.dimId(level.dimension()), corner.getX(), corner.getY(), corner.getZ()));
            CompanionStore.markDirty();
        }
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            if (x != 0 && z != 0 && x != SIZE - 1 && z != SIZE - 1) continue;
            BlockPos p = corner.offset(x, 0, z);
            if (p.equals(gate(corner))) continue;
            builder.add(p, s -> s.is(Items.OAK_FENCE));
        }
        builder.add(gate(corner), s -> s.is(Items.OAK_FENCE_GATE),
                s -> Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH));
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (!crafted) {
            crafted = true;
            boolean gateMissing = level.getBlockState(gate(corner)).canBeReplaced();
            int need = builder.needed(level) - (gateMissing ? 1 : 0) - ly.inv().count(Items.OAK_FENCE);
            if (need > 0) {
                var steps = Crafting.plan(Crafting.copy(ly.inv()), Items.OAK_FENCE, (need + 2) / 3, false);
                if (steps == null) return false;
                for (var s : steps) if (!Crafting.execute(ly.inv(), s)) return false;
            }
            if (gateMissing && !ly.inv().has(Items.OAK_FENCE_GATE)) {
                var steps = Crafting.plan(Crafting.copy(ly.inv()), Items.OAK_FENCE_GATE, false);
                if (steps == null) return false;
                for (var s : steps) if (!Crafting.execute(ly.inv(), s)) return false;
            }
            ly.swingArm();
            return true;
        }
        if (builder.tick(ly, level)) return true;
        if (builder.done() && inside(corner).inflate(0.3, 0, 0.3).contains(ly.position())) {
            // Lỡ đứng trong chuồng lúc rào xong: mở cổng đi ra rồi đóng lại.
            return walkOut(ly, level, corner, age);
        }
        if (builder.done()) {
            CompanionStore.Data d = ly.data();
            if (d != null) {
                d.structures.remove("plan:pen");
                d.structures.put("pen", new CompanionStore.Spot(Compat.dimId(level.dimension()), corner.getX(), corner.getY(), corner.getZ()));
                CompanionStore.markDirty();
            }
            ly.mind().event(Mind.Event.BUILT, null, "chuồng", corner);
        }
        return false;
    }

    /** Mở cổng, đi ra ngoài chuồng, đóng cổng. @return true khi còn đang đi. */
    public static boolean walkOut(LyMeowzEntity ly, ServerLevel level, BlockPos corner, int age) {
        BlockPos g = gate(corner);
        BlockState st = level.getBlockState(g);
        boolean in = inside(corner).inflate(0.3, 0, 0.3).contains(ly.position());
        if (st.getBlock() instanceof FenceGateBlock) {
            boolean open = st.getValue(FenceGateBlock.OPEN);
            if (in != open) {
                ly.getLookControl().setLookAt(g.getX() + 0.5, g.getY() + 0.5, g.getZ() + 0.5);
                ly.swingArm();
                level.setBlock(g, st.setValue(FenceGateBlock.OPEN, in), 10);
                level.playSound(null, g, in ? net.minecraft.sounds.SoundEvents.FENCE_GATE_OPEN : net.minecraft.sounds.SoundEvents.FENCE_GATE_CLOSE,
                        net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
            }
        }
        if (!in) return false;
        BlockPos out = g.north(2);
        if (ly.getNavigation().isDone() || age % 20 == 0) ly.getNavigation().moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 1.0);
        if (age % 200 == 199) ly.teleportNear(level, out, 0, 1); // kẹt hẳn
        return true;
    }

    /** Chỗ dựng chuồng: nền phẳng gần nhà, không trùng ruộng. */
    public static BlockPos findSite(LyMeowzEntity ly, ServerLevel level, BlockPos home, BlockPos avoid) {
        BlockPos best = null;
        double bestScore = 1e9;
        for (int i = 0; i < 140; i++) {
            int x = home.getX() + ly.getRandom().nextIntBetweenInclusive(-16, 16);
            int z = home.getZ() + ly.getRandom().nextIntBetweenInclusive(-16, 16);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos c = new BlockPos(x, y, z);
            if (c.distSqr(home) < 7 * 7 || (avoid != null && c.distSqr(avoid) < 9 * 9) || ly.isProtected(c)) continue;
            boolean ok = true;
            for (int dx = 0; dx < SIZE && ok; dx++) for (int dz = 0; dz < SIZE && ok; dz++) {
                BlockPos p = c.offset(dx, 0, dz);
                BlockState a = level.getBlockState(p), a2 = level.getBlockState(p.above());
                BlockState g = level.getBlockState(p.below());
                if (!a.canBeReplaced() || !a.getFluidState().isEmpty() || !a2.canBeReplaced()) ok = false;
                else if (!g.isFaceSturdy(level, p.below(), Direction.UP) || g.is(BlockTags.LEAVES)) ok = false;
            }
            if (!ok) continue;
            double s = Math.sqrt(c.distSqr(home));
            if (s < bestScore) { bestScore = s; best = c; }
        }
        return best;
    }
}
