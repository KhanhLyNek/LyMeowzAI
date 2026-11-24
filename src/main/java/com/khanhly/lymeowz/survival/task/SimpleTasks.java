package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.speech.Speech;
import com.khanhly.lymeowz.survival.Crafting;
import com.khanhly.lymeowz.survival.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/** Các việc nhỏ do "tâm trí" chọn: nghỉ, trò chuyện, chạy trốn, trồng cây, thắp đuốc. */
public final class SimpleTasks {
    private SimpleTasks() {}

    /** Nghỉ ngơi cạnh chủ: hồi năng lượng, bớt sợ. */
    public static class Rest extends Task {
        public Rest(LyMeowzEntity ly) { super(ly); timeout = 20 * 20; }
        @Override public String name() { return "nghỉ"; }
        @Override protected boolean run(ServerLevel level) {
            ServerPlayer o = ly.getOwner();
            if (o != null && o.level() == level && o.distanceTo(ly) > 4 && ly.getNavigation().isDone()) ly.getNavigation().moveTo(o, 0.9);
            else if (o != null && o.distanceTo(ly) <= 3) ly.getNavigation().stop();
            if (o != null) ly.getLookControl().setLookAt(o, 20f, 20f);
            ly.mind().data().fear = Math.max(0, ly.mind().data().fear - 0.3f);
            return true;
        }
        @Override public boolean succeeded() { return true; }
    }

    /** Lại gần chủ nói chuyện; tốt bụng thì có khi tặng quà. */
    public static class Socialize extends Task {
        private boolean spoke;
        public Socialize(LyMeowzEntity ly) { super(ly); timeout = 20 * 12; }
        @Override public String name() { return "trò chuyện"; }
        @Override protected boolean run(ServerLevel level) {
            ServerPlayer o = ly.getOwner();
            if (o == null || o.level() != level) return false;
            if (o.distanceTo(ly) > 3) {
                if (ly.getNavigation().isDone()) ly.getNavigation().moveTo(o, 1.0);
                return true;
            }
            ly.getNavigation().stop();
            ly.getLookControl().setLookAt(o, 30f, 30f);
            if (!spoke) {
                spoke = true;
                ly.speak(Speech.Act.SMALLTALK, Speech.Ctx.of().to(o));
                ly.mind().data().social = Math.min(100, ly.mind().data().social + 25);
                ly.mind().data().boredom = Math.max(0, ly.mind().data().boredom - 10);
                var p = ly.mind().p();
                if (ly.getRandom().nextFloat() < 0.25f * p.kind && ly.mind().affinity(o.getUUID()) > 40) gift(level, o);
                return true;
            }
            return age < 60;
        }
        private void gift(ServerLevel level, ServerPlayer o) {
            ItemStack g = ly.inv().takeOne(s -> s.is(Items.APPLE) || com.khanhly.lymeowz.compat.Compat.isFlower(s) || s.is(Items.COOKED_BEEF));
            if (g.isEmpty()) return;
            ItemEntity e = new ItemEntity(level, ly.getX(), ly.getEyeY() - 0.3, ly.getZ(), g);
            Vec3 dir = o.position().subtract(ly.position()).normalize().scale(0.25);
            e.setDeltaMovement(dir.x, 0.2, dir.z);
            e.setThrower(ly);
            level.addFreshEntity(e);
            ly.swingArm();
            ly.sayRaw("Cái này cho " + (ly.mind().affinity(o.getUUID()) > 70 ? "cậu nè~" : "cậu nha."));
        }
        @Override public boolean succeeded() { return spoke; }
    }

    /** Nhút nhát gặp quái: chạy về phía chủ, tránh xa quái. */
    public static class Flee extends Task {
        private final Mob from;
        public Flee(LyMeowzEntity ly, Mob from) { super(ly); this.from = from; timeout = 20 * 6; }
        @Override public String name() { return "chạy trốn"; }
        @Override public void start(ServerLevel level) {
            ly.setTarget(null);
            ly.speak(Speech.Act.SCARED, Speech.Ctx.of().mob(Speech.mobName(from.getType())));
        }
        @Override protected boolean run(ServerLevel level) {
            if (!from.isAlive() || from.distanceTo(ly) > 14) return false;
            ServerPlayer o = ly.getOwner();
            Vec3 away = ly.position().subtract(from.position()).normalize().scale(8);
            Vec3 goal = o != null && o.level() == level ? o.position().add(away.scale(0.3)) : ly.position().add(away);
            if (ly.getNavigation().isDone() || age % 10 == 0) ly.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.4);
            return true;
        }
        @Override public boolean succeeded() { return ly.getHealth() > 4; }

