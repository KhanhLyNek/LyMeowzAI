package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.UUID;

/** Nhặt đồ rơi gần đó (đồ của con vật vừa săn, cây con rụng, đồ chủ ném cho). */
public class CollectTask extends Task {
    private final ItemEntity item;
    /** Món nhặt không được (kẹt trên lá, trong hố...) → tạm bỏ qua 1 phút. */
    private static final java.util.Map<UUID, Long> SKIP = new java.util.HashMap<>();

    public CollectTask(LyMeowzEntity ly, ItemEntity item) {
        super(ly);
        this.item = item;
        this.timeout = 20 * 15;
    }

    @Override
    public boolean succeeded() {
        return !item.isAlive();
    }

    @Override
    public String name() {
        return "nhặt đồ";
    }

    @Override
    public void start(ServerLevel level) {
        ly.getNavigation().moveTo(item, 1.0);
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (!item.isAlive()) return false;
        if (item.distanceToSqr(ly) < 2.0 * 2.0) {
            ItemStack stack = item.getItem();
            int before = stack.getCount();
            ItemStack left = ly.inv().put(stack.copy());
            int taken = before - left.getCount();
            if (taken > 0) {
                ly.take(item, taken);
                // Đồ người chơi ném cho cô ấy là quà.
                if (item.getOwner() instanceof net.minecraft.world.entity.player.Player p) {
                    ly.mind().event(com.khanhly.lymeowz.mind.Mind.Event.GIFT, p, com.khanhly.lymeowz.survival.ItemNames.of(stack.getItem()), null);
                    ly.mind().gift(p, com.khanhly.lymeowz.survival.Tools.value(stack) * Math.max(1, taken / 8));
                }
            }
            if (left.isEmpty()) item.discard();
            else item.setItem(left);
            return false;
        }
        if (ly.getNavigation().isDone() && !ly.getNavigation().moveTo(item, 1.0)) return false;
        return true;
    }

    @Override
    public void stop(ServerLevel level) {
        if (item.isAlive()) SKIP.put(item.getUUID(), level.getGameTime() + 1200);
    }

    public static ItemEntity find(LyMeowzEntity ly, ServerLevel level, int radius) {
        if (ly.inv().isFull()) return null;
        return level.getEntitiesOfClass(ItemEntity.class, ly.getBoundingBox().inflate(radius, 3, radius), e -> {
                    if (!e.isAlive() || e.hasPickUpDelay()) return false;
                    Long until = SKIP.get(e.getUUID());
                    if (until != null && until > level.getGameTime()) return false;
                    Entity thrower = e.getOwner();
                    if (thrower == null || thrower == ly) {
                        // Đồ rơi ngay cạnh người chơi là của người đó (họ tự nhặt).
                        var p = level.getNearestPlayer(e, 4);
                        return p == null || p.isSpectator();
                    }
                    // Người chơi ném đồ về phía cô ấy: quà.
                    return thrower instanceof net.minecraft.world.entity.player.Player && e.distanceToSqr(ly) < 5 * 5
                            && e.distanceToSqr(thrower) > 2 * 2;
                }).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(ly)))
                .orElse(null);
    }
}
