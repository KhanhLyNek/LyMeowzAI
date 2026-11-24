package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.mind.MindData;
import com.khanhly.lymeowz.survival.BlockBreaker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Làm obsidian như người chơi: múc một xô nước, đi tới vũng nham thạch, đổ nước lên cho nham thạch nguội
 * thành obsidian (rồi múc nước lại), sau đó dùng cuốc kim cương đào obsidian ra.
 */
public class ObsidianTask extends Task {
    public static final int NEED = 10;

    private enum Phase { WATER, LAVA, MINE }

    private Phase phase;
    private BlockPos target;
    private final BlockBreaker breaker = new BlockBreaker();
    private final Set<BlockPos> made = new HashSet<>();
    private final Set<BlockPos> skip = new HashSet<>();
    private int walk, got;

    public ObsidianTask(LyMeowzEntity ly) {
        super(ly);
        this.timeout = 20 * 60 * 5;
    }

    @Override public String name() { return "làm obsidian"; }

    @Override public boolean succeeded() { return got > 0; }

    @Override
    public void start(ServerLevel level) {
        phase = ly.inv().has(Items.WATER_BUCKET) ? Phase.LAVA : Phase.WATER;
    }

    public static boolean canStart(LyMeowzEntity ly) {
        return (ly.inv().has(Items.WATER_BUCKET) || ly.inv().has(Items.BUCKET))
                && ly.countAll(s -> s.is(Items.DIAMOND_PICKAXE) || s.is(Items.NETHERITE_PICKAXE)) > 0;
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (ly.countAll(s -> s.is(Items.OBSIDIAN)) >= NEED) return false;
        return switch (phase) {
            case WATER -> water(level);
            case LAVA -> lava(level);
            case MINE -> mine(level);
        };
    }

    // ------------------------------------------------------------------ múc nước

    private boolean water(ServerLevel level) {
        if (target == null || !level.getFluidState(target).isSource() || !level.getFluidState(target).is(FluidTags.WATER)) {
            target = findFluid(level, FluidTags.WATER, 32);
            walk = 0;
            if (target == null) return false;
        }
        if (!approach(target)) return true;
        if (!ly.inv().consume(Items.BUCKET, 1)) return false;
        ly.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        ly.swingArm();
        level.playSound(null, target, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 1f, 1f);
        ly.inv().put(new ItemStack(Items.WATER_BUCKET));
        target = null;
        phase = Phase.LAVA;
        return true;
    }

    // ------------------------------------------------------------------ đổ nước lên nham thạch

    private boolean lava(ServerLevel level) {
        if (target == null || !level.getFluidState(target).is(FluidTags.LAVA) || !level.getFluidState(target).isSource()) {
            target = findFluid(level, FluidTags.LAVA, 20);
            walk = 0;
            if (target == null) {
                // Đi tới chỗ nham thạch đã nhớ.
                MindData.Place pl = ly.mind().nearest(Mind.PlaceKind.LAVA, ly.blockPosition());
                if (pl == null) return toMine();
                BlockPos at = new BlockPos(pl.x, pl.y, pl.z);
                if (at.distSqr(ly.blockPosition()) < 6 * 6) {
                    ly.mind().data().places.remove(pl); // nham thạch đã hết
                    return toMine();
                }
                if (ly.getNavigation().isDone()) ly.walkTo(at, 1.0);
                return age < timeout;
            }
        }
        if (!approach(target)) return true;
        if (!ly.inv().has(Items.WATER_BUCKET)) { phase = Phase.WATER; return true; }
        ly.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        ly.swingArm();
        // Nước chảy lan: nham thạch quanh đó cũng nguội thành obsidian. Rồi múc nước lại.
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(target.offset(-1, 0, -1), target.offset(1, 0, 1))) {
            if (n >= 4) break;
            if (level.getFluidState(p).is(FluidTags.LAVA) && level.getFluidState(p).isSource()) {
                level.setBlock(p, Blocks.OBSIDIAN.defaultBlockState(), 3);
                made.add(p.immutable());
                n++;
            }
        }
        level.playSound(null, target, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f);
        level.levelEvent(1501, target, 0); // khói xèo xèo
        target = null;
        if (made.size() + ly.countAll(s -> s.is(Items.OBSIDIAN)) >= NEED || findFluid(level, FluidTags.LAVA, 6) == null) phase = Phase.MINE;
        return true;
    }

    /** Hết nham thạch: chuyển sang đào obsidian đã làm (nếu có). */
    private boolean toMine() {
        if (made.isEmpty()) return false;
        phase = Phase.MINE;
        return true;
    }

    // ------------------------------------------------------------------ đào obsidian

    private boolean mine(ServerLevel level) {
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            if (r == BlockBreaker.Result.DONE) got++;
            else if (breaker.target() != null) skip.add(breaker.target());
            breaker.cancel(level, ly);
        }
        made.removeIf(p -> !level.getBlockState(p).is(Blocks.OBSIDIAN) || skip.contains(p));
        if (made.isEmpty()) {
            phase = Phase.LAVA;
            return ly.countAll(s -> s.is(Items.OBSIDIAN)) < NEED && findFluid(level, FluidTags.LAVA, 20) != null;
        }
        BlockPos p = made.stream().min(java.util.Comparator.comparingDouble(q -> q.distSqr(ly.blockPosition()))).get();
        if (!BlockBreaker.inReach(ly, p)) {
            if (ly.getNavigation().isDone()) { if (++walk > 6) { skip.add(p); walk = 0; } else ly.walkTo(p, 1.0); }
            return true;
        }
        // Đứng ngay trên obsidian sắp đào thì có thể rơi vào nham thạch bên dưới: bỏ qua ô đó.
        if (ly.blockPosition().below().equals(p)) { skip.add(p); return true; }
        breaker.start(p);
        return true;
    }

    // ------------------------------------------------------------------

    private boolean approach(BlockPos p) {
        if (ly.getEyePosition().distanceTo(Vec3.atCenterOf(p)) <= BlockBreaker.REACH - 0.5) {
            ly.getNavigation().stop();
            return true;
        }
        if (ly.getNavigation().isDone()) {
            if (++walk > 6) { skip.add(p); target = null; return false; }
            BlockPos stand = standBeside(p);
            ly.walkTo(stand != null ? stand : p, 1.0);
        }
        return false;
    }

    private BlockPos standBeside(BlockPos p) {
        ServerLevel level = (ServerLevel) ly.level();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = p.relative(d);
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos q = s.above(dy);
                if (level.getBlockState(q).isAir() && level.getBlockState(q.above()).isAir() && level.getBlockState(q.below()).isSolid()) return q;
            }
        }
        return null;
    }

    private BlockPos findFluid(ServerLevel level, net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag, int r) {
        BlockPos c = ly.blockPosition();
        BlockPos best = null;
        double bd = 1e9;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -10, -r), c.offset(r, 4, r))) {
            var f = level.getFluidState(p);
            if (!f.is(tag) || !f.isSource() || skip.contains(p)) continue;
            if (standBeside(p) == null && !level.getBlockState(p.above()).isAir()) continue;
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
