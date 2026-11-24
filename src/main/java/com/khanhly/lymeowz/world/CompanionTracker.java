package com.khanhly.lymeowz.world;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.chat.Lines;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Giữ liên lạc giữa người chơi và LyMeowz của họ:
 * <ul>
 *   <li>Người chơi vào game → load chunk nơi cô ấy ngủ, tìm cô ấy, rồi cô ấy tự chạy/dịch chuyển tới.</li>
 *   <li>Mỗi 5 giây: nếu chủ online mà cô ấy nằm trong chunk đã unload (ví dụ chủ /tp đi xa) → kéo cô ấy về.</li>
 *   <li>Chống nhân bản: chỉ một LyMeowz cho mỗi người chơi.</li>
 * </ul>
 */
public final class CompanionTracker {
    private CompanionTracker() {}

    private static final int RECALL_TIMEOUT = 200; // 10 giây

    private static final class Recall {
        int ticksLeft = RECALL_TIMEOUT;
        final boolean greet;
        Recall(boolean greet) { this.greet = greet; }
    }

    private static final Map<UUID, Recall> PENDING = new HashMap<>();
    private static final Map<UUID, Integer> PENDING_SPAWN = new HashMap<>();

    public static void init() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player, server));
        ServerTickEvents.END_SERVER_TICK.register(CompanionTracker::tick);
        ServerEntityEvents.ENTITY_LOAD.register(CompanionTracker::onLoad);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> { PENDING.clear(); PENDING_SPAWN.clear(); });
    }

    /** Tìm LyMeowz (đang được load) của người chơi. */
    @Nullable
    public static LyMeowzEntity find(MinecraftServer server, UUID owner) {
        CompanionState.Entry e = CompanionState.get(server).get(owner);
        if (e == null) return null;
        return findById(server, e);
    }

    @Nullable
    private static LyMeowzEntity findById(MinecraftServer server, CompanionState.Entry e) {
        ServerWorld preferred = server.getWorld(e.dimension);
        if (preferred != null && preferred.getEntity(e.companion) instanceof LyMeowzEntity ly && ly.isAlive()) return ly;
        for (ServerWorld w : server.getWorlds()) {
            Entity ent = w.getEntity(e.companion);
            if (ent instanceof LyMeowzEntity ly && ly.isAlive()) return ly;
        }
        return null;
    }

    /** Load chỗ cô ấy đang ngủ để tìm cô ấy (dùng cho lệnh khi cô ấy chưa được load). */
    public static void recall(UUID owner) {
        PENDING.putIfAbsent(owner, new Recall(false));
    }

    private static void onJoin(ServerPlayerEntity player, MinecraftServer server) {
        CompanionState.Entry e = CompanionState.get(server).get(player.getUuid());
        if (e == null) {
            if (LyConfig.get().autoSpawnOnFirstJoin) PENDING_SPAWN.put(player.getUuid(), 60);
        } else {
            PENDING.put(player.getUuid(), new Recall(true));
        }
    }

    private static void tick(MinecraftServer server) {
        CompanionState state = CompanionState.get(server);

        // Tạo mới cho người chơi lần đầu vào thế giới (đợi 3 giây cho thế giới load xong).
        for (Iterator<Map.Entry<UUID, Integer>> it = PENDING_SPAWN.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> s = it.next();
            int left = s.getValue() - 1;
            if (left > 0) { s.setValue(left); continue; }
            it.remove();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.getKey());
            if (p != null && state.get(p.getUuid()) == null) {
                LyMeowzEntity ly = spawnFor(p);
                if (ly != null) { ly.say(Lines.FIRST_MEET); ly.hearts(); }
            }
        }

        // Đang đi tìm cô ấy.
        for (Iterator<Map.Entry<UUID, Recall>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Recall> r = it.next();
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(r.getKey());
            CompanionState.Entry e = state.get(r.getKey());
            if (owner == null || e == null) { it.remove(); continue; }

            LyMeowzEntity ly = findById(server, e);
            if (ly != null) {
                it.remove();
                if (r.getValue().greet) ly.onOwnerReturned(owner);
                continue;
            }
            ServerWorld w = server.getWorld(e.dimension);
            if (w != null && r.getValue().ticksLeft % 20 == 0) ChunkKeeper.recall(w, e.lastPos);
            if (--r.getValue().ticksLeft <= 0) {
                it.remove();
                LyMeowzMod.LOGGER.warn("Không tìm thấy LyMeowz của {} tại {} {}, tạo lại cô ấy.",
                        owner.getName().getString(), e.dimension.getValue(), e.lastPos);
                LyMeowzEntity fresh = spawnFor(owner);
                if (fresh != null) fresh.say(Lines.WELCOME_BACK);
            }
        }

        // Kiểm tra định kỳ: chủ online mà cô ấy không được load → đi tìm.
        if (server.getTicks() % 100 == 0) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (PENDING.containsKey(p.getUuid())) continue;
                CompanionState.Entry e = state.get(p.getUuid());
                if (e != null && e.follows && findById(server, e) == null) PENDING.put(p.getUuid(), new Recall(false));
            }
        }
    }

    private static void onLoad(Entity entity, ServerWorld world) {
        if (!(entity instanceof LyMeowzEntity ly) || ly.getOwnerUuid() == null) return;
        CompanionState state = CompanionState.get(world.getServer());
        CompanionState.Entry e = state.get(ly.getOwnerUuid());
        if (e == null) {
            state.put(ly.getOwnerUuid(), ly.getUuid(), world.getRegistryKey(), ly.getBlockPos(), ly.getMode() == LyMeowzEntity.Mode.FOLLOW);
        } else if (!e.companion.equals(ly.getUuid())) {
            // Bản cũ bị bỏ lại (đã có bản mới thay thế) → gỡ đi để không bị trùng.
            LyMeowzMod.LOGGER.info("Gỡ bản LyMeowz trùng lặp {} của chủ {}", ly.getUuid(), ly.getOwnerUuid());
            world.getServer().execute(ly::discard);
        }
    }

    /** Tạo LyMeowz mới cạnh người chơi (thay thế bản cũ nếu có). */
    @Nullable
    public static LyMeowzEntity spawnFor(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        LyMeowzEntity old = find(player.getServer(), player.getUuid());
        if (old != null) old.discard();

        LyMeowzEntity ly = LyMeowzMod.LYMEOWZ.create(world);
        if (ly == null) return null;
        BlockPos at = player.getBlockPos();
        ly.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getYaw() + 180f, 0f);
        ly.initialize(world, world.getLocalDifficulty(at), SpawnReason.EVENT, null);
        ly.setOwner(player); // ghi vào CompanionState trước khi spawn để không bị coi là trùng
        world.spawnEntity(ly);
        ly.teleportNear(world, at, 1, 3);
        return ly;
    }
}
