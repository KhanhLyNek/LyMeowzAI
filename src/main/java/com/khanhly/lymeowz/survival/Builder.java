package com.khanhly.lymeowz.survival;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Đặt từng block một như người chơi: đi tới chỗ với tới, nhìn vào, đặt (mỗi block vài tick),
 * không đặt đè lên người/thú, kẹt quá lâu ở một ô thì bỏ qua ô đó.
 */
public class Builder {
    public record Piece(BlockPos pos, Predicate<ItemStack> material, Function<ItemStack, BlockState> state) {}

    private final List<Piece> pieces = new ArrayList<>();
    private int index;
    private int cooldown;
    private int stuck;
    private int placed, skipped;
    private Vec3 lastPos = Vec3.ZERO;

    public void add(BlockPos pos, Predicate<ItemStack> material, Function<ItemStack, BlockState> state) {
        pieces.add(new Piece(pos.immutable(), material, state));
    }

    /** Một block lấy từ túi (đá cuội, ván...) đặt đúng như chính nó. */
    public void add(BlockPos pos, Predicate<ItemStack> material) {
        add(pos, material, s -> s.getItem() instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : null);
    }

    public int remaining() {
        return pieces.size() - index;
    }

    public int placed() { return placed; }
    public int skipped() { return skipped; }

    public boolean done() {
        return index >= pieces.size();
    }

    /** Số block mỗi loại vật liệu cần (ước lượng đơn giản: đếm số ô chưa có block). */
    public int needed(ServerLevel level) {
        int n = 0;
        for (int i = index; i < pieces.size(); i++) if (level.getBlockState(pieces.get(i).pos).canBeReplaced()) n++;
        return n;
    }

    /** @return false khi xong hoặc không làm tiếp được (hết vật liệu). */
    public boolean tick(LyMeowzEntity ly, ServerLevel level) {
        while (index < pieces.size()) {
            Piece p = pieces.get(index);
            BlockState here = level.getBlockState(p.pos);
            if (!here.canBeReplaced()) { index++; stuck = 0; continue; } // đã có block
            break;
        }
        if (done()) return false;
        Piece p = pieces.get(index);
        if (ly.inv().count(p.material) == 0) return false;

        double reach = ly.getEyePosition().distanceTo(Vec3.atCenterOf(p.pos));
        if (reach > BlockBreaker.REACH - 0.3) {
            if ((ly.getNavigation().isDone() && ly.tickCount % 10 == 0) || ly.tickCount % 40 == 0) {
                var path = ly.getNavigation().createPath(p.pos, 2);
                if (path != null) ly.getNavigation().moveTo(path, 1.0);
            }
            if (ly.tickCount % 20 == 0) {
                stuck = ly.position().distanceToSqr(lastPos) < 0.2 ? stuck + 20 : Math.max(0, stuck - 10);
                lastPos = ly.position();
            }
            if (stuck > 160) skip();
            return true;
        }
        if (cooldown-- > 0) return true;
        AABB box = new AABB(p.pos);
        if (ly.getBoundingBox().intersects(box)) {
            // Đang đứng đúng chỗ cần đặt: bước sang một ô trống không nằm trong bản vẽ.
            BlockPos away = freeSpot(ly, level, p.pos);
            if (away != null) ly.getNavigation().moveTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5, 1.0);
            if (++stuck > 60) defer();
            cooldown = 5;
            return true;
        }
        if (!level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != ly).isEmpty()) {
            // Có người/thú đứng ở đó: làm chỗ khác trước, lát quay lại.
            if (++stuck > 6) defer();
            cooldown = 10;
            return true;
        }
        ItemStack item = ly.inv().takeOne(p.material);
        BlockState st = item.isEmpty() ? null : p.state.apply(item);
        if (st == null || !st.canSurvive(level, p.pos)) {
            if (!item.isEmpty()) ly.inv().put(item);
            skip();
            return true;
        }
        ly.getNavigation().stop();
        ly.placeBlock(level, p.pos, st);
        placed++;
        index++;
        stuck = 0;
        cooldown = 4 + ly.getRandom().nextInt(3);
        return true;
    }

    private final java.util.Map<BlockPos, Integer> deferred = new java.util.HashMap<>();

    /** Đẩy ô này xuống cuối danh sách (tối đa 3 lần), làm ô khác trước. */
    private void defer() {
        Piece p = pieces.get(index);
        int n = deferred.merge(p.pos, 1, Integer::sum);
        if (n > 3 || index == pieces.size() - 1) { skip(); return; }
        pieces.remove(index);
        pieces.add(p);
        stuck = 0;
    }

    /** Ô đứng được gần {@code near} mà không nằm trong phần còn lại của bản vẽ. */
    private BlockPos freeSpot(LyMeowzEntity ly, ServerLevel level, BlockPos near) {
        java.util.Set<BlockPos> todo = new java.util.HashSet<>();
        for (int i = index; i < pieces.size(); i++) todo.add(pieces.get(i).pos);
        BlockPos best = null;
        double bd = 1e9;
        for (BlockPos c : BlockPos.betweenClosed(near.offset(-3, -1, -3), near.offset(3, 1, 3))) {
            if (todo.contains(c) || todo.contains(c.above())) continue;
            if (!level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir()) continue;
            if (!level.getBlockState(c.below()).isSolid()) continue;
            double d = c.distSqr(near);
            if (d < 2 || d > 12) continue;
            if (d < bd) { bd = d; best = c.immutable(); }
        }
        return best;
    }

    private void skip() {
        index++;
        skipped++;
        stuck = 0;
    }
}
