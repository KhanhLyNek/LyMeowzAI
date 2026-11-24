package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.survival.Builder;
import com.khanhly.lymeowz.survival.Crafting;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.function.Predicate;

/**
 * Xây một căn nhà nhỏ 5×5 (trong lòng 3×3) như người chơi mới vào game:
 * tường đá cuội cao 3, mái phẳng, cửa gỗ ở hướng bắc, đuốc trong nhà, bàn chế tạo và rương.
 * Chỗ xây được chọn ở nơi bằng phẳng, không có cây, gần chỗ cô ấy hay ở.
 */
public class HouseTask extends Task {
    public static final int SIZE = 5;
    /** Số block xây cần (tường + mái), chưa tính vá nền. */
    public static final int BLOCKS = 16 * 3 - 2 + SIZE * SIZE;

    public static final Predicate<ItemStack> WALL = s -> s.is(ItemTags.STONE_TOOL_MATERIALS) || s.is(Items.STONE_BRICKS)
            || s.is(Items.COBBLED_DEEPSLATE) || s.is(ItemTags.PLANKS);
    public static final Predicate<ItemStack> ROOF = s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.STONE_TOOL_MATERIALS) || s.is(Items.COBBLED_DEEPSLATE);
    public static final Predicate<ItemStack> FILL = s -> s.is(Items.DIRT) || WALL.test(s) || s.is(Items.GRANITE) || s.is(Items.DIORITE) || s.is(Items.ANDESITE);

    private final BlockPos corner;   // góc tây-bắc của nền nhà (tầng cô ấy đứng)
    private final Builder walls = new Builder();
    private final Builder roof = new Builder();
    private final Builder floor = new Builder();
    private int phase;
    private int wait;

    public HouseTask(LyMeowzEntity ly, BlockPos corner) {
        super(ly);
        this.corner = corner.immutable();
        this.timeout = 20 * 60 * 8;
    }

    @Override public String name() { return "xây nhà"; }

    @Override public boolean succeeded() { return phase >= 4; }

    // ------------------------------------------------------------------ bố cục (dùng chung với giường, rương...)

    public static BlockPos door(BlockPos c) { return c.offset(2, 0, 0); }
    public static BlockPos inside(BlockPos c) { return c.offset(2, 0, 2); }
    public static BlockPos bedFoot(BlockPos c) { return c.offset(1, 0, 2); }
    public static BlockPos bedHead(BlockPos c) { return c.offset(1, 0, 3); }
    public static BlockPos table(BlockPos c) { return c.offset(3, 0, 2); }
    public static BlockPos chest(BlockPos c) { return c.offset(3, 0, 3); }
    public static BlockPos torch(BlockPos c) { return c.offset(1, 0, 1); }

    public static boolean isWall(int x, int z) {
        return x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
    }

    @Override
    public void start(ServerLevel level) {
        CompanionStore.Data d = ly.data();
        if (d != null) {
            d.structures.put("plan:house", new CompanionStore.Spot(Compat.dimId(level.dimension()), corner.getX(), corner.getY(), corner.getZ()));
            CompanionStore.markDirty();
        }
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            BlockPos below = corner.offset(x, -1, z);
            BlockState st = level.getBlockState(below);
            if (st.canBeReplaced() || !st.getFluidState().isEmpty()) floor.add(below, FILL);
        }
        for (int y = 0; y < 3; y++) for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            if (!isWall(x, z)) continue;
            BlockPos p = corner.offset(x, y, z);
            if (p.getX() == door(corner).getX() && p.getZ() == door(corner).getZ() && y < 2) continue; // chừa cửa
            walls.add(p, WALL);
        }
        // Mái: từ ngoài vào trong để đứng dưới đất vẫn với tới.
        for (int ring = 0; ring <= 2; ring++) for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            if (Math.min(Math.min(x, z), Math.min(SIZE - 1 - x, SIZE - 1 - z)) != ring) continue;
            roof.add(corner.offset(x, 3, z), ROOF);
        }
        // Dọn cỏ/hoa trong lòng nhà.
        for (BlockPos p : BlockPos.betweenClosed(corner, corner.offset(SIZE - 1, 2, SIZE - 1))) {
            BlockState st = level.getBlockState(p);
            if (!st.isAir() && st.canBeReplaced() && st.getFluidState().isEmpty()) level.destroyBlock(p, false, ly);
        }
    }

    @Override
    protected boolean run(ServerLevel level) {
        switch (phase) {
            case 0 -> { if (!floor.tick(ly, level)) phase = 1; }
            case 1 -> {
                if (!walls.tick(ly, level)) {
                    if (!walls.done()) return false; // hết vật liệu
                    phase = 2;
                }
            }
            case 2 -> {
                if (!roof.tick(ly, level)) {
                    if (!roof.done()) return false;
                    phase = 3;
                    wait = 0;
                }
            }
            case 3 -> { if (furnish(level)) return true; phase = 4; finish(level); return false; }
            default -> { return false; }
        }
        return true;
    }

    /** Đặt cửa, đuốc, bàn chế tạo, rương. @return true khi còn đang làm. */
    private boolean furnish(ServerLevel level) {
        level0 = level;
        if (wait-- > 0) return true;
        BlockPos in = inside(corner);
        if (ly.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(in)) > 2.2) {
            if (ly.getNavigation().isDone()) ly.walkTo(in, 1.0);
            if (age % 400 == 0 && age > 0) ly.teleportNear(level, in, 0, 1); // lạc ở ngoài quá lâu
            return true;
        }
        BlockPos d = door(corner);
        if (level.getBlockState(d).isAir()) {
            if (ly.inv().has(Items.OAK_DOOR) || makeOne(Items.OAK_DOOR)) {
                ly.inv().consume(Items.OAK_DOOR, 1);
                BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH)
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
                ly.placeBlock(level, d, lower);
                level.setBlock(d.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
                wait = 8;
                return true;
            }
        }
        if (level.getBlockState(torch(corner)).isAir() && (ly.inv().has(Items.TORCH) || makeOne(Items.TORCH))) {
            ly.inv().consume(Items.TORCH, 1);
            ly.placeBlock(level, torch(corner), Blocks.TORCH.defaultBlockState());
            wait = 8;
            return true;
        }
        if (level.getBlockState(table(corner)).isAir() && (ly.inv().has(Items.CRAFTING_TABLE) || makeOne(Items.CRAFTING_TABLE))) {
            ly.inv().consume(Items.CRAFTING_TABLE, 1);
            ly.placeBlock(level, table(corner), Blocks.CRAFTING_TABLE.defaultBlockState());
            wait = 8;
            return true;
        }
        if (level.getBlockState(chest(corner)).isAir() && (ly.inv().has(Items.CHEST) || makeOne(Items.CHEST))) {
            ly.inv().consume(Items.CHEST, 1);
            ly.placeBlock(level, chest(corner), Blocks.CHEST.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.WEST));
            wait = 8;
            return true;
        }
        return false;
    }

    private ServerLevel level0;

    private boolean makeOne(net.minecraft.world.item.Item item) {
        var steps = Crafting.plan(Crafting.copy(ly.inv()), item, level0 != null && ly.findBlockNear(level0, Blocks.CRAFTING_TABLE, 6) != null);
        if (steps == null) return false;
        for (var s : steps) if (!Crafting.execute(ly.inv(), s)) return false;
        ly.swingArm();
        return ly.inv().has(item);
    }

    private void finish(ServerLevel level) {
        CompanionStore.Data d = ly.data();
        if (d == null) return;
        String dim = Compat.dimId(level.dimension());
        d.structures.remove("plan:house");
        d.structures.put("house", new CompanionStore.Spot(dim, corner.getX(), corner.getY(), corner.getZ()));
        BlockPos in = inside(corner);
        d.home = new CompanionStore.Spot(dim, in.getX(), in.getY(), in.getZ());
        if (level.getBlockState(chest(corner)).is(Blocks.CHEST)) {
            BlockPos c = chest(corner);
            d.structures.put("chest", new CompanionStore.Spot(dim, c.getX(), c.getY(), c.getZ()));
        }
        CompanionStore.markDirty();
        ly.mind().remember(Mind.PlaceKind.HOME, in, 1);
        ly.mind().event(Mind.Event.BUILT, null, "nhà", in);
    }

    // ------------------------------------------------------------------ chọn chỗ xây

    /** Tìm nền phẳng 5×5 (+1 vòng lối đi) quanh {@code center}. */
    public static BlockPos findSite(LyMeowzEntity ly, ServerLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < 160; i++) {
            int dx = ly.getRandom().nextIntBetweenInclusive(-radius, radius);
            int dz = ly.getRandom().nextIntBetweenInclusive(-radius, radius);
            int x = center.getX() + dx, z = center.getZ() + dz;
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos c = new BlockPos(x, y, z);
            int score = flatness(level, c);
            if (score < 0) continue;
            if (ly.isProtected(c)) continue;
            double s = score * 30 + Math.sqrt(dx * dx + dz * dz);
            if (s < bestScore) { bestScore = s; best = c; }
        }
        return best;
    }

    /** Độ gồ ghề của nền (số ô phải vá), -1 nếu không xây được. */
    private static int flatness(ServerLevel level, BlockPos c) {
        int patches = 0;
        for (int x = -1; x <= SIZE; x++) for (int z = -1; z <= SIZE; z++) {
            boolean ring = x < 0 || z < 0 || x >= SIZE || z >= SIZE;
            BlockPos below = c.offset(x, -1, z);
            BlockState g = level.getBlockState(below);
            if (!g.getFluidState().isEmpty() || g.is(BlockTags.LEAVES) || g.is(BlockTags.LOGS)) return -1;
            if (!ring && (g.canBeReplaced())) {
                if (!level.getBlockState(below.below()).isSolid()) return -1;
                patches++;
            }
            for (int y = 0; y <= (ring ? 1 : 3); y++) {
                BlockState st = level.getBlockState(c.offset(x, y, z));
                if (!st.canBeReplaced() || !st.getFluidState().isEmpty()) return -1;
            }
        }
        return patches > 6 ? -1 : patches;
    }
}
