package com.khanhly.lymeowz.survival;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Phá block như người chơi: phải đứng trong tầm với, nhìn vào block, vung tay;
 * tốc độ theo đúng công thức của người chơi (độ cứng, dụng cụ, đúng/sai dụng cụ),
 * hiện vết nứt, đồ rơi vào túi, dụng cụ bị mòn.
 */
public class BlockBreaker {
    public static final double REACH = 4.5;

    public enum Result { WORKING, DONE, OUT_OF_REACH, FAILED }

    private BlockPos pos;
    private float progress;
    private int lastStage = -1;
    private int ticks;

    public BlockPos target() {
        return pos;
    }

    public void start(BlockPos p) {
        pos = p.immutable();
        progress = 0;
        lastStage = -1;
        ticks = 0;
    }

    public static boolean inReach(LyMeowzEntity ly, BlockPos p) {
        return ly.getEyePosition().distanceTo(Vec3.atCenterOf(p)) <= REACH;
    }

    public Result tick(LyMeowzEntity ly, ServerLevel level) {
        if (pos == null) return Result.FAILED;
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) { cancel(level, ly); return Result.DONE; }
        if (!inReach(ly, pos)) return Result.OUT_OF_REACH;

        ly.getNavigation().stop();
        ly.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        ItemStack tool = ly.getMainHandItem();
        float hardness = st.getDestroySpeed(level, pos);
        if (hardness < 0) { cancel(level, ly); return Result.FAILED; }
        boolean correct = !st.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(st);
        float speed = Math.max(1.0f, tool.getDestroySpeed(st));
        progress += hardness == 0 ? 1.0f : speed / hardness / (correct ? 30f : 100f);

        if (ticks++ % 4 == 0) {
            ly.swingArm();
            SoundType sound = st.getSoundType();
            level.playSound(null, pos, sound.getHitSound(), SoundSource.NEUTRAL, sound.getVolume() * 0.25f, sound.getPitch() * 0.5f);
        }
        int stage = Math.min(9, (int) (progress * 10));
        if (stage != lastStage) {
            level.destroyBlockProgress(ly.getId(), pos, stage);
            lastStage = stage;
        }
        if (progress < 1.0f) return Result.WORKING;

        // Vỡ: tính đồ rơi như người chơi đào bằng đúng dụng cụ này.
        List<ItemStack> drops = correct
                ? Block.getDrops(st, level, pos, level.getBlockEntity(pos), ly, tool)
                : List.of();
        level.destroyBlock(pos, false, ly, 512);
        level.destroyBlockProgress(ly.getId(), pos, -1);
        for (ItemStack d : drops) {
            ItemStack left = ly.inv().put(d);
            if (!left.isEmpty()) ly.spawnAtLocation(level, left);
        }
        if (!tool.isEmpty() && tool.isDamageableItem() && hardness > 0) {
            tool.hurtAndBreak(1, ly, EquipmentSlot.MAINHAND);
        }
        ly.onBlockBroken(st);
        pos = null;
        return Result.DONE;
    }

    public void cancel(ServerLevel level, LyMeowzEntity ly) {
        if (pos != null) level.destroyBlockProgress(ly.getId(), pos, -1);
        pos = null;
        progress = 0;
        lastStage = -1;
    }
}
