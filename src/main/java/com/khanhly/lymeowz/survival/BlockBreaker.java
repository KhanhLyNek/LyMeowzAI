package com.khanhly.lymeowz.survival;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Phá block như người chơi: đứng trong tầm với, <b>nhìn thấy</b> block (tia từ mắt tới block không bị chắn),
 * vung tay, block nứt dần theo đúng công thức tốc độ của người chơi, đồ rơi vào túi, dụng cụ mòn.
 * Nếu bị lá / cỏ / hoa che thì phá chúng trước (như người chơi vẫn làm); bị đá che thì báo không thấy.
 */
public class BlockBreaker {
    public static final double REACH = 4.5;

    public enum Result { WORKING, DONE, OUT_OF_REACH, NOT_VISIBLE, FAILED }

    private BlockPos goal;      // block cần phá
    private BlockPos current;   // block đang phá (có thể là lá chắn trước goal)
    private float progress;
    private int lastStage = -1;
    private int ticks;

    public BlockPos target() {
        return goal;
    }

    public void start(BlockPos p) {
        goal = p.immutable();
        current = null;
        progress = 0;
        lastStage = -1;
        ticks = 0;
    }

    public static boolean inReach(LyMeowzEntity ly, BlockPos p) {
        return ly.getEyePosition().distanceTo(Vec3.atCenterOf(p)) <= REACH;
    }

    /** Block đầu tiên chắn tầm nhìn từ mắt tới {@code p} (null nếu nhìn thấy rõ). */
    public static BlockPos obstacle(LyMeowzEntity ly, BlockPos p) {
        Vec3 eye = ly.getEyePosition();
        Vec3 to = Vec3.atCenterOf(p);
        BlockHitResult hit = ly.level().clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ly));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(p)) return null;
        return hit.getBlockPos();
    }

    /** Thứ chắn mà người chơi sẽ tiện tay phá luôn (lá, cỏ, hoa, dây leo...). */
    public static boolean isSoftObstacle(BlockState st) {
        return st.is(BlockTags.LEAVES) || st.canBeReplaced() || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS);
    }

    public static boolean canSee(LyMeowzEntity ly, BlockPos p) {
        return obstacle(ly, p) == null;
    }

    public Result tick(LyMeowzEntity ly, ServerLevel level) {
        if (goal == null) return Result.FAILED;
        if (level.getBlockState(goal).isAir()) { cancel(level, ly); return Result.DONE; }
        if (!inReach(ly, goal)) return Result.OUT_OF_REACH;

        if (current == null || level.getBlockState(current).isAir()) {
            BlockPos ob = obstacle(ly, goal);
            if (ob == null) current = goal;
            else {
                BlockState obs = level.getBlockState(ob);
                if (isSoftObstacle(obs) && inReach(ly, ob)) current = ob;
                else { cancel(level, ly); return Result.NOT_VISIBLE; }
            }
            progress = 0;
            lastStage = -1;
            ly.holdBestFor(level.getBlockState(current));
        }

        BlockPos pos = current;
        BlockState st = level.getBlockState(pos);
        ly.getNavigation().stop();
        ly.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30f, 30f);
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

        List<ItemStack> drops = correct ? Block.getDrops(st, level, pos, level.getBlockEntity(pos), ly, tool) : List.of();
        level.destroyBlock(pos, false, ly, 512);
        level.destroyBlockProgress(ly.getId(), pos, -1);
        for (ItemStack d : drops) {
            ItemStack left = ly.inv().put(d);
            if (!left.isEmpty()) ly.spawnAtLocation(level, left);
        }
        if (!tool.isEmpty() && tool.isDamageableItem() && hardness > 0) tool.hurtAndBreak(1, ly, EquipmentSlot.MAINHAND);
        ly.onBlockBroken(st);
        boolean wasGoal = pos.equals(goal);
        current = null;
        progress = 0;
        if (wasGoal) { goal = null; return Result.DONE; }
        return Result.WORKING; // vừa dọn chỗ chắn, giờ tới block chính
    }

    public void cancel(ServerLevel level, LyMeowzEntity ly) {
        if (current != null) level.destroyBlockProgress(ly.getId(), current, -1);
        goal = null;
        current = null;
        progress = 0;
        lastStage = -1;
    }
}
