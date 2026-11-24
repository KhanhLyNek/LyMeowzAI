package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.survival.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Đào mỏ như người chơi: đào cầu thang xuống tới độ sâu mục tiêu, rồi đào hầm chính 1×2 có các nhánh hai bên,
 * cắm đuốc khi tối, thấy quặng ở thành hầm thì đào theo mạch, gặp nham thạch/nước thì bịt lại bằng đá,
 * xong thì đi ngược đường cũ lên mặt đất.
 */
public class StairMineTask extends Task {
    private enum Phase { WALK_OUT, DESCEND, TUNNEL, RETURN }

    private final int targetY;
    private final int tunnelLength;
    private Direction dir;
    private Phase phase = Phase.DESCEND;
    private BlockPos feet;
    private final List<BlockPos> trail = new ArrayList<>();
    private final List<BlockPos> waypoints = new ArrayList<>();
    private int wp;
    private final BlockBreaker breaker = new BlockBreaker();
    private final List<BlockPos> toClear = new ArrayList<>();
    private BlockPos moveTarget;
    private int moveTicks;
    private int returnIdx;
    private int stone, ores;
    private int lastTorchStep = -100;
    private int steps;
    private boolean aborted;
    private int stuckMoves;
    private int descended;
    private BlockPos walkOut;

    public StairMineTask(LyMeowzEntity ly, int targetY, int tunnelLength) {
        super(ly);
        this.targetY = targetY;
        this.tunnelLength = tunnelLength;
        this.timeout = 20 * 60 * 9;
    }

    @Override
    public String name() {
        return "đào mỏ";
    }

    @Override
    public boolean succeeded() {
        return ores > 0 || stone >= 16;
    }

    public int oresMined() { return ores; }

