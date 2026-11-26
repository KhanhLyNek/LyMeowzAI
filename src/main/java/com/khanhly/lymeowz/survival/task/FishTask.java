package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Câu cá như người chơi: ra bờ nước, quăng cần, ngồi chờ phao (thỉnh thoảng ngó quanh), cá cắn câu thì giật lên.
 * Vừa có đồ ăn dự phòng, vừa là trò thư giãn.
 */
public class FishTask extends Task {
    private BlockPos water, stand;
    private int phase;          // 0 đi tới bờ, 1 quăng, 2 chờ, 3 giật
    private int wait, walk;
    private int caught;
    private final int want;

    public FishTask(LyMeowzEntity ly, int want) {
        super(ly);
        this.want = want;
        this.timeout = 20 * 60 * 3;
    }

    @Override public String name() { return "câu cá"; }

    @Override public boolean succeeded() { return caught > 0; }

    public static boolean hasRod(LyMeowzEntity ly) {
        return ly.countAll(s -> s.is(Items.FISHING_ROD)) > 0;
    }

    /** Mặt nước câu được gần đây (nước nguồn, phía trên thoáng, có chỗ đứng trên bờ). */
    public static BlockPos findWater(LyMeowzEntity ly, ServerLevel level, int r) {
        BlockPos c = ly.blockPosition();
        return com.khanhly.lymeowz.util.BlockScan.nearest(level, c, c.offset(-r, -4, -r), c.offset(r, 3, r),
                st -> st.getFluidState().is(FluidTags.WATER) && st.getFluidState().isSource(),
                p -> level.getBlockState(p.above()).isAir() && shore(level, p) != null);
    }

