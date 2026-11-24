package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chăn nuôi: cầm thức ăn dụ thú về chuồng (thú đi theo người cầm đồ ăn), cho thú ăn qua hàng rào để chúng đẻ,
 * cắt lông cừu nếu có kéo, và khi chuồng đông quá thì làm thịt bớt.
 */
public class RanchTask extends Task {
    public enum Mode { LURE, BREED, SHEAR, CULL }

    private final Mode mode;
    private final BlockPos pen;
    private Animal animal;
    private int fed, lured, sheared;
    private boolean gateOpened;
    private int phase;

    public RanchTask(LyMeowzEntity ly, BlockPos pen, Mode mode, Animal animal) {
        super(ly);
        this.pen = pen.immutable();
        this.mode = mode;
        this.animal = animal;
        this.timeout = mode == Mode.LURE ? 20 * 90 : 20 * 40;
    }

    @Override public String name() { return "chăn nuôi"; }

    @Override public boolean succeeded() { return fed > 0 || lured > 0 || sheared > 0 || (mode == Mode.CULL && !animal.isAlive()); }

    public static boolean inPen(BlockPos pen, Animal a) {
        return PenTask.inside(pen).contains(a.position());
    }

    public static List<Animal> penned(ServerLevel level, BlockPos pen) {
        return level.getEntitiesOfClass(Animal.class, PenTask.inside(pen), a -> a.isAlive() && HuntTask.GAME.contains(a.getType()));
    }

    /** Thức ăn dụ / cho thú ăn mà cô ấy đang có. */
    public static boolean hasFoodFor(LyMeowzEntity ly, Animal a) {
        return ly.inv().count(a::isFood) > 0;
    }

    /** Loài đông nhất trong chuồng. */
    public static Map<net.minecraft.world.entity.EntityType<?>, List<Animal>> byType(List<Animal> list) {
        return list.stream().collect(Collectors.groupingBy(Animal::getType, Collectors.toList()));
    }

    @Override
    public void start(ServerLevel level) {
        if (mode == Mode.CULL) {
            ly.holdBestWeapon();
            ly.setTarget(animal);
            return;
        }
        // Cầm thức ăn trên tay cho thú thấy.
        int slot = ly.inv().bestSlot(s -> animal.isFood(s) ? 1 : 0);
        if (slot >= 0 && animal.isFood(ly.inv().getItem(slot))) {
            ly.stowHand();
            ly.setItemSlot(EquipmentSlot.MAINHAND, ly.inv().takeSlot(slot));
        }
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (animal == null || !animal.isAlive()) return false;
        return switch (mode) {
            case LURE -> lure(level);
            case BREED -> feed(level);
            case SHEAR -> shear(level);
            case CULL -> cull();
        };
    }

    private boolean cull() {
        if (ly.getTarget() != animal) ly.setTarget(animal);
        return animal.isAlive();
    }

    /** Dụ thú: đi tới gần nó, rồi đi chậm về chuồng – nó đi theo đồ ăn trên tay; tới cổng thì mở cổng cho vào. */
    private boolean lure(ServerLevel level) {
        if (inPen(pen, animal)) {
            lured++;
            BlockPos gate = PenTask.gate(pen);
            Vec3 out = Vec3.atBottomCenterOf(gate.north(2));
            if (ly.position().distanceTo(out) > 1.5 && phase < 3) {
                phase = 2;
                if (ly.getNavigation().isDone()) ly.getNavigation().moveTo(out.x, out.y, out.z, 1.0);
                return age < timeout;
            }
            setGate(level, false);
            return false;
        }
        if (!(ly.getMainHandItem().isEmpty() || animal.isFood(ly.getMainHandItem()))) return false;
        if (!animal.isFood(ly.getMainHandItem())) return false;
        double d = ly.distanceTo(animal);
        if (phase == 0) {
            if (d > 4) {
                if (ly.getNavigation().isDone() || age % 20 == 0) ly.getNavigation().moveTo(animal, 1.1);
                return d < 64;
            }
            phase = 1;
        }
        // Thú đi theo người cầm đồ ăn.
        if (age % 10 == 0 && d > 2) {
            animal.getNavigation().moveTo(ly, 1.1);
            animal.getLookControl().setLookAt(ly, 30f, 30f);
        }
        if (d > 10) { phase = 0; return true; } // lạc mất, quay lại tìm
        BlockPos gate = PenTask.gate(pen);
        Vec3 inside = Vec3.atBottomCenterOf(gate.south(2));
        Vec3 target = ly.position().distanceTo(Vec3.atCenterOf(gate)) < 3 ? inside : Vec3.atBottomCenterOf(gate.north(1));
        if (ly.position().distanceTo(Vec3.atCenterOf(gate)) < 4) setGate(level, true);
        if (ly.getNavigation().isDone() || age % 20 == 0) {
            // Đi chậm để thú theo kịp.
            ly.getNavigation().moveTo(target.x, target.y, target.z, d > 5 ? 0.5 : 0.8);
        }
        return true;
    }

