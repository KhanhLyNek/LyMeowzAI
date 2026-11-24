package com.khanhly.lymeowz.world;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.chat.Lines;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
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
        ServerLifecycleEvents.SERVER_STARTED.register(CompanionStore::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> CompanionStore.save());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> { CompanionStore.unload(); PENDING.clear(); PENDING_SPAWN.clear(); });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(CompanionTracker::tick);
        ServerEntityEvents.ENTITY_LOAD.register(CompanionTracker::onLoad);
        if (Boolean.getBoolean("lymeowz.debug")) ServerEntityEvents.ENTITY_UNLOAD.register((e, l) -> {
            if (e instanceof LyMeowzEntity) LyMeowzMod.LOGGER.info("[debug] UNLOAD {} removed={} reason={}", e.blockPosition().toShortString(), e.isRemoved(), e.getRemovalReason());
        });
    }

    /** Tìm LyMeowz (đang được load) của người chơi. */
    @Nullable
    public static LyMeowzEntity find(MinecraftServer server, UUID owner) {
        CompanionStore.Data d = CompanionStore.byOwner(owner);
        return d == null ? null : findById(server, d);
    }

    @Nullable
    private static LyMeowzEntity findById(MinecraftServer server, CompanionStore.Data d) {
        UUID id;
        try { id = UUID.fromString(d.companion); } catch (IllegalArgumentException e) { return null; }
        ResourceKey<Level> key = d.last == null ? null : Compat.dim(d.last.dim);
        ServerLevel preferred = key == null ? null : server.getLevel(key);
        if (preferred != null && preferred.getEntity(id) instanceof LyMeowzEntity ly && ly.isAlive()) return ly;
        for (ServerLevel l : server.getAllLevels()) {
            if (l.getEntity(id) instanceof LyMeowzEntity ly && ly.isAlive()) return ly;
        }
        return null;
    }

    private static void onJoin(ServerPlayer player) {
        if (CompanionStore.byOwner(player.getUUID()) == null) {
            if (LyConfig.get().autoSpawnOnFirstJoin) PENDING_SPAWN.put(player.getUUID(), 60);
        } else {
            PENDING.put(player.getUUID(), new Recall(true));
        }
    }

    /** Load chỗ cô ấy đang ngủ để tìm cô ấy (dùng cho lệnh khi cô ấy chưa được load). */
    public static void recall(UUID owner) {
        PENDING.putIfAbsent(owner, new Recall(false));
    }

    private static void tick(MinecraftServer server) {
        com.khanhly.lymeowz.command.SelfTest.tick(server);
        com.khanhly.lymeowz.command.SurvivalTest.tick(server);
        // Tạo mới cho người chơi lần đầu vào thế giới (đợi 3 giây cho thế giới load xong).
        for (Iterator<Map.Entry<UUID, Integer>> it = PENDING_SPAWN.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> s = it.next();
            int left = s.getValue() - 1;
            if (left > 0) { s.setValue(left); continue; }
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(s.getKey());
            if (p != null && CompanionStore.byOwner(p.getUUID()) == null) {
                LyMeowzEntity ly = spawnFor(p);
                if (ly != null) { ly.say(Lines.FIRST_MEET); ly.hearts(); }
            }
        }

        // Đang đi tìm cô ấy.
        for (Iterator<Map.Entry<UUID, Recall>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Recall> r = it.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(r.getKey());
            CompanionStore.Data d = CompanionStore.byOwner(r.getKey());
            if (owner == null || d == null) { it.remove(); continue; }

            LyMeowzEntity ly = findById(server, d);
            if (ly != null) {
                it.remove();
                if (r.getValue().greet) ly.onOwnerReturned(owner);
                continue;
            }
            ResourceKey<Level> key = d.last == null ? null : Compat.dim(d.last.dim);
            ServerLevel l = key == null ? null : server.getLevel(key);
            if (l != null && r.getValue().ticksLeft % 20 == 0) {
                Compat.recallChunk(l, new BlockPos(d.last.x, d.last.y, d.last.z));
            }
            if (--r.getValue().ticksLeft <= 0) {
                it.remove();
                LyMeowzMod.LOGGER.warn("Không tìm thấy LyMeowz của {}, tạo lại cô ấy.", owner.getName().getString());
                LyMeowzEntity fresh = spawnFor(owner);
                if (fresh != null) fresh.say(Lines.WELCOME_BACK);
            }
        }

        // Kiểm tra định kỳ: chủ online, cô ấy đang đi theo mà không được load → đi tìm.
        if (server.getTickCount() % 100 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (PENDING.containsKey(p.getUUID())) continue;
                CompanionStore.Data d = CompanionStore.byOwner(p.getUUID());
                if (d != null && "FOLLOW".equals(d.mode) && findById(server, d) == null) {
                    PENDING.put(p.getUUID(), new Recall(false));
                }
            }
        }

        if (server.getTickCount() % 1200 == 0) CompanionStore.save();
    }

    private static void onLoad(Entity entity, ServerLevel level) {
        com.khanhly.lymeowz.command.SelfTest.onEntityLoad(entity, level);
        if (!(entity instanceof LyMeowzEntity ly)) return;
        if (CompanionStore.byCompanion(ly.getUUID()) == null && CompanionStore.isRetired(ly.getUUID())) {
            // Bản cũ bị bỏ lại (đã có bản mới thay thế) → gỡ đi để không bị trùng.
            LyMeowzMod.LOGGER.info("Gỡ bản LyMeowz cũ {}", ly.getUUID());
            level.getServer().execute(ly::discard);
        }
    }

    /** Tạo LyMeowz mới cạnh người chơi (bản cũ – nếu có – được đánh dấu để tự gỡ). */
    @Nullable
    public static LyMeowzEntity spawnFor(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        LyMeowzEntity old = find(level.getServer(), player.getUUID());

        LyMeowzEntity ly = Compat.create(LyMeowzMod.LYMEOWZ, level);
        if (ly == null) return null;
        BlockPos at = player.blockPosition();
        Compat.placeAt(ly, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getYRot() + 180f, 0f);
        // Gán trước: dữ liệu (nhà, lịch sử trò chuyện...) chuyển sang bản mới, bản cũ thành "nghỉ hưu".
        ly.setOwner(player);
        ly.setMode(LyMeowzEntity.Mode.FOLLOW);
        if (old != null) old.discard();
        level.addFreshEntity(ly);
        ly.teleportNear(level, at, 1, 3);
        return ly;
    }
}