    private static BlockPos shore(ServerLevel level, BlockPos water) {
        for (int dist = 2; dist <= 3; dist++) for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos s = water.relative(d, dist).above(dy);
                if (level.getBlockState(s).isAir() && level.getBlockState(s.above()).isAir()
                        && level.getBlockState(s.below()).isSolid() && level.getFluidState(s.below()).isEmpty()) return s;
            }
        }
        return null;
    }

    @Override
    public void start(ServerLevel level) {
        water = findWater(ly, level, 24);
        if (water != null) stand = shore(level, water);
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (water == null || stand == null) return false;
        if (caught >= want) return false;
        Vec3 bobber = Vec3.atCenterOf(water).add(0, 0.4, 0);
        switch (phase) {
            case 0 -> {
                if (ly.position().distanceTo(Vec3.atBottomCenterOf(stand)) < 1.2) { ly.getNavigation().stop(); phase = 1; wait = 10; return true; }
                if (ly.getNavigation().isDone()) { if (++walk > 5) return false; ly.walkTo(stand, 1.0); }
                return true;
            }
            case 1 -> {
                ly.getLookControl().setLookAt(bobber.x, bobber.y, bobber.z, 20, 20);
                if (--wait > 0) return true;
                if (!holdRod()) return false;
                ly.swingArm();
                level.playSound(null, ly.blockPosition(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5f, 0.4f);
                level.sendParticles(ParticleTypes.SPLASH, bobber.x, bobber.y, bobber.z, 6, 0.1, 0.05, 0.1, 0.0);
                wait = 100 + ly.getRandom().nextInt(400) - (level.isRaining() ? 60 : 0);
                phase = 2;
            }
            case 2 -> {
                // Chờ phao: phần lớn nhìn phao, thỉnh thoảng ngó quanh cho đỡ chán.
                if (age % 80 < 60) ly.getLookControl().setLookAt(bobber.x, bobber.y - 0.2, bobber.z, 8, 8);
                else if (age % 80 == 60) {
                    Vec3 look = ly.getEyePosition().add(Vec3.directionFromRotation(ly.getRandom().nextIntBetweenInclusive(-20, 10),
                            ly.getYHeadRot() + ly.getRandom().nextIntBetweenInclusive(-90, 90)).scale(5));
                    ly.getLookControl().setLookAt(look.x, look.y, look.z, 15, 10);
                }
                if (age % 20 == 0) level.sendParticles(ParticleTypes.FISHING, bobber.x, bobber.y - 0.3, bobber.z, 1, 0.05, 0, 0.05, 0.0);
                if (--wait <= 20 && wait > 0 && wait % 4 == 0)   // cá đang tới: vệt bọt
                    level.sendParticles(ParticleTypes.BUBBLE, bobber.x, bobber.y - 0.5, bobber.z, 2, 0.2, 0, 0.2, 0.0);
                if (wait <= 0) {
                    level.playSound(null, water, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.25f, 1f);
                    level.sendParticles(ParticleTypes.SPLASH, bobber.x, bobber.y, bobber.z, 12, 0.2, 0.05, 0.2, 0.0);
                    phase = 3;
                    wait = 4 + ly.getRandom().nextInt(10); // phản xạ: lúc nhanh lúc chậm
                }
            }
            case 3 -> {
                ly.getLookControl().setLookAt(bobber.x, bobber.y, bobber.z, 40, 40);
                if (--wait > 0) return true;
                ly.swingArm();
                level.playSound(null, ly.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1f, 1f);
                ItemStack rod = ly.getMainHandItem();
                if (rod.is(Items.FISHING_ROD)) rod.hurtAndBreak(1, ly, EquipmentSlot.MAINHAND);
                // Đôi khi giật hụt.
                if (ly.getRandom().nextFloat() < 0.2f) {
                    if (ly.getRandom().nextBoolean()) ly.express(LyMeowzEntity.Expr.CONFUSED);
                } else {
                    ItemStack loot = loot();
                    ly.inv().put(loot);
                    caught++;
                    level.sendParticles(ParticleTypes.SPLASH, bobber.x, bobber.y, bobber.z, 8, 0.2, 0.1, 0.2, 0.0);
                    if (loot.is(Items.COD) || loot.is(Items.SALMON)) {
                        if (ly.getRandom().nextFloat() < 0.3f) ly.express(LyMeowzEntity.Expr.HAPPY);
                    } else if (ly.getRandom().nextFloat() < 0.4f) ly.express(LyMeowzEntity.Expr.CONFUSED); // câu được cái ủng?!
                }
                phase = 1;
                wait = 15 + ly.getRandom().nextInt(25);
            }
            default -> {}
        }
        return true;
    }

    private ItemStack loot() {
        float r = ly.getRandom().nextFloat();
        Item it;
        if (r < 0.55f) it = Items.COD;
        else if (r < 0.80f) it = Items.SALMON;
        else if (r < 0.85f) it = Items.TROPICAL_FISH;
        else if (r < 0.95f) it = switch (ly.getRandom().nextInt(5)) {   // rác
            case 0 -> Items.LEATHER_BOOTS;
            case 1 -> Items.STICK;
            case 2 -> Items.BOWL;
            case 3 -> Items.STRING;
            default -> Items.LILY_PAD;
        };
        else it = switch (ly.getRandom().nextInt(4)) {                 // kho báu
            case 0 -> Items.NAME_TAG;
            case 1 -> Items.SADDLE;
            case 2 -> Items.NAUTILUS_SHELL;
            default -> Items.BOW;
        };
        return new ItemStack(it);
    }

    private boolean holdRod() {
        if (ly.getMainHandItem().is(Items.FISHING_ROD)) return true;
        int slot = ly.inv().bestSlot(s -> s.is(Items.FISHING_ROD) ? 1 : 0);
        if (slot < 0 || !ly.inv().getItem(slot).is(Items.FISHING_ROD)) return false;
        ly.stowHand();
        ly.setItemSlot(EquipmentSlot.MAINHAND, ly.inv().takeSlot(slot));
        return true;
    }

    @Override
    public void stop(ServerLevel level) {
        if (caught > 0) ly.mind().remember(Mind.PlaceKind.WATER, water, caught);
    }
}
