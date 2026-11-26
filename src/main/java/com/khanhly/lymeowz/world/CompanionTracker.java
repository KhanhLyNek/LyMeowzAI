package com.khanhly.lymeowz.world;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Giữ cho cô ấy "sống" như một người chơi:
 * <ul>
 *   <li>Server bật lên → load chỗ cô ấy đang ở để cô ấy tiếp tục cuộc sống (kể cả khi không ai ở gần).</li>
 *   <li>Lần đầu có người chơi vào thế giới → cô ấy xuất hiện ở đâu đó cách 40–60 block (một lần cho cả thế giới).</li>
 *   <li>Không tìm thấy cô ấy trong thế giới (entity bị mất) → tạo lại ở chỗ cũ với nguyên trí nhớ, túi đồ, nhà cửa.</li>
 *   <li>Người chơi giết con quái đang đánh cô ấy → cô ấy ghi nhận (thân hơn).</li>
 * </ul>
 */
public final class CompanionTracker {
    private CompanionTracker() {}

    private static final int RECALL_TIMEOUT = 20 * 30;
    private static final Map<UUID, Integer> PENDING = new HashMap<>();
    private static int spawnCountdown = -1;
    @Nullable private static UUID spawnFor;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { CompanionStore.load(s); recallAll(s); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> CompanionStore.save());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> { CompanionStore.unload(); PENDING.clear(); spawnCountdown = -1; TabPresence.reset(); });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> { TabPresence.forget(handler.player.getUUID()); onJoin(server, handler.player); });
        ServerTickEvents.END_SERVER_TICK.register(CompanionTracker::tick);
        ServerEntityEvents.ENTITY_LOAD.register(CompanionTracker::onLoad);
        ServerLivingEntityEvents.AFTER_DEATH.register(CompanionTracker::onDeath);
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> {
            if (!alive) for (LyMeowzEntity ly : loaded(((ServerLevel) newP.level()).getServer())) ly.bond.onPlayerRespawn(newP);
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer)) return;
            for (LyMeowzEntity ly : loaded(sl.getServer())) if (ly.level() == level && ly.ownsBlock(pos)) ly.bond.onHouseBroken(player);
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp
                    && player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem) {
                for (LyMeowzEntity ly : loaded(sl.getServer())) if (ly.level() == level) ly.bond.onPlayerBuild(sp, hit.getBlockPos());
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
    }

    /** Mọi LyMeowz đang được load. */
    public static List<LyMeowzEntity> loaded(MinecraftServer server) {
        List<LyMeowzEntity> out = new ArrayList<>();
        for (CompanionStore.Data d : CompanionStore.list()) {
            LyMeowzEntity ly = find(server, d);
            if (ly != null) out.add(ly);
        }
        return out;
    }

    /** LyMeowz gần người chơi nhất (đang được load). */
    @Nullable
    public static LyMeowzEntity nearest(ServerPlayer p) {
        LyMeowzEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LyMeowzEntity ly : loaded(((ServerLevel) p.level()).getServer())) {
            double d = ly.level() == p.level() ? ly.distanceToSqr(p) : 1e12;
            if (d < bd) { bd = d; best = ly; }
        }
        return best;
    }

    @Nullable
    public static LyMeowzEntity find(MinecraftServer server, CompanionStore.Data d) {
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

    private static void recallAll(MinecraftServer server) {
        for (CompanionStore.Data d : CompanionStore.list()) PENDING.put(UUID.fromString(d.companion), RECALL_TIMEOUT);
    }

    private static void onJoin(MinecraftServer server, ServerPlayer player) {
        server.execute(() -> { for (LyMeowzEntity ly : loaded(server)) ly.bond.onPlayerJoin(player); });
        if (CompanionStore.isEmpty() && LyConfig.get().spawnOnFirstJoin && spawnCountdown < 0) {
            spawnCountdown = 100;
            spawnFor = player.getUUID();
        }
    }

    private static void tick(MinecraftServer server) {
        com.khanhly.lymeowz.command.SelfTest.tick(server);
        com.khanhly.lymeowz.command.SurvivalTest.tick(server);
        TabPresence.tick(server);

        if (spawnCountdown > 0 && --spawnCountdown == 0) {
            spawnCountdown = -1;
            ServerPlayer p = spawnFor == null ? null : server.getPlayerList().getPlayer(spawnFor);
            if (p != null && CompanionStore.isEmpty()) {
                LyMeowzEntity ly = spawnNear(p, 40, 60);
                if (ly != null) LyMeowzMod.LOGGER.info("Có một cô gái xuất hiện ở {}", ly.blockPosition().toShortString());
            }
        }

        // Đang đi tìm cô ấy (load chunk chỗ cô ấy ở lần cuối).
        for (Iterator<Map.Entry<UUID, Integer>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> r = it.next();
            CompanionStore.Data d = CompanionStore.get(r.getKey());
            if (d == null) { it.remove(); continue; }
            if (find(server, d) != null) { it.remove(); continue; }
            ResourceKey<Level> key = d.last == null ? null : Compat.dim(d.last.dim);
            ServerLevel l = key == null ? null : server.getLevel(key);
            if (l != null && r.getValue() % 20 == 0) Compat.recallChunk(l, new BlockPos(d.last.x, d.last.y, d.last.z));
            int left = r.getValue() - 1;
            if (left > 0) { r.setValue(left); continue; }
            it.remove();
            if (l != null) {
                LyMeowzMod.LOGGER.warn("Không tìm thấy LyMeowz {}, tạo lại ở chỗ cũ.", d.companion);
                respawn(l, d);
            }
        }

        // Kiểm tra định kỳ: cô ấy luôn phải có mặt trong thế giới.
        if (server.getTickCount() % 200 == 0) {
            for (CompanionStore.Data d : CompanionStore.list()) {
                UUID id = UUID.fromString(d.companion);
                if (!PENDING.containsKey(id) && find(server, d) == null) PENDING.put(id, RECALL_TIMEOUT);
            }
        }
        if (server.getTickCount() % 1200 == 0) CompanionStore.saveAsync();
    }

    private static void onLoad(Entity entity, ServerLevel level) {
        com.khanhly.lymeowz.command.SelfTest.onEntityLoad(entity, level);
        MonsterAggro.hook(entity);
        if (!(entity instanceof LyMeowzEntity ly)) return;
        if (CompanionStore.get(ly.getUUID()) == null && CompanionStore.isRetired(ly.getUUID())) {
            LyMeowzMod.LOGGER.info("Gỡ bản LyMeowz cũ {}", ly.getUUID());
            level.getServer().execute(ly::discard);
        }
    }

    /** Người chơi giết con quái đang đánh / đứng sát cô ấy = cứu cô ấy. */
    private static void onDeath(LivingEntity dead, net.minecraft.world.damagesource.DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer killer && dead.level() instanceof ServerLevel l)
            for (LyMeowzEntity ly : l.getEntitiesOfClass(LyMeowzEntity.class, dead.getBoundingBox().inflate(48))) ly.bond.onKill(killer, dead);
        if (!(dead instanceof Enemy) || !(dead instanceof Mob mob) || !(source.getEntity() instanceof ServerPlayer p)) return;
        if (!(dead.level() instanceof ServerLevel level)) return;
        for (LyMeowzEntity ly : level.getEntitiesOfClass(LyMeowzEntity.class, dead.getBoundingBox().inflate(10))) {
            boolean targeting = mob.getTarget() == ly || ly.getTarget() == mob || ly.getLastHurtByMob() == mob;
            if (targeting || (dead.distanceTo(ly) < 5 && ly.getHealth() < ly.getMaxHealth())) ly.savedBy(p, dead);
        }
    }

    /** Tạo cô ấy ở một chỗ trên mặt đất cách người chơi {@code min}–{@code max} block. */
    @Nullable
    public static LyMeowzEntity spawnNear(ServerPlayer player, int min, int max) {
        ServerLevel level = (ServerLevel) player.level();
        LyMeowzEntity ly = Compat.create(LyMeowzMod.LYMEOWZ, level);
        if (ly == null) return null;
        BlockPos at = player.blockPosition();
        for (int i = 0; i < 30; i++) {
            double ang = player.getRandom().nextDouble() * Math.PI * 2;
            int r = min + player.getRandom().nextInt(Math.max(1, max - min));
            int x = at.getX() + (int) (Math.cos(ang) * r), z = at.getZ() + (int) (Math.sin(ang) * r);
            if (level.dimensionType().hasCeiling()) break;
            Compat.keepChunk(level, new BlockPos(x, 0, z), 1);
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getFluidState(p.below()).isEmpty() || !level.getBlockState(p.below()).isSolid()) continue;
            at = p;
            break;
        }
        Compat.placeAt(ly, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getRandom().nextFloat() * 360f, 0f);
        CompanionStore.create(ly.getUUID(), new CompanionStore.Spot(Compat.dimId(level.dimension()), at.getX(), at.getY(), at.getZ()));
        level.addFreshEntity(ly);
        if (at.equals(player.blockPosition())) ly.teleportNear(level, at, 2, 4);
        return ly;
    }

    /** Tạo lại cô ấy ở chỗ cũ, giữ nguyên dữ liệu (bản cũ – nếu còn ở đâu đó – sẽ tự gỡ). */
    @Nullable
    public static LyMeowzEntity respawn(ServerLevel level, CompanionStore.Data d) {
        LyMeowzEntity ly = Compat.create(LyMeowzMod.LYMEOWZ, level);
        if (ly == null) return null;
        BlockPos at = d.last == null ? Compat.worldSpawn(level) : new BlockPos(d.last.x, d.last.y, d.last.z);
        Compat.placeAt(ly, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        CompanionStore.transfer(UUID.fromString(d.companion), ly.getUUID());
        level.addFreshEntity(ly);
        ly.teleportNear(level, at, 0, 3);
        return ly;
    }
}
