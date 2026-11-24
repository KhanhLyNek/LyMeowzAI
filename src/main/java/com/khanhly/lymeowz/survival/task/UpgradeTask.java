package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.survival.Builder;
import com.khanhly.lymeowz.survival.Crafting;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Nhà lớn dần theo thời gian: lần lượt cắm đuốc quanh nhà, làm vườn hoa trước cửa, trổ cửa sổ kính,
 * rồi xây thêm một phòng bên cạnh (có lò nung và rương thứ hai), thông với phòng cũ.
 */
public class UpgradeTask extends Task {
    public enum Kind {
        TORCHES("đuốc quanh nhà"), GARDEN("vườn hoa"), WINDOWS("cửa sổ"), ROOM2("phòng mới");
        public final String title;
        Kind(String t) { title = t; }
        public String key() { return "deco:" + name().toLowerCase(); }
    }

    private final Kind kind;
    private final BlockPos house;
    private final Builder builder = new Builder();
    private final BlockBreaker breaker = new BlockBreaker();
    private final List<BlockPos> toBreak = new ArrayList<>();
    private HouseTask room;
    private int phase;

    public UpgradeTask(LyMeowzEntity ly, BlockPos house, Kind kind) {
        super(ly);
        this.house = house.immutable();
        this.kind = kind;
        this.timeout = kind == Kind.ROOM2 ? 20 * 60 * 6 : 20 * 90;
    }

    @Override public String name() { return "sửa nhà"; }

    @Override public boolean succeeded() { return phase >= 9; }

    /** Việc sửa nhà tiếp theo chưa làm (theo thứ tự), hoặc null. */
    public static Kind next(LyMeowzEntity ly) {
        CompanionStore.Data d = ly.data();
        if (d == null) return null;
        for (Kind k : Kind.values()) if (!d.structures.containsKey(k.key())) return k;
        return null;
    }

    /** Đủ vật liệu chưa. */
    public static boolean ready(LyMeowzEntity ly, Kind k) {
        var all = ly.inventoryWithHand();
        return switch (k) {
            case TORCHES -> all.count(Items.TORCH) >= 4 || all.count(net.minecraft.tags.ItemTags.COALS) >= 1;
            case GARDEN -> all.count(s -> Compat.isFlower(s)) >= 4;
            case WINDOWS -> all.count(Items.GLASS) >= 3;
            case ROOM2 -> all.count(HouseTask.WALL) >= 64;
        };
    }

    /** Ô trống quanh nhà cho phòng mới (phía đông, dùng chung tường). */
    public static boolean roomFits(ServerLevel level, BlockPos house) {
        for (int x = 5; x <= 8; x++) for (int z = 0; z < HouseTask.SIZE; z++) {
            BlockPos p = house.offset(x, 0, z);
            if (!level.getBlockState(p.below()).isSolid() && !level.getBlockState(p.below()).canBeReplaced()) return false;
            for (int y = 0; y <= 3; y++) {
                BlockState st = level.getBlockState(p.above(y));
                if (!st.canBeReplaced() || !st.getFluidState().isEmpty()) return false;
            }
        }
        return true;
    }

    @Override
    public void start(ServerLevel level) {
        switch (kind) {
            case TORCHES -> {
                if (!ly.inv().has(Items.TORCH) || ly.inv().count(Items.TORCH) < 4) craft(Items.TORCH);
                int[][] corners = {{-1, -1}, {HouseTask.SIZE, -1}, {-1, HouseTask.SIZE}, {HouseTask.SIZE, HouseTask.SIZE}, {1, -1}, {3, -1}};
                for (int[] c : corners) {
                    BlockPos p = ground(level, house.offset(c[0], 0, c[1]));
                    if (p != null) builder.add(p, s -> s.is(Items.TORCH), s -> Blocks.TORCH.defaultBlockState());
                }
            }
            case GARDEN -> {
                for (int x = 0; x < HouseTask.SIZE; x++) {
                    if (x == 2) continue; // lối đi trước cửa
                    BlockPos p = house.offset(x, 0, -2);
                    if (level.getBlockState(p.below()).is(BlockTags.DIRT)) builder.add(p, Compat::isFlower,
                            s -> s.getItem() instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : null);
                }
            }
            case WINDOWS -> {
                for (BlockPos w : new BlockPos[]{house.offset(0, 1, 2), house.offset(HouseTask.SIZE - 1, 1, 2), house.offset(2, 1, HouseTask.SIZE - 1)}) {
                    if (!level.getBlockState(w).is(Blocks.GLASS)) toBreak.add(w);
                    builder.add(w, s -> s.is(Items.GLASS), s -> Blocks.GLASS.defaultBlockState());
                }
            }
            case ROOM2 -> room = new HouseTask(ly, house.offset(HouseTask.SIZE - 1, 0, 0), true);
        }
        if (room != null) room.start(level);
    }

    private BlockPos ground(ServerLevel level, BlockPos p) {
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos q = p.above(dy);
            if (level.getBlockState(q).canBeReplaced() && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) return q;
        }
        return null;
    }

    private void craft(net.minecraft.world.item.Item item) {
        var steps = Crafting.plan(Crafting.copy(ly.inv()), item, false);
        if (steps != null) for (var s : steps) Crafting.execute(ly.inv(), s);
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (room != null) {
            if (room.tick(level)) return true;
            if (!room.succeeded()) return false;
            // Thông cửa giữa hai phòng.
            if (phase < 5) {
                toBreak.add(house.offset(HouseTask.SIZE - 1, 0, 2));
                toBreak.add(house.offset(HouseTask.SIZE - 1, 1, 2));
                phase = 5;
            }
            if (breakNext(level)) return true;
            return finish(level);
        }
        if (breakNext(level)) return true;
        if (builder.tick(ly, level)) return true;
        if (!builder.done()) return false;   // hết vật liệu giữa chừng: lần sau làm tiếp
        return finish(level);                // xong (hoặc không còn chỗ nào để làm)
    }

    /** Đập các block cần bỏ (tường chỗ làm cửa sổ / cửa thông phòng). @return true khi còn đang đập. */
    private boolean breakNext(ServerLevel level) {
        if (breaker.target() != null) {
            BlockBreaker.Result r = breaker.tick(ly, level);
            if (r == BlockBreaker.Result.WORKING) return true;
            if (r == BlockBreaker.Result.OUT_OF_REACH && breaker.target() != null) {
                if (ly.getNavigation().isDone()) ly.walkTo(breaker.target(), 1.0);
                return true;
            }
            breaker.cancel(level, ly);
        }
        while (!toBreak.isEmpty()) {
            BlockPos p = toBreak.get(0);
            if (level.getBlockState(p).isAir() || level.getBlockState(p).is(Blocks.GLASS)) { toBreak.remove(0); continue; }
            if (!BlockBreaker.inReach(ly, p)) {
                if (ly.getNavigation().isDone()) ly.walkTo(p, 1.0);
                if (age % 300 == 299) toBreak.remove(0);
                return true;
            }
            toBreak.remove(0);
            breaker.start(p);
            return true;
        }
        return false;
    }

    private boolean finish(ServerLevel level) {
        phase = 9;
        CompanionStore.Data d = ly.data();
        if (d != null) {
            d.structures.put(kind.key(), new CompanionStore.Spot(Compat.dimId(level.dimension()), house.getX(), house.getY(), house.getZ()));
            CompanionStore.markDirty();
        }
        ly.mind().event(Mind.Event.BUILT, null, kind.title, house);
        return false;
    }

    @Override
    public void stop(ServerLevel level) {
        breaker.cancel(level, ly);
        if (room != null) room.stop(level);
    }
}
