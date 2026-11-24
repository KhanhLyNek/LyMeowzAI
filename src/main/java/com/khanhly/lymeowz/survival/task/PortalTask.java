package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.survival.Builder;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Dựng cổng Nether cạnh nhà: khung obsidian 4×5 (không cần góc), rồi dùng bật lửa thắp lên.
 */
public class PortalTask extends Task {
    private final BlockPos base;   // góc dưới bên trái khung (ngang mặt đất)
    private final Builder builder = new Builder();
    private boolean lit;

    public PortalTask(LyMeowzEntity ly, BlockPos base) {
        super(ly);
        this.base = base.immutable();
        this.timeout = 20 * 60 * 3;
    }

    @Override public String name() { return "dựng cổng Nether"; }

    @Override public boolean succeeded() { return lit; }

    public static boolean canStart(LyMeowzEntity ly) {
        return ly.countAll(s -> s.is(Items.OBSIDIAN)) >= ObsidianTask.NEED && ly.countAll(s -> s.is(Items.FLINT_AND_STEEL)) > 0;
    }

    @Override
    public void start(ServerLevel level) {
        // Đáy, hai bên, nóc (bỏ 4 góc).
        builder.add(base.offset(1, 0, 0), s -> s.is(Items.OBSIDIAN));
        builder.add(base.offset(2, 0, 0), s -> s.is(Items.OBSIDIAN));
        for (int y = 1; y <= 3; y++) {
            builder.add(base.offset(0, y, 0), s -> s.is(Items.OBSIDIAN));
            builder.add(base.offset(3, y, 0), s -> s.is(Items.OBSIDIAN));
        }
        builder.add(base.offset(1, 4, 0), s -> s.is(Items.OBSIDIAN));
        builder.add(base.offset(2, 4, 0), s -> s.is(Items.OBSIDIAN));
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (builder.tick(ly, level)) return true;
        if (!builder.done()) return false;
        BlockPos inside = base.offset(1, 1, 0);
        if (ly.getEyePosition().distanceTo(Vec3.atCenterOf(inside)) > 4) {
            if (ly.getNavigation().isDone()) ly.walkTo(inside.relative(Direction.SOUTH, 2).below(), 1.0);
            return age < timeout;
        }
        int slot = ly.inv().bestSlot(s -> s.is(Items.FLINT_AND_STEEL) ? 1 : 0);
        if (slot < 0 || !ly.inv().getItem(slot).is(Items.FLINT_AND_STEEL)) {
            if (!ly.getMainHandItem().is(Items.FLINT_AND_STEEL)) return false;
        } else {
            ly.stowHand();
            ly.setItemSlot(EquipmentSlot.MAINHAND, ly.inv().takeSlot(slot));
        }
        ly.getLookControl().setLookAt(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
        ly.swingArm();
        level.playSound(null, inside, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1f, 1f);
        level.setBlock(inside, BaseFireBlock.getState(level, inside), 11); // lửa trong khung tự mở cổng
        ItemStack tool = ly.getMainHandItem();
        if (tool.isDamageableItem()) tool.hurtAndBreak(1, ly, EquipmentSlot.MAINHAND);
        lit = level.getBlockState(inside).is(Blocks.NETHER_PORTAL);
        if (lit) {
            CompanionStore.Data d = ly.data();
            if (d != null) {
                d.structures.put("portal", new CompanionStore.Spot(Compat.dimId(level.dimension()), base.getX(), base.getY(), base.getZ()));
                CompanionStore.markDirty();
            }
            ly.mind().event(Mind.Event.BUILT, null, "cổng Nether", base);
        }
        return false;
    }

    /** Chỗ dựng cổng: nền phẳng 4 ô cạnh nhà, trống cao 5 ô. */
    public static BlockPos findSite(LyMeowzEntity ly, ServerLevel level, BlockPos home) {
        for (int i = 0; i < 120; i++) {
            int x = home.getX() + ly.getRandom().nextIntBetweenInclusive(-14, 14);
            int z = home.getZ() + ly.getRandom().nextIntBetweenInclusive(-14, 14);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos b = new BlockPos(x, y, z);
            if (b.distSqr(home) < 7 * 7 || ly.isProtected(b)) continue;
            boolean ok = true;
            for (int dx = -1; dx <= 4 && ok; dx++) for (int dz = -1; dz <= 2 && ok; dz++) {
                BlockPos g = b.offset(dx, -1, dz);
                if (!level.getBlockState(g).isSolid() || level.getBlockState(g).is(BlockTags.LEAVES)) ok = false;
                for (int dy = 0; dy <= 5 && ok; dy++)
                    if (!level.getBlockState(b.offset(dx, dy, dz)).canBeReplaced() || !level.getFluidState(b.offset(dx, dy, dz)).isEmpty()) ok = false;
            }
            if (ok) return b;
        }
        return null;
    }
}
