package com.khanhly.lymeowz.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;

/**
 * Dò khối trong một vùng thật nhanh: đọc thẳng từ từng khúc 16×16×16 của chunk đã nạp, và bỏ qua nguyên khúc nếu
 * bảng màu (palette) của nó cho biết chắc chắn không có loại khối cần tìm. Ví dụ tìm gỗ trong bán kính 48: trước
 * đây đọc ~66 000 ô, giờ phần lớn khúc (chỉ có đất, đá, không khí) bị bỏ qua ngay.
 * <p>
 * Không bao giờ nạp chunk mới.
 */
public final class BlockScan {
    private BlockScan() {}

    @FunctionalInterface
    public interface Visitor {
        /** @return false để dừng dò. */
        boolean visit(BlockPos.MutableBlockPos pos, BlockState state);
    }

    public static void forEach(ServerLevel level, BlockPos min, BlockPos max, Predicate<BlockState> want, Visitor v) {
        int x0 = Math.min(min.getX(), max.getX()), x1 = Math.max(min.getX(), max.getX());
        int z0 = Math.min(min.getZ(), max.getZ()), z1 = Math.max(min.getZ(), max.getZ());
        int y0 = Math.max(level.getMinY(), Math.min(min.getY(), max.getY()));
        int y1 = Math.min(level.getMaxY(), Math.max(min.getY(), max.getY()));
        if (y0 > y1) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        var chunks = level.getChunkSource();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                LevelChunk ch = chunks.getChunkNow(cx, cz);
                if (ch == null) continue;
                int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    LevelChunkSection sec = ch.getSection(level.getSectionIndexFromSectionY(sy));
                    if (sec.hasOnlyAir() || !sec.maybeHas(want)) continue;
                    int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
                    for (int y = ay; y <= by; y++)
                        for (int x = ax; x <= bx; x++)
                            for (int z = az; z <= bz; z++) {
                                BlockState st = sec.getBlockState(x & 15, y & 15, z & 15);
                                if (!want.test(st)) continue;
                                m.set(x, y, z);
                                if (!v.visit(m, st)) return;
                            }
                }
            }
        }
    }

    /** Khối gần {@code from} nhất thỏa {@code want} và {@code ok} (null nếu không có). */
    public static BlockPos nearest(ServerLevel level, BlockPos from, BlockPos min, BlockPos max, Predicate<BlockState> want,
                                   Predicate<BlockPos> ok) {
        BlockPos[] best = {null};
        double[] bd = {Double.MAX_VALUE};
        forEach(level, min, max, want, (p, st) -> {
            double d = p.distSqr(from);
            if (d < bd[0] && ok.test(p)) { bd[0] = d; best[0] = p.immutable(); }
            return true;
        });
        return best[0];
    }
}
