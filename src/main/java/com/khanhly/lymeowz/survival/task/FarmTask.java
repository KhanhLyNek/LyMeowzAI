package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Làm ruộng như người chơi: nhổ cỏ lấy hạt giống, cầm cuốc cày đất, gieo hạt (lúa / cà rốt / khoai),
 * nếu có xô nước thì đổ nước ở giữa ruộng. Ruộng đã có thì chỉ đi thu hoạch cây chín và gieo lại.
 */
public class FarmTask extends Task {
    public static final int SIZE = 5;

    private enum Phase { SEEDS, TILL, PLANT, HARVEST }

    private Phase phase;
    private BlockPos corner;
    private final BlockBreaker breaker = new BlockBreaker();
    private BlockPos grass;
    private int index;
    private int cooldown;
    private int harvested, planted, tilled;
    private int searchFail;
    private final List<BlockPos> cells = new ArrayList<>();

    /** Làm ruộng mới ở {@code corner}, hoặc thu hoạch ruộng cũ nếu {@code harvest}. */
    public FarmTask(LyMeowzEntity ly, BlockPos corner, boolean harvest) {
        super(ly);
        this.corner = corner.immutable();
        this.phase = harvest ? Phase.HARVEST : Phase.SEEDS;
        this.timeout = 20 * 60 * 4;
    }

    @Override public String name() { return phase == Phase.HARVEST ? "thu hoạch" : "làm ruộng"; }

    @Override public boolean succeeded() { return harvested > 0 || planted > 0; }

    public static int seeds(LyMeowzEntity ly) {
        return ly.inv().count(Items.WHEAT_SEEDS) + ly.inv().count(Items.CARROT) + ly.inv().count(Items.POTATO);
    }

    public static boolean hasHoe(LyMeowzEntity ly) {
        return ly.getMainHandItem().is(ItemTags.HOES) || ly.inv().count(ItemTags.HOES) > 0;
    }

