package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Đi săn lấy thịt: chỉ săn con trưởng thành, và luôn chừa lại ít nhất 2 con mỗi loài quanh đó. */
public class HuntTask extends Task {
    public static final Set<EntityType<?>> GAME = Set.of(
            EntityTypes.COW, EntityTypes.PIG, EntityTypes.CHICKEN, EntityTypes.SHEEP, EntityTypes.RABBIT);

    private final Animal prey;

    public HuntTask(LyMeowzEntity ly, Animal prey) {
        super(ly);
        this.prey = prey;
        this.timeout = 20 * 40;
    }

    @Override
    public boolean succeeded() {
        return !prey.isAlive();
    }

    @Override
    public String name() {
        return "đi săn";
    }

    @Override
    public void start(ServerLevel level) {
        ly.holdBestWeapon();
        ly.setTarget(prey);
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (!prey.isAlive()) return false;
        if (ly.getTarget() != prey) ly.setTarget(prey);
        return true;
    }

    @Override
    public void stop(ServerLevel level) {
        if (ly.getTarget() == prey) ly.setTarget(null);
    }

    public static Animal find(LyMeowzEntity ly, ServerLevel level, int radius) {
        return find(ly, level, radius, false);
    }

    /** {@code desperate}: sắp chết đói – săn cả thú trong chuồng, chỉ chừa lại 1 con mỗi loài. */
    public static Animal find(LyMeowzEntity ly, ServerLevel level, int radius, boolean desperate) {
        AABB box = ly.getBoundingBox().inflate(radius, 6, radius);
        List<Animal> all = level.getEntitiesOfClass(Animal.class, box, a -> a.isAlive() && GAME.contains(a.getType()));
        return all.stream()
                .filter(a -> !a.isBaby())
                .filter(a -> all.stream().filter(o -> o.getType() == a.getType()).count() >= (desperate ? 2 : 3)) // chừa lại giống
                .filter(a -> desperate || !ly.isProtected(a.blockPosition()))                  // không săn vật nuôi trong nhà
                .min(Comparator.comparingDouble(a -> a.distanceToSqr(ly)))
                .orElse(null);
    }
}