    private void setGate(ServerLevel level, boolean open) {
        BlockPos gate = PenTask.gate(pen);
        BlockState st = level.getBlockState(gate);
        if (!(st.getBlock() instanceof FenceGateBlock) || st.getValue(FenceGateBlock.OPEN) == open) return;
        ly.getLookControl().setLookAt(gate.getX() + 0.5, gate.getY() + 0.5, gate.getZ() + 0.5);
        ly.swingArm();
        level.setBlock(gate, st.setValue(FenceGateBlock.OPEN, open), 10);
        level.playSound(null, gate, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1f, 1f);
        gateOpened = open;
    }

    /** Cho thú ăn qua hàng rào để chúng đẻ con. */
    private boolean feed(ServerLevel level) {
        if (animal.isInLove() || !animal.canFallInLove() || animal.isBaby()) return false;
        if (ly.distanceTo(animal) > 3.2) {
            if (ly.getNavigation().isDone() || age % 20 == 0) ly.getNavigation().moveTo(animal, 1.0);
            return true;
        }
        ItemStack food = ly.inv().takeOne(animal::isFood);
        if (food.isEmpty()) return false;
        ly.getNavigation().stop();
        ly.getLookControl().setLookAt(animal, 30f, 30f);
        ly.swingArm();
        animal.setInLove(null);
        level.playSound(null, animal.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6f, 1.2f);
        fed++;
        return false;
    }

    private boolean shear(ServerLevel level) {
        if (!(animal instanceof Sheep sheep) || !sheep.readyForShearing()) return false;
        if (ly.distanceTo(animal) > 3.2) {
            if (ly.getNavigation().isDone() || age % 20 == 0) ly.getNavigation().moveTo(animal, 1.0);
            return true;
        }
        int slot = ly.inv().bestSlot(s -> s.is(Items.SHEARS) ? 1 : 0);
        if (slot < 0 || !ly.inv().getItem(slot).is(Items.SHEARS)) return false;
        ly.stowHand();
        ly.setItemSlot(EquipmentSlot.MAINHAND, ly.inv().takeSlot(slot));
        ly.getNavigation().stop();
        ly.getLookControl().setLookAt(animal, 30f, 30f);
        ly.swingArm();
        sheep.setSheared(true);
        level.playSound(null, sheep.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1f, 1f);
        int n = 1 + ly.getRandom().nextInt(3);
        ly.inv().put(new ItemStack(Items.WOOL.pick(sheep.getColor()), n));
        ly.getMainHandItem().hurtAndBreak(1, ly, EquipmentSlot.MAINHAND);
        sheared += n;
        return false;
    }

    @Override
    public void stop(ServerLevel level) {
        if (gateOpened) setGate(level, false);
        if (mode == Mode.CULL && ly.getTarget() == animal) ly.setTarget(null);
    }

    /** Con thú hoang gần nhất (ngoài chuồng) mà cô ấy có đồ dụ được. */
    public static Animal wildToLure(LyMeowzEntity ly, ServerLevel level, BlockPos pen, int radius) {
        AABB box = ly.getBoundingBox().inflate(radius, 8, radius);
        return level.getEntitiesOfClass(Animal.class, box, a -> a.isAlive() && !a.isBaby() && HuntTask.GAME.contains(a.getType())
                        && !inPen(pen, a) && hasFoodFor(ly, a) && a.blockPosition().distSqr(pen) < 48 * 48)
                .stream().min(Comparator.comparingDouble(a -> a.distanceToSqr(ly))).orElse(null);
    }
}
