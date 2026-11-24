package com.khanhly.lymeowz.world;

import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.Comparator;

/**
 * Chunk ticket tự hết hạn: LyMeowz làm mới ticket ở chunk mình đang đứng mỗi giây,
 * chunk cũ tự được nhả ra sau vài giây. Nhờ vậy cô ấy không bị "đóng băng"
 * khi đi xa người chơi, và không để lại chunk nào bị load mãi.
 */
public final class ChunkKeeper {
    private ChunkKeeper() {}

    /** Ticket theo chân LyMeowz, hết hạn sau 3 giây nếu không được làm mới. */
    public static final ChunkTicketType<ChunkPos> FOLLOW =
            ChunkTicketType.create("lymeowz_follow", Comparator.comparingLong(ChunkPos::toLong), 60);

    /** Ticket ngắn hạn dùng để load chỗ cô ấy đang ngủ khi cần tìm lại cô ấy. */
    public static final ChunkTicketType<ChunkPos> RECALL =
            ChunkTicketType.create("lymeowz_recall", Comparator.comparingLong(ChunkPos::toLong), 100);

    public static void keep(ServerWorld world, BlockPos pos, int radius) {
        ChunkPos cp = new ChunkPos(pos);
        world.getChunkManager().addTicket(FOLLOW, cp, radius, cp);
    }

    public static void recall(ServerWorld world, BlockPos pos) {
        ChunkPos cp = new ChunkPos(pos);
        world.getChunkManager().addTicket(RECALL, cp, 3, cp);
    }
}
