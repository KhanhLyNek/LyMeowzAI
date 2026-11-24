package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Thư giãn kiểu người chơi: đặt thuyền xuống đất rồi ngồi vào đó ngắm trời một lúc (không có thuyền thì
 * đứng ngắm cảnh), xong thì phá thuyền cất lại vào túi.
 */
public class ChillTask extends Task {
    private Boat boat;
    private final int length;
    private float yaw;

    public ChillTask(LyMeowzEntity ly) {
        super(ly);
        this.length = 20 * (25 + ly.getRandom().nextInt(35));
        this.timeout = length + 20 * 10;
    }

    @Override public String name() { return "thư giãn"; }

    @Override public boolean succeeded() { return age >= length - 5; }

    @Override
    public void start(ServerLevel level) {
        ly.getNavigation().stop();
        yaw = ly.getYRot();
        if (!ly.inv().has(Items.OAK_BOAT)) return;
        BlockPos spot = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = ly.blockPosition().relative(d);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolid()) { spot = p; break; }
        }
        if (spot == null) return;
        Boat b = EntityTypes.OAK_BOAT.create(level, EntitySpawnReason.TRIGGERED);
        if (b == null) return;
        ly.inv().consume(Items.OAK_BOAT, 1);
        b.setInitialPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        b.setYRot(ly.getYRot());
        level.addFreshEntity(b);
        ly.swingArm();
        if (ly.startRiding(b)) boat = b;
        else { b.discard(); ly.inv().put(new ItemStack(Items.OAK_BOAT)); }
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (age >= length) return false;
        if (boat != null && !ly.isPassenger()) return false;
        // Nhìn quanh lơ đãng, thỉnh thoảng ngước lên trời.
        if (age % 60 == 0) yaw += ly.getRandom().nextIntBetweenInclusive(-50, 50);
        Vec3 look = ly.getEyePosition().add(Vec3.directionFromRotation(age % 240 < 80 ? -35 : 0, yaw).scale(5));
        ly.getLookControl().setLookAt(look.x, look.y, look.z, 10f, 10f);
        ly.mind().data().boredom = Math.max(0, ly.mind().data().boredom - 0.05f);
        ly.mind().data().energy = Math.min(100, ly.mind().data().energy + 0.03f);
        ly.mind().data().fear = Math.max(0, ly.mind().data().fear - 0.05f);
        return true;
    }

    @Override
    public void stop(ServerLevel level) {
        if (boat != null) {
            ly.stopRiding();
            if (boat.isAlive()) {
                boat.discard();
                ly.swingArm();
                ly.inv().put(new ItemStack(Items.OAK_BOAT));
            }
            boat = null;
        }
    }
}
