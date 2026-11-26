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
        // Đếm cả vùng rộng (64 ô) để chừa giống cho cả vùng, không chỉ mấy con đang đứng gần.
        int wide = Math.max(radius, 64);
        List<Animal> all = level.getEntitiesOfClass(Animal.class, ly.getBoundingBox().inflate(wide, 12, wide),
                a -> a.isAlive() && GAME.contains(a.getType()));
        java.util.Map<net.minecraft.world.entity.EntityType<?>, Integer> count = new java.util.HashMap<>();
        for (Animal a : all) if (!a.isBaby()) count.merge(a.getType(), 1, Integer::sum);
        double r2 = (double) radius * radius;
        return all.stream()
                .filter(a -> !a.isBaby() && a.distanceToSqr(ly) <= r2 && Math.abs(a.getY() - ly.getY()) <= 6)
                .filter(a -> count.getOrDefault(a.getType(), 0) >= (desperate ? 2 : 3)) // chừa lại giống
                .filter(a -> desperate || !ly.isProtected(a.blockPosition()))                  // không săn vật nuôi trong nhà
                .min(Comparator.comparingDouble(a -> a.distanceToSqr(ly)))
                .orElse(null);
    }
}