        public static Mob nearestThreat(LyMeowzEntity ly, ServerLevel level) {
            List<Mob> l = level.getEntitiesOfClass(Mob.class, ly.getBoundingBox().inflate(10), m -> m instanceof Enemy && m.isAlive());
            return l.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(ly))).orElse(null);
        }
    }

    /** Trồng cây con trên đất trống (cách các cây khác ≥ 3 block). */
    public static class Plant extends Task {
        private int planted;
        public Plant(LyMeowzEntity ly) { super(ly); timeout = 20 * 25; }
        @Override public String name() { return "trồng cây"; }
        @Override protected boolean run(ServerLevel level) {
            if (planted >= 4 || ly.inv().count(ItemTags.SAPLINGS) == 0) return false;
            if (age % 25 != 0) return true;
            BlockPos spot = findSpot(level);
            if (spot == null) return false;
            if (!spot.closerToCenterThan(ly.position(), 3.5)) { ly.walkTo(spot, 1.0); return true; }
            ItemStack s = ly.inv().takeOne(x -> x.is(ItemTags.SAPLINGS));
            if (s.getItem() instanceof BlockItem bi) {
                BlockState st = bi.getBlock().defaultBlockState();
                if (st.canSurvive(level, spot)) { ly.placeBlock(level, spot, st); planted++; return true; }
            }
            ly.inv().put(s);
            return false;
        }
        private BlockPos findSpot(ServerLevel level) {
            BlockPos c = ly.blockPosition();
            for (int i = 0; i < 30; i++) {
                BlockPos p = c.offset(ly.getRandom().nextIntBetweenInclusive(-6, 6), 0, ly.getRandom().nextIntBetweenInclusive(-6, 6));
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos q = p.above(dy);
                    if (!level.getBlockState(q).isAir() || !level.getBlockState(q.below()).is(BlockTags.DIRT)) continue;
                    if (!level.canSeeSky(q)) continue;
                    boolean crowded = false;
                    for (BlockPos n : BlockPos.betweenClosed(q.offset(-2, -1, -2), q.offset(2, 3, 2))) {
                        BlockState st = level.getBlockState(n);
                        if (st.is(BlockTags.LOGS) || st.is(BlockTags.SAPLINGS) || st.is(Blocks.CRAFTING_TABLE)) { crowded = true; break; }
                    }
                    ServerPlayer o = ly.getOwner();
                    if (!crowded && (o == null || !o.blockPosition().closerThan(q, 2))) return q.immutable();
                }
            }
            return null;
        }
        @Override public boolean succeeded() { return planted > 0; }
    }

    /** Cắm đuốc ở chỗ tối quanh chủ (tự làm đuốc từ than + que nếu cần). */
    public static class Light extends Task {
        private int placed;
        public Light(LyMeowzEntity ly) { super(ly); timeout = 20 * 20; }
        @Override public String name() { return "thắp đuốc"; }
        @Override public void start(ServerLevel level) {
            if (!ly.inv().has(Items.TORCH)) {
                var steps = Crafting.plan(Crafting.copy(ly.inv()), Items.TORCH, false);
                if (steps != null) for (var st : steps) Crafting.execute(ly.inv(), st);
            }
        }
        private BlockPos spot;
        private int walk;
        @Override protected boolean run(ServerLevel level) {
            if (placed >= 4 || !ly.inv().has(Items.TORCH)) return false;
            if (spot == null || !level.getBlockState(spot).isAir()) {
                spot = findDarkSpot(level);
                walk = 0;
                if (spot == null) return false;
            }
            if (!spot.closerToCenterThan(ly.position(), 3.5)) {
                if (ly.getNavigation().isDone()) {
                    if (++walk > 4) { spot = null; return placed < 4 && age < timeout; }
                    ly.walkTo(spot, 1.0);
                }
                return true;
            }
            ly.inv().consume(Items.TORCH, 1);
            ly.placeBlock(level, spot, Blocks.TORCH.defaultBlockState());
            placed++;
            spot = null;
            return true;
        }
        private BlockPos findDarkSpot(ServerLevel level) {
            BlockPos c = ly.getOwner() != null ? ly.getOwner().blockPosition() : ly.blockPosition();
            for (int i = 0; i < 40; i++) {
                BlockPos p = c.offset(ly.getRandom().nextIntBetweenInclusive(-7, 7), 0, ly.getRandom().nextIntBetweenInclusive(-7, 7));
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos q = p.above(dy);
                    if (!level.getBlockState(q).isAir() || !level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) continue;
                    if (level.getBrightness(LightLayer.BLOCK, q) > 6) continue;
                    if (ly.getOwner() != null && ly.getOwner().blockPosition().closerThan(q, 2)) continue;
                    return q.immutable();
                }
            }
            return null;
        }
        @Override public boolean succeeded() { return placed > 0; }

        public static boolean canLight(LyMeowzEntity ly) {
            return ly.inv().has(Items.TORCH) || (ly.inv().count(ItemTags.COALS) > 0 && (ly.inv().has(Items.STICK) || ly.inv().count(ItemTags.PLANKS) >= 2 || ly.inv().count(ItemTags.LOGS) > 0));
        }
    }

    @SuppressWarnings("unused")
    private static double unused(ItemStack s) { return Tools.fuelValue(s); }
}
