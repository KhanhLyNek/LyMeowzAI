package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.house.Blueprint;
import com.khanhly.lymeowz.house.Materials;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Xây nhà theo bản vẽ, từng chút một như người chơi:
 * <ol>
 *   <li>Đặt một cái rương tạm cạnh chỗ xây để cất đồ.</li>
 *   <li>Dọn chỗ: chặt cây, đập đất đá, nhổ cỏ trong phạm vi nhà.</li>
 *   <li>Đắp nền, rồi xây các khối đặc từ dưới lên, rồi tới cầu thang, phiến, kính, cửa, đồ đạc.</li>
 * </ol>
 * Mỗi block tốn nguyên liệu thật ({@link Materials}); thiếu thì lấy trong rương, rương cũng hết thì dừng lại đi kiếm.
 * Chỗ cao không với tới thì kê cột (nhảy lên đặt block dưới chân), xây xong đoạn đó thì đập cột đi xuống.
 */
public class BlueprintTask extends Task {
    private enum Phase { CHEST, CLEAR, BUILD, DONE }

    private final Blueprint bp;
    private final BlockPos origin;
    private final boolean decorOnly;
    private final Materials.Wallet wallet = new Materials.Wallet();
    private final BlockBreaker breaker = new BlockBreaker();
    private Phase phase = Phase.CHEST;
    private final Deque<BlockPos> clear = new ArrayDeque<>();
    private final Deque<Blueprint.Piece> queue = new ArrayDeque<>();
    private final Map<BlockPos, Integer> deferred = new HashMap<>();
    private final Set<BlockPos> plan = new HashSet<>();
    private final Map<Materials.Mat, Float> missing = new EnumMap<>(Materials.Mat.class);
    private int placed, cooldown, reachTicks, walkFails, unpaid, climbTries;
    // Cột kê để leo lên chỗ cao.
    private final List<BlockPos> pillar = new ArrayList<>();
    private boolean climbing, descending;
    private BlockPos pillarBase;
    private int targetStandY;
    private int pillarTicks;

    public BlueprintTask(LyMeowzEntity ly, Blueprint bp, BlockPos origin, boolean decorOnly) {
        super(ly);
        this.bp = bp;
        this.origin = origin.immutable();
        this.decorOnly = decorOnly;
        this.timeout = 20 * 60 * 8;
    }

    @Override public String name() { return decorOnly ? "trang trí nhà" : "xây nhà"; }

    @Override public String debug() {
        return phase + " clear=" + clear.size() + " queue=" + queue.size() + " placed=" + placed + " unpaid=" + unpaid
                + (climbing ? " climbing" : "") + (descending ? " descending" : "") + " pillar=" + pillar.size()
                + " reach=" + reachTicks + " walkFails=" + walkFails + " brk=" + breaker.target()
                + (queue.isEmpty() ? "" : " next=" + world(queue.peekFirst().pos()).toShortString() + " " + queue.peekFirst().state().getBlock())
                + (clear.isEmpty() ? "" : " clr=" + clear.peekFirst().toShortString());
    }

    @Override public boolean succeeded() { return placed > 0 || phase == Phase.DONE; }

    /** Nguyên liệu còn thiếu ở lần xây vừa rồi. */
    public Map<Materials.Mat, Float> missing() { return missing; }

    public BlockPos world(BlockPos rel) { return origin.offset(rel); }

    // ------------------------------------------------------------------ bắt đầu