    @Override
    public void start(ServerLevel level) {
        feet = ly.blockPosition();
        if (ly.isProtected(feet)) {
            // Không đào mỏ ngay trong nhà / ruộng: đi ra chỗ trống cách đó một đoạn đã.
            for (int i = 0; i < 20 && walkOut == null; i++) {
                double ang = ly.getRandom().nextDouble() * Math.PI * 2;
                BlockPos c = feet.offset((int) (Math.cos(ang) * 14), 0, (int) (Math.sin(ang) * 14));
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
                BlockPos p = new BlockPos(c.getX(), y, c.getZ());
                if (!ly.isProtected(p) && level.getFluidState(p.below()).isEmpty()) walkOut = p;
            }
            if (walkOut == null) { aborted = true; return; }
            phase = Phase.WALK_OUT;
            ly.walkTo(walkOut, 1.0);
        }
        dir = Direction.Plane.HORIZONTAL.getRandomDirection(ly.getRandom());
        // Tránh đào thẳng về phía nước.
        for (int i = 0; i < 4 && !level.getFluidState(feet.relative(dir, 2).below()).isEmpty(); i++) dir = dir.getClockWise();
        trail.add(feet);
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (phase == Phase.WALK_OUT) {
            if (aborted || age > 20 * 40) return false;
            if (ly.blockPosition().closerThan(walkOut, 2.5)) {
                feet = ly.blockPosition();
                trail.clear();
                trail.add(feet);
                phase = Phase.DESCEND;
            } else if (ly.getNavigation().isDone()) ly.walkTo(walkOut, 1.0);
            return true;
        }
        if (age >= timeout - 20 * 60 && phase != Phase.RETURN) phase = Phase.RETURN; // chừa 1 phút để đi ra
        if (phase != Phase.RETURN && (ly.inv().isFull() || !hasPickaxe() || ly.getHealth() < 7 || aborted)) phase = Phase.RETURN;

        // 1) Đang phá dở một block.
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            breaker.cancel(level, ly);
            if (r != BlockBreaker.Result.DONE && !toClear.isEmpty()) toClear.remove(0); // không phá được thì bỏ qua ô đó
            return true;
        }
        // 2) Đang đi tới ô kế tiếp.
        if (moveTarget != null) {
            if (ly.blockPosition().equals(moveTarget) || (ly.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(moveTarget)) < 0.35)) {
                feet = moveTarget;
                moveTarget = null;
                stuckMoves = 0;
                if (phase == Phase.DESCEND) descended++;
                if (phase != Phase.RETURN) { trail.add(feet); afterStep(level); }
                return true;
            }
            if (++moveTicks > 80) { // kẹt: thử lại từ chỗ đang đứng
                feet = ly.blockPosition();
                moveTarget = null;
                if (++stuckMoves > 4) {
                    if (phase == Phase.RETURN) return false;
                    phase = Phase.RETURN;
                }
                return true;
            }
            if (ly.getNavigation().isDone()) ly.getNavigation().moveTo(moveTarget.getX() + 0.5, moveTarget.getY(), moveTarget.getZ() + 0.5, 1.0);
            return true;
        }
        // 3) Đào quặng lộ ra quanh đây trước.
        if (phase != Phase.RETURN) {
            BlockPos ore = visibleOre(level);
            if (ore != null) { breaker.start(ore); ores++; return true; }
        }
        // 4) Dọn các ô cho bước tiếp theo.
        while (!toClear.isEmpty()) {
            BlockPos c = toClear.get(0);
            BlockState st = level.getBlockState(c);
            if (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty()) { toClear.remove(0); continue; }
            if (!st.getFluidState().isEmpty()) { seal(level, c); toClear.remove(0); continue; }
            if (!BlockBreaker.inReach(ly, c)) { toClear.remove(0); continue; }
            if (ly.isProtected(c)) { toClear.clear(); pendingMove = null; turn(); return true; }
            sealAround(level, c);
            if (st.is(BlockTags.BASE_STONE_OVERWORLD)) stone++;
            breaker.start(c);
            return true;
        }
        if (pendingMove != null) {
            ensureFloor(level, pendingMove);
            moveTarget = pendingMove;
            pendingMove = null;
            moveTicks = 0;
            ly.getNavigation().moveTo(moveTarget.getX() + 0.5, moveTarget.getY(), moveTarget.getZ() + 0.5, 1.0);
            return true;
        }
        // 5) Lên kế hoạch bước tiếp theo.
        return switch (phase) {
            case DESCEND -> planDescend(level);
            case TUNNEL -> planTunnel(level);
            case RETURN -> planReturn();
            case WALK_OUT -> true;
        };
    }

    private BlockPos pendingMove;

    private boolean planDescend(ServerLevel level) {
        if (feet.getY() <= targetY || feet.getY() <= level.getMinY() + 6) {
            if (descended == 0 && feet.getY() <= level.getMinY() + 6) return false; // đã sát đáy thế giới, không đào cầu thang được
            phase = Phase.TUNNEL;
            buildWaypoints();
            return true;
        }
        BlockPos next = feet.relative(dir).below();
        if (dangerAhead(level, next)) { turn(); return ++steps < 400; }
        toClear.add(next.above(2));
        toClear.add(next.above());
        toClear.add(next);
        pendingMove = next;
        steps++;
        return true;
    }

    private void buildWaypoints() {
        Direction left = dir.getCounterClockWise(), right = dir.getClockWise();
        BlockPos base = feet;
        for (int i = 1; i <= tunnelLength; i++) {
            BlockPos m = base.relative(dir, i);
            waypoints.add(m);
            if (i % 4 == 0) {
                for (int j = 1; j <= 5; j++) waypoints.add(m.relative(left, j));
                for (int j = 4; j >= 0; j--) waypoints.add(m.relative(left, j));
                for (int j = 1; j <= 5; j++) waypoints.add(m.relative(right, j));
                for (int j = 4; j >= 0; j--) waypoints.add(m.relative(right, j));
            }
        }
    }

    private boolean planTunnel(ServerLevel level) {
        if (wp >= waypoints.size()) { phase = Phase.RETURN; return true; }
        BlockPos next = waypoints.get(wp++);
        if (dangerAhead(level, next)) { phase = Phase.RETURN; return true; }
        toClear.add(next.above());
        toClear.add(next);
        pendingMove = next;
        return true;
    }

    private boolean planReturn() {
        // Đi ngược lại đường cũ, mỗi chặng tối đa 8 ô.
        if (returnIdx == 0) returnIdx = trail.size() - 1;
        if (returnIdx <= 0) return false;
        returnIdx = Math.max(0, returnIdx - 8);
        pendingMove = trail.get(returnIdx);
        return true;
    }

    private void afterStep(ServerLevel level) {
        // Cắm đuốc chỗ tối, cách nhau ~7 bước.
        if (trail.size() - lastTorchStep >= 7 && level.getBrightness(LightLayer.BLOCK, feet) < 6 && level.getBrightness(LightLayer.SKY, feet) < 6) {
            if (ly.inv().has(Items.TORCH) || makeTorches()) {
                BlockPos t = trail.size() >= 2 ? trail.get(trail.size() - 2) : feet;
                if (level.getBlockState(t).isAir() && level.getBlockState(t.below()).isFaceSturdy(level, t.below(), Direction.UP)) {
                    ly.inv().consume(Items.TORCH, 1);
                    ly.placeBlock(level, t, Blocks.TORCH.defaultBlockState());
                    lastTorchStep = trail.size();
                }
            }
        }
    }

    private boolean makeTorches() {
        var steps = com.khanhly.lymeowz.survival.Crafting.plan(com.khanhly.lymeowz.survival.Crafting.copy(ly.inv()), Items.TORCH, false);
        if (steps == null) return false;
        for (var s : steps) com.khanhly.lymeowz.survival.Crafting.execute(ly.inv(), s);
        return ly.inv().has(Items.TORCH);
    }

    private void turn() {
        dir = ly.getRandom().nextBoolean() ? dir.getClockWise() : dir.getCounterClockWise();
    }

    /** Có nham thạch ngay chỗ sắp đào hoặc sát bên không. */
    private boolean dangerAhead(ServerLevel level, BlockPos next) {
        for (BlockPos p : BlockPos.betweenClosed(next.offset(-1, -1, -1), next.offset(1, 3, 1))) {
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** Bịt chất lỏng sát ô sắp đào bằng đá cuội. */
    private void sealAround(ServerLevel level, BlockPos c) {
        for (Direction d : Direction.values()) {
            BlockPos n = c.relative(d);
            if (toClear.contains(n) || n.equals(feet) || n.equals(feet.above())) continue;
            if (!level.getFluidState(n).isEmpty()) seal(level, n);
        }
    }

    private void seal(ServerLevel level, BlockPos p) {
        ItemStack block = ly.inv().takeOne(s -> s.is(ItemTags.STONE_TOOL_MATERIALS) || s.is(Items.DIRT) || s.is(Items.COBBLED_DEEPSLATE));
        if (block.isEmpty()) { aborted = true; return; }
        BlockState st = block.getItem() instanceof net.minecraft.world.item.BlockItem bi ? bi.getBlock().defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        ly.placeBlock(level, p, st);
    }

    /** Sàn dưới ô sắp bước tới phải chắc (không rơi xuống hang). */
    private void ensureFloor(ServerLevel level, BlockPos next) {
        BlockPos below = next.below();
        BlockState st = level.getBlockState(below);
        if (st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced()) seal(level, below);
    }

    private boolean hasPickaxe() {
        return Tools.isKind(ly.getMainHandItem(), Tools.Kind.PICKAXE) || ly.inv().count(s -> Tools.isKind(s, Tools.Kind.PICKAXE)) > 0;
    }

    public static boolean isOre(BlockState st) {
        return st.is(BlockItemTags.COAL_ORES.block()) || st.is(BlockTags.IRON_ORES) || st.is(BlockTags.COPPER_ORES)
                || st.is(BlockTags.GOLD_ORES) || st.is(BlockItemTags.REDSTONE_ORES.block()) || st.is(BlockItemTags.LAPIS_ORES.block())
                || st.is(BlockItemTags.DIAMOND_ORES.block()) || st.is(BlockItemTags.EMERALD_ORES.block());
    }

    /** Quặng nhìn thấy và với tới được, có dụng cụ đào ra đồ. */
    private BlockPos visibleOre(ServerLevel level) {
        BlockPos best = null;
        double bestD = 1e9;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-3, -1, -3), feet.offset(3, 3, 3))) {
            BlockState st = level.getBlockState(p);
            if (!isOre(st)) continue;
            if (!BlockBreaker.inReach(ly, p) || !BlockBreaker.canSee(ly, p)) continue;
            ItemStack tool = ly.bestToolFor(st);
            if (st.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(st)) continue;
            double d = p.distSqr(feet);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    @Override
    public void stop(ServerLevel level) {
        breaker.cancel(level, ly);
    }
}
