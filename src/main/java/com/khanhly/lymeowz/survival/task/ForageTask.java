package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/** Hái quả mọng như người chơi: tới bụi mọng chín, chuột phải hái (bụi vẫn còn để mọc lại). */
public class ForageTask extends Task {
    private final Set<BlockPos> done = new HashSet<>();
    private BlockPos bush;
    private int picked, walk, cooldown;

    public ForageTask(LyMeowzEntity ly) {
        super(ly);
        this.timeout = 20 * 60;
    }

    @Override public String name() { return "hái quả"; }

    @Override public boolean succeeded() { return picked > 0; }

    public static boolean ripe(BlockState st) {
        return st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) >= 2;
    }

    public static BlockPos find(LyMeowzEntity ly, ServerLevel level, int r, Set<BlockPos> skip) {
        BlockPos c = ly.blockPosition();
        BlockPos best = null;
        double bd = 1e9;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 3, r))) {
            if (!ripe(level.getBlockState(p)) || skip.contains(p)) continue;
            double d = p.distSqr(c);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (cooldown-- > 0) return true;
        if (picked >= 12) return false;
        if (bush == null || !ripe(level.getBlockState(bush))) {
            bush = find(ly, level, 20, done);
            walk = 0;
            if (bush == null) return false;
        }
        if (ly.getEyePosition().distanceTo(Vec3.atCenterOf(bush)) > 3.5) {
            if (ly.getNavigation().isDone()) { if (++walk > 5) { done.add(bush); bush = null; } else ly.walkTo(bush, 1.0); }
            return true;
        }
        ly.getNavigation().stop();
        ly.getLookControl().setLookAt(bush.getX() + 0.5, bush.getY() + 0.5, bush.getZ() + 0.5, 20, 20);
        BlockState st = level.getBlockState(bush);
        int age = st.getValue(SweetBerryBushBlock.AGE);
        int n = 1 + ly.getRandom().nextInt(2) + (age == 3 ? 1 : 0);
        ly.swingArm();
        level.playSound(null, bush, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1f, 0.8f + ly.getRandom().nextFloat() * 0.4f);
        level.setBlock(bush, st.setValue(SweetBerryBushBlock.AGE, 1), 2);
        ly.inv().put(new ItemStack(Items.SWEET_BERRIES, n));
        picked += n;
        done.add(bush);
        bush = null;
        cooldown = 6 + ly.getRandom().nextInt(10);
        return true;
    }
}