    @Override
    public void start(ServerLevel level) {
        for (Blueprint.Piece p : bp.pieces) plan.add(world(p.pos()));
        // Dọn chỗ: mọi ô trong nhà phải trống mà đang có block.
        if (!decorOnly) {
            List<BlockPos> c = new ArrayList<>();
            for (BlockPos rel : bp.air) {
                BlockPos w = world(rel);
                BlockState st = level.getBlockState(w);
                if (st.isAir() || !st.getFluidState().isEmpty() || ly.ownsBlockOtherThan(w, origin)) continue;
                // Đuốc, nến... (không có va chạm, không phải cây con) trong nhà thì cứ để.
                if (!st.canBeReplaced() && st.getCollisionShape(level, w).isEmpty() && !st.is(net.minecraft.tags.BlockTags.SAPLINGS)) continue;
                c.add(w);
            }
            // Từ trên xuống (chặt ngọn cây trước), rồi gần trước xa sau.
            c.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()));
            clear.addAll(c);
        }
        // Thứ tự xây: nền đất, khối đặc từ dưới lên, rồi các thứ khác từ dưới lên.
        List<Blueprint.Piece> solid = new ArrayList<>(), detail = new ArrayList<>();
        for (Blueprint.Piece p : bp.pieces) {
            if (p.pos().getY() == 0 || p.state().isCollisionShapeFullBlock(level, world(p.pos()))) solid.add(p);
            else detail.add(p);
        }
        Comparator<Blueprint.Piece> byY = Comparator.comparingInt((Blueprint.Piece p) -> p.pos().getY())
                .thenComparingInt(p -> p.pos().getX()).thenComparingInt(p -> p.pos().getZ());
        solid.sort(byY);
        detail.sort(byY);
        queue.addAll(solid);
        queue.addAll(detail);
        phase = ly.structure("chest") != null && ly.structure("chest").distSqr(origin) < 40 * 40 ? Phase.CLEAR : Phase.CHEST;
        if (decorOnly) phase = Phase.BUILD;
    }

    // ------------------------------------------------------------------ vòng lặp

    @Override
    protected boolean run(ServerLevel level) {
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            breaker.cancel(level, ly);
            return true;
        }
        if (descending) return descend(level);
        if (climbing) return climb(level);
        if (cooldown-- > 0) return true;
        return switch (phase) {
            case CHEST -> placeChest(level);
            case CLEAR -> clearSite(level);
            case BUILD -> build(level);
            case DONE -> false;
        };
    }

    /** Rương tạm cạnh chỗ xây. */
    private boolean placeChest(ServerLevel level) {
        BlockPos spot = origin.offset(-2, 1, -2);
        for (int dy = -1; dy <= 2; dy++) {
            BlockPos s = spot.above(dy);
            if (level.getBlockState(s).canBeReplaced() && level.getBlockState(s.below()).isSolid()) { spot = s; break; }
        }
        if (!ly.inv().has(Items.CHEST)) {
            var steps = com.khanhly.lymeowz.survival.Crafting.plan(com.khanhly.lymeowz.survival.Crafting.copy(ly.inv()), Items.CHEST, false);
            if (steps != null) for (var s : steps) com.khanhly.lymeowz.survival.Crafting.execute(ly.inv(), s);
        }
        if (!ly.inv().has(Items.CHEST)) { phase = Phase.CLEAR; return true; }
        if (!near(level, spot)) return true;
        ly.inv().consume(Items.CHEST, 1);
        ly.placeBlock(level, spot, Blocks.CHEST.defaultBlockState());
        CompanionStore.Data d = ly.data();
        if (d != null) {
            d.structures.put("chest", new CompanionStore.Spot(Compat.dimId(level.dimension()), spot.getX(), spot.getY(), spot.getZ()));
            CompanionStore.markDirty();
        }
        phase = Phase.CLEAR;
        cooldown = 10;
        return true;
    }

    private boolean clearSite(ServerLevel level) {
        while (!clear.isEmpty()) {
            BlockPos p = clear.peekFirst();
            BlockState st = level.getBlockState(p);
            if (st.isAir() || !st.getFluidState().isEmpty()) { clear.pollFirst(); continue; }
            if (st.canBeReplaced()) {           // cỏ, hoa: nhổ luôn
                if (!near(level, p)) return true;
                level.destroyBlock(p, true, ly);
                ly.swingArm();
                clear.pollFirst();
                cooldown = 2;
                return true;
            }
            if (!near(level, p)) return true;
            clear.pollFirst();
            ly.holdBestFor(st);
            breaker.start(p);
            return true;
        }
        phase = Phase.BUILD;
        return true;
    }

    private boolean build(ServerLevel level) {
        while (!queue.isEmpty()) {
            Blueprint.Piece piece = queue.peekFirst();
            BlockPos w = world(piece.pos());
            BlockState now = level.getBlockState(w);
            if (same(now, piece.state()) || (piece.pos().getY() == 0 && !now.canBeReplaced())) { queue.pollFirst(); continue; }
            if (!now.isAir() && !now.canBeReplaced()) {
                // Có block khác chắn chỗ (đất trồi lên...): đập đi đã.
                if (!near(level, w)) return true;
                breaker.start(w);
                return true;
            }
            BlockState state = piece.pos().getY() == 0 ? Blocks.DIRT.defaultBlockState() : piece.state();
            Map<Materials.Mat, Float> cost = Materials.cost(state);
            if (!wallet.canPay(ly.inv(), cost)) {
                if (withdraw(level, cost)) return true;
                if (Materials.optional(piece.state())) { queue.pollFirst(); continue; } // đồ trang trí: để sau
                // Thiếu nguyên liệu cho khối này: xây những chỗ khác có đồ trước, khối này để lần sau.
                for (var e : cost.entrySet()) missing.merge(e.getKey(), e.getValue(), Float::sum);
                queue.pollFirst();
                unpaid++;
                continue;
            }
            if (!state.canSurvive(level, w)) { if (defer(piece)) continue; queue.pollFirst(); continue; }
            if (!near(level, w)) return true;
            // Đừng đặt đè lên người / thú.
            AABB box = new AABB(w);
            if (ly.getBoundingBox().intersects(box)) { stepAside(level, w); return true; }
            if (!level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != ly).isEmpty()) { if (defer(piece)) continue; return true; }
            if (!wallet.pay(ly.inv(), cost)) { queue.pollFirst(); unpaid++; return true; }
            ly.getLookControl().setLookAt(w.getX() + 0.5, w.getY() + 0.5, w.getZ() + 0.5, 30, 30);
            level.setBlock(w, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            var sound = state.getSoundType();
            level.playSound(null, w, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1f) / 2f, sound.getPitch() * 0.8f);
            ly.swingArm();
            queue.pollFirst();
            placed++;
            reachTicks = 0;
            walkFails = 0;
            climbTries = 0;
            cooldown = 3 + ly.getRandom().nextInt(4);
            onPlaced(level, w, state);
            return true;
        }
        if (!pillar.isEmpty()) { descending = true; return true; }
        if (unpaid > 0) return false; // hết đồ: đi kiếm thêm rồi quay lại xây tiếp
        phase = Phase.DONE;
        finish(level);
        return false;
    }

    private boolean defer(Blueprint.Piece p) {
        int n = deferred.merge(p.pos(), 1, Integer::sum);
        if (n > 3) return false;
        queue.pollFirst();
        queue.addLast(p);
        return true;
    }

    private void onPlaced(ServerLevel level, BlockPos w, BlockState st) {
        CompanionStore.Data d = ly.data();
        if (d == null) return;
        if (st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.HEAD) {
            d.bed = new CompanionStore.Spot(Compat.dimId(level.dimension()), w.getX(), w.getY(), w.getZ());
            d.structures.put("bed", d.bed);
            CompanionStore.markDirty();
        }
    }

    // ------------------------------------------------------------------ lấy đồ trong rương

    private int withdrawTries;

    private boolean withdraw(ServerLevel level, Map<Materials.Mat, Float> cost) {
        BlockPos chest = ly.structure("chest");
        if (chest == null || withdrawTries > 6 || chest.distSqr(origin) > 48 * 48) return false;
        BlockState st = level.getBlockState(chest);
        if (!(st.getBlock() instanceof ChestBlock cb)) return false;
        Container c = ChestBlock.getContainer(cb, st, level, chest, true);
        if (c == null) return false;
        boolean has = false;
        for (Materials.Mat m : cost.keySet()) if (Materials.amount(c, m) > 0) has = true;
        if (!has) return false;
        if (!near(level, chest)) return true;
        withdrawTries++;
        level.blockEvent(chest, st.getBlock(), 1, 1);
        level.playSound(null, chest, net.minecraft.sounds.SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1f);
        for (Materials.Mat m : cost.keySet()) {
            var src = Materials.source(m);
            int taken = 0;
            for (int i = 0; i < c.getContainerSize() && taken < 3; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !src.test(s)) continue;
                ItemStack left = ly.inv().put(s.copy());
                s.setCount(left.getCount());
                taken++;
                if (!left.isEmpty()) break;
            }
        }
        c.setChanged();
        level.blockEvent(chest, st.getBlock(), 1, 0);
        ly.swingArm();
        cooldown = 10;
        return true;
    }

    // ------------------------------------------------------------------ đi tới / kê cột

    /** Đứng trong tầm với của {@code p}: đi bộ tới, không được thì kê cột leo lên. */
    private boolean near(ServerLevel level, BlockPos p) {
        double reach = ly.getEyePosition().distanceTo(Vec3.atCenterOf(p));
        if (reach <= BlockBreaker.REACH - 0.4) { ly.getNavigation().stop(); return true; }
        if (!pillar.isEmpty() && reach > BlockBreaker.REACH + 2) { descending = true; return false; } // xong chỗ này rồi
        reachTicks++;
        if (reachTicks % 20 == 1) {   // tìm đường mỗi giây một lần thôi (tìm đường tốn công)
            var path = ly.getNavigation().createPath(p, 2);
            if (path != null && path.canReach()) ly.getNavigation().moveTo(path, 1.0);
            else {
                walkFails++;
                if (path != null) ly.getNavigation().moveTo(path, 1.0); // đi tới chỗ gần nhất có thể
            }
        }
        boolean high = p.getY() > ly.getBlockY() + 2;
        if (high && climbTries < 2 && (walkFails > 2 || reachTicks > 160) && reachTicks % 20 == 2) {
            climbTries++;
            startClimb(level, p);
            if (climbing) return false;
        }
        // Chỗ cao giữa mái, không kê cột được: đứng sát bên dưới với tay lên (như người chơi đứng trên mái đặt nốt).
        if (high && climbTries >= 2 && reachTicks > 200 && reach <= BlockBreaker.REACH + 3) { ly.getNavigation().stop(); return true; }
        if (reachTicks > 300) {
            // Không tới được: để sau (lá cây trên cao thì tự rụng khi chặt hết gỗ).
            if (!queue.isEmpty() && world(queue.peekFirst().pos()).equals(p)) { if (!defer(queue.peekFirst())) queue.pollFirst(); }
            else clear.remove(p);
            reachTicks = 0;
            walkFails = 0;
            climbTries = 0;
        }
        return false;
    }

    private int asideTicks;

    /** Đang đứng đúng chỗ cần đặt: bước sang chỗ khác (không phải chỗ sắp xây); không có chỗ thì để khối này sau. */
    private void stepAside(ServerLevel level, BlockPos w) {
        if (++asideTicks % 20 != 1) {
            if (asideTicks > 100) { asideTicks = 0; if (!defer(queue.peekFirst())) queue.pollFirst(); }
            return;
        }
        BlockPos feet = ly.blockPosition();
        for (int r = 1; r <= 4; r++) {
            for (BlockPos c : BlockPos.betweenClosed(feet.offset(-r, -1, -r), feet.offset(r, 1, r))) {
                if (Math.max(Math.abs(c.getX() - feet.getX()), Math.abs(c.getZ() - feet.getZ())) != r) continue;
                if (plan.contains(c) || plan.contains(c.above()) || !level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir()
                        || !level.getBlockState(c.below()).isSolid()) continue;
                ly.getNavigation().moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 1.0);
                return;
            }
        }
        asideTicks = 0;
        if (!defer(queue.peekFirst())) queue.pollFirst();
    }

    private void startClimb(ServerLevel level, BlockPos target) {
        if (ly.inv().count(HouseTask.FILL) < 3) return;
        // Cột ở ô gần chỗ cần xây mà không đè lên bản vẽ.
        BlockPos best = null;
        double bd = 1e9;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos col = new BlockPos(target.getX() + dx, ly.getBlockY(), target.getZ() + dz);
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos b = col.above(dy);
                if (!level.getBlockState(b.below()).isSolid() || !level.getBlockState(b).isAir()) continue;
                boolean free = true;
                for (int y = b.getY(); y <= target.getY() + 1 && free; y++) {
                    BlockPos c = new BlockPos(b.getX(), y, b.getZ());
                    if (!level.getBlockState(c).isAir()) free = false; // cột tạm, xong là đập đi nên đứng tạm trong nhà cũng được
                }
                if (!free) continue;
                double d = b.distSqr(ly.blockPosition()) + 2 * b.distSqr(new BlockPos(target.getX(), b.getY(), target.getZ()));
                if (d < bd) { bd = d; best = b; }
            }
        }
        if (best == null) return;
        pillarBase = best;
        targetStandY = Math.max(best.getY() + 1, target.getY() - 2);
        climbing = true;
        pillarTicks = 0;
        reachTicks = 0;
        walkFails = 0;
    }

    /** Nhảy lên, đặt block dưới chân, lặp lại (kê cột như người chơi). */
    private boolean climb(ServerLevel level) {
        if (++pillarTicks > 20 * 40) { climbing = false; descending = !pillar.isEmpty(); return true; }
        BlockPos feet = ly.blockPosition();
        if (pillar.isEmpty() && (feet.getX() != pillarBase.getX() || feet.getZ() != pillarBase.getZ())) {
            if (ly.getNavigation().isDone()) ly.getNavigation().moveTo(pillarBase.getX() + 0.5, pillarBase.getY(), pillarBase.getZ() + 0.5, 1.0);
            if (pillarTicks > 200) climbing = false;
            return true;
        }
        ly.getNavigation().stop();
        if (feet.getY() >= targetStandY) { climbing = false; return true; }
        if (!level.getBlockState(feet.above(2)).isAir()) { climbing = false; return true; } // đụng trần
        ly.getLookControl().setLookAt(ly.getX(), ly.getY() - 1, ly.getZ(), 40, 40);
        int nextY = pillar.isEmpty() ? pillarBase.getY() : pillar.get(pillar.size() - 1).getY() + 1;
        BlockPos place = new BlockPos(pillarBase.getX(), nextY, pillarBase.getZ());
        if (ly.onGround()) {
            ly.setPos(pillarBase.getX() + 0.5, ly.getY(), pillarBase.getZ() + 0.5); // đứng giữa ô
            ly.getJumpControl().jump();
        } else if (ly.getY() >= nextY + 1.0 && level.getBlockState(place).isAir()) {
            ItemStack b = ly.inv().takeOne(HouseTask.FILL);
            if (b.isEmpty() || !(b.getItem() instanceof BlockItem bi)) { climbing = false; return true; }
            ly.placeBlock(level, place, bi.getBlock().defaultBlockState());
            pillar.add(place);
        }
        return true;
    }

    /** Đập cột từ trên xuống để xuống đất (lấy lại block). */
    private boolean descend(ServerLevel level) {
        if (pillar.isEmpty()) { descending = false; return true; }
        BlockPos top = pillar.get(pillar.size() - 1);
        if (!level.getBlockState(top).isSolid()) { pillar.remove(pillar.size() - 1); return true; }
        ly.getLookControl().setLookAt(top.getX() + 0.5, top.getY() + 0.5, top.getZ() + 0.5, 40, 40);
        pillar.remove(pillar.size() - 1);
        breaker.start(top);
        return true;
    }

    // ------------------------------------------------------------------ xong

    private void finish(ServerLevel level) {
        CompanionStore.Data d = ly.data();
        if (d == null) return;
        String dim = Compat.dimId(level.dimension());
        if (!decorOnly) {
            // Còn thiếu khối chính nào không? (sót vài khối khó với tới thì vẫn ở được, lần trang trí sau sẽ đặt nốt)
            int total = 0, left = 0;
            for (Blueprint.Piece p : bp.pieces) {
                if (Materials.optional(p.state()) || p.pos().getY() == 0) continue;
                total++;
                if (!same(level.getBlockState(world(p.pos())), p.state())) left++;
            }
            if (left > Math.max(4, total / 50)) return;
            if (!d.builtHouses.contains(bp.name)) d.builtHouses.add(bp.name);
            d.structures.put("home", new CompanionStore.Spot(dim, origin.getX(), origin.getY(), origin.getZ()));
            d.homeBlueprint = bp.name;
            d.blueprint = null;
            d.blueprintAt = null;
            d.structures.remove("plan:home");
            BlockPos in = bp.inside != null ? world(bp.inside) : world(new BlockPos(bp.width / 2, 1, bp.length / 2));
            d.home = new CompanionStore.Spot(dim, in.getX(), in.getY(), in.getZ());
            CompanionStore.markDirty();
            ly.mind().remember(Mind.PlaceKind.HOME, in, 1);
            ly.mind().event(Mind.Event.BUILT, null, "nhà mới", in);
        }
    }

    /** Đã đúng khối theo bản vẽ chưa (bỏ qua trạng thái phụ: cửa đang mở, hàng rào nối, lá...). */
    static boolean same(BlockState now, BlockState want) {
        return now == want || now.is(want.getBlock());
    }

    /** Còn bao nhiêu block (khối chính / trang trí) chưa xây. */
    public static int[] remaining(ServerLevel level, Blueprint bp, BlockPos origin) {
        int main = 0, decor = 0;
        for (Blueprint.Piece p : bp.pieces) {
            BlockPos w = origin.offset(p.pos());
            BlockState now = level.getBlockState(w);
            if (same(now, p.state()) || (p.pos().getY() == 0 && !now.canBeReplaced())) continue;
            if (Materials.optional(p.state())) decor++;
            else main++;
        }
        return new int[]{main, decor};
    }

    /** Có bao nhiêu khối chính chưa xây mà nguyên liệu {@code have} đủ trả (để biết có nên đi xây tiếp không). */
    public static int affordable(ServerLevel level, Blueprint bp, BlockPos origin, Map<Materials.Mat, Float> have, int enough) {
        int n = 0;
        Map<Materials.Mat, Float> left = new EnumMap<>(Materials.Mat.class);
        left.putAll(have);
        for (Blueprint.Piece p : bp.pieces) {
            if (Materials.optional(p.state())) continue;
            BlockPos w = origin.offset(p.pos());
            BlockState now = level.getBlockState(w);
            if (same(now, p.state()) || (p.pos().getY() == 0 && !now.canBeReplaced())) continue;
            BlockState st = p.pos().getY() == 0 ? Blocks.DIRT.defaultBlockState() : p.state();
            var cost = Materials.cost(st);
            boolean ok = true;
            for (var e : cost.entrySet()) if (left.getOrDefault(e.getKey(), 0f) + 1e-3f < e.getValue()) { ok = false; break; }
            if (!ok) continue;
            for (var e : cost.entrySet()) left.merge(e.getKey(), -e.getValue(), Float::sum); // tính dồn: đồ trong túi chỉ trả được chừng ấy khối
            if (++n >= enough) return n;
        }
        return n;
    }

    /** Tổng nguyên liệu còn cần cho phần chưa xây. */
    public static Map<Materials.Mat, Float> need(ServerLevel level, Blueprint bp, BlockPos origin, boolean includeDecor) {
        Map<Materials.Mat, Float> out = new EnumMap<>(Materials.Mat.class);
        for (Blueprint.Piece p : bp.pieces) {
            BlockPos w = origin.offset(p.pos());
            BlockState now = level.getBlockState(w);
            if (same(now, p.state()) || (p.pos().getY() == 0 && !now.canBeReplaced())) continue;
            if (!includeDecor && Materials.optional(p.state())) continue;
            BlockState st = p.pos().getY() == 0 ? Blocks.DIRT.defaultBlockState() : p.state();
            for (var e : Materials.cost(st).entrySet()) out.merge(e.getKey(), e.getValue(), Float::sum);
        }
        return out;
    }

    @Override
    public void stop(ServerLevel level) {
        if (com.khanhly.lymeowz.command.SurvivalTest.running() && placed == 0 && phase != Phase.DONE)
            com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[survival]     (xây nhà dừng: {} thiếu={})", debug(), missing);
        breaker.cancel(level, ly);
        // Bị ngắt khi đang trên cột: đập cột xuống ngay (không để cột đất lơ lửng).
        for (int i = pillar.size() - 1; i >= 0; i--) {
            BlockPos p = pillar.get(i);
            BlockState st = level.getBlockState(p);
            if (!st.isAir()) {
                level.removeBlock(p, false);
                ly.inv().put(new ItemStack(st.getBlock().asItem()));
            }
        }
        pillar.clear();
    }
}