    @Override
    public void start(ServerLevel level) {
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            if (x == SIZE / 2 && z == SIZE / 2) continue; // ô giữa để nước
            cells.add(corner.offset(x, 0, z));
        }
        if (phase == Phase.SEEDS && seeds(ly) >= 6) phase = Phase.TILL;
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            if (r == BlockBreaker.Result.OUT_OF_REACH) {
                BlockPos t = breaker.target();
                if (ly.getNavigation().isDone()) ly.walkTo(t, 1.0);
                return true;
            }
            breaker.cancel(level, ly);
            return true;
        }
        if (cooldown-- > 0) return true;
        return switch (phase) {
            case SEEDS -> gatherSeeds(level);
            case TILL -> till(level);
            case PLANT -> plant(level);
            case HARVEST -> harvest(level);
        };
    }

    /** Nhổ cỏ quanh đây cho tới khi có đủ hạt. */
    private boolean gatherSeeds(ServerLevel level) {
        if (seeds(ly) >= 8 || age > 20 * 90) {
            if (seeds(ly) == 0) return false;
            phase = Phase.TILL;
            return true;
        }
        if (grass == null || !isGrass(level.getBlockState(grass))) {
            grass = findGrass(level);
            if (grass == null) {
                if (++searchFail > 3) {
                    if (seeds(ly) == 0) return false;
                    phase = Phase.TILL;
                }
                cooldown = 20;
                return true;
            }
        }
        if (!BlockBreaker.inReach(ly, grass)) {
            if (ly.getNavigation().isDone()) ly.walkTo(grass, 1.0);
            return true;
        }
        breaker.start(grass);
        grass = null;
        cooldown = 3;
        return true;
    }

    private static boolean isGrass(BlockState st) {
        return st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN);
    }

    private BlockPos findGrass(ServerLevel level) {
        return findGrass(ly, level, 48);
    }

    /** Bụi cỏ gần nhất (cắt cỏ ra hạt giống). Dò nhanh theo khúc chunk nên dò rộng được. */
    public static BlockPos findGrass(LyMeowzEntity ly, ServerLevel level, int r) {
        BlockPos c = ly.blockPosition();
        return com.khanhly.lymeowz.util.BlockScan.nearest(level, c, c.offset(-r, -4, -r), c.offset(r, 4, r), FarmTask::isGrass,
                p -> !ly.isProtected(p));
    }

    private boolean till(ServerLevel level) {
        if (!hasHoe(ly)) return false;
        while (index < cells.size()) {
            BlockPos cell = cells.get(index);
            BlockPos ground = cell.below();
            BlockState g = level.getBlockState(ground);
            if (g.is(Blocks.FARMLAND)) { index++; continue; }
            if (!(g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT) || g.is(Blocks.DIRT_PATH) || g.is(Blocks.COARSE_DIRT))) { index++; continue; }
            BlockState above = level.getBlockState(cell);
            if (!above.isAir()) {
                if (above.canBeReplaced() && above.getFluidState().isEmpty()) { breaker.start(cell); return true; }
                index++;
                continue;
            }
            if (!near(ground)) return true;
            holdHoe();
            ly.getLookControl().setLookAt(ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 0.5);
            ly.swingArm();
            level.setBlock(ground, Blocks.FARMLAND.defaultBlockState(), 11);
            level.playSound(null, ground, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1f, 1f);
            ItemStack hoe = ly.getMainHandItem();
            if (hoe.isDamageableItem()) hoe.hurtAndBreak(1, ly, EquipmentSlot.MAINHAND);
            tilled++;
            index++;
            cooldown = 6;
            return true;
        }
        // Nước ở giữa ruộng (nếu có xô nước).
        BlockPos mid = corner.offset(SIZE / 2, -1, SIZE / 2);
        if (ly.inv().has(Items.WATER_BUCKET) && !level.getFluidState(mid).isSource()) {
            if (!near(mid)) return true;
            ly.inv().consume(Items.WATER_BUCKET, 1);
            ly.inv().put(new ItemStack(Items.BUCKET));
            ly.swingArm();
            level.setBlock(mid, Blocks.WATER.defaultBlockState(), 11);
            level.playSound(null, mid, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1f, 1f);
        }
        index = 0;
        phase = Phase.PLANT;
        return true;
    }

    private void holdHoe() {
        if (ly.getMainHandItem().is(ItemTags.HOES)) return;
        int slot = ly.inv().bestSlot(s -> s.is(ItemTags.HOES) ? 1 + com.khanhly.lymeowz.survival.Tools.tier(s) : 0);
        if (slot >= 0) {
            ly.stowHand();
            ly.setItemSlot(EquipmentSlot.MAINHAND, ly.inv().takeSlot(slot));
        }
    }

    private boolean near(BlockPos p) {
        if (ly.getEyePosition().distanceTo(Vec3.atCenterOf(p)) <= BlockBreaker.REACH - 0.3) {
            ly.getNavigation().stop();
            return true;
        }
        if (ly.getNavigation().isDone()) ly.walkTo(p.above(), 1.0);
        if (age % 200 == 0) index++; // không tới được ô này
        return false;
    }

    private boolean plant(ServerLevel level) {
        while (index < cells.size()) {
            BlockPos cell = cells.get(index);
            if (!level.getBlockState(cell.below()).is(Blocks.FARMLAND) || !level.getBlockState(cell).isAir()) { index++; continue; }
            ItemStack seed = ly.inv().takeOne(s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO));
            if (seed.isEmpty()) return finishNew(level);
            if (!near(cell)) { ly.inv().put(seed); return true; }
            ly.placeBlock(level, cell, cropFor(seed.getItem()).defaultBlockState());
            level.playSound(null, cell, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1f, 1f);
            planted++;
            index++;
            cooldown = 5;
            return true;
        }
        return finishNew(level);
    }

    private static net.minecraft.world.level.block.Block cropFor(Item seed) {
        if (seed == Items.CARROT) return Blocks.CARROTS;
        if (seed == Items.POTATO) return Blocks.POTATOES;
        return Blocks.WHEAT;
    }

    private boolean finishNew(ServerLevel level) {
        CompanionStore.Data d = ly.data();
        if (d != null && !d.structures.containsKey("farm") && tilled + planted > 0) {
            d.structures.put("farm", new CompanionStore.Spot(Compat.dimId(level.dimension()), corner.getX(), corner.getY(), corner.getZ()));
            CompanionStore.markDirty();
            ly.mind().event(Mind.Event.BUILT, null, "ruộng", corner);
        }
        return false;
    }

    private BlockPos replant;
    private Item replantSeed;

    private boolean harvest(ServerLevel level) {
        if (replant != null) {
            if (level.getBlockState(replant).isAir() && level.getBlockState(replant.below()).is(Blocks.FARMLAND)) {
                ItemStack s = ly.inv().takeOne(x -> x.is(replantSeed));
                if (!s.isEmpty()) {
                    if (!near(replant)) { ly.inv().put(s); return true; }
                    ly.placeBlock(level, replant, cropFor(replantSeed).defaultBlockState());
                    planted++;
                }
            }
            replant = null;
            cooldown = 4;
            return true;
        }
        while (index < cells.size()) {
            BlockPos cell = cells.get(index);
            BlockState st = level.getBlockState(cell);
            if (st.isAir() && level.getBlockState(cell.below()).is(Blocks.FARMLAND) && seeds(ly) > 0) {
                replant = cell;
                replantSeed = ly.inv().count(Items.WHEAT_SEEDS) > 0 ? Items.WHEAT_SEEDS : ly.inv().has(Items.CARROT) ? Items.CARROT : Items.POTATO;
                index++;
                return true;
            }
            if (st.getBlock() instanceof CropBlock crop && crop.isMaxAge(st)) {
                if (!near(cell)) return true;
                replant = cell;
                replantSeed = st.is(Blocks.CARROTS) ? Items.CARROT : st.is(Blocks.POTATOES) ? Items.POTATO : Items.WHEAT_SEEDS;
                breaker.start(cell);
                harvested++;
                index++;
                return true;
            }
            index++;
        }
        return false;
    }

    /** Số ô chín trên ruộng. */
    public static int ripe(ServerLevel level, BlockPos corner) {
        int n = 0;
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            BlockState st = level.getBlockState(corner.offset(x, 0, z));
            if (st.getBlock() instanceof CropBlock c && c.isMaxAge(st)) n++;
        }
        return n;
    }

    /** Ô ruộng trống chưa gieo. */
    public static int empty(ServerLevel level, BlockPos corner) {
        int n = 0;
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            BlockPos p = corner.offset(x, 0, z);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).is(Blocks.FARMLAND)) n++;
        }
        return n;
    }

    /** Chọn chỗ làm ruộng: đất cỏ bằng phẳng gần nhà, ưu tiên cạnh nước. */
    public static BlockPos findSite(LyMeowzEntity ly, ServerLevel level, BlockPos home) {
        BlockPos best = null;
        double bestScore = 1e9;
        for (int i = 0; i < 120; i++) {
            int x = home.getX() + ly.getRandom().nextIntBetweenInclusive(-14, 14);
            int z = home.getZ() + ly.getRandom().nextIntBetweenInclusive(-14, 14);
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos c = new BlockPos(x, y, z);
            if (c.distSqr(home) < 6 * 6 || ly.isProtected(c)) continue;
            int good = 0;
            boolean ok = true;
            for (int dx = 0; dx < SIZE && ok; dx++) for (int dz = 0; dz < SIZE && ok; dz++) {
                BlockPos p = c.offset(dx, 0, dz);
                BlockState g = level.getBlockState(p.below());
                BlockState a = level.getBlockState(p);
                if (!(a.isAir() || (a.canBeReplaced() && a.getFluidState().isEmpty()))) ok = false;
                else if (g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT)) good++;
                else if (!g.is(BlockTags.DIRT) && !g.isSolid()) ok = false;
            }
            if (!ok || good < SIZE * SIZE - 3) continue;
            double s = Math.sqrt(c.distSqr(home));
            if (s < bestScore) { bestScore = s; best = c; }
        }
        return best;
    }

    @Override
    public void stop(ServerLevel level) {
        breaker.cancel(level, ly);
        if (phase != Phase.HARVEST && planted > 0) finishNew(level);
    }
}
