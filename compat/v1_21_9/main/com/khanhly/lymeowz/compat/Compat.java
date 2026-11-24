package com.khanhly.lymeowz.compat;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;


/** Những chỗ API Minecraft khác nhau giữa các phiên bản – bản cho 1.21.9–1.21.10. */
public final class Compat {
    private Compat() {}

    // KEEP_DIMENSION_ACTIVE: từ 1.21.9 chiều không gian không có người chơi sẽ ngừng tick nếu không có ticket này.
    private static final int FLAGS = TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE;
    private static final TicketType FOLLOW = Registry.register(BuiltInRegistries.TICKET_TYPE, "lymeowz_follow",
            new TicketType(60L, FLAGS));
    private static final TicketType RECALL = Registry.register(BuiltInRegistries.TICKET_TYPE, "lymeowz_recall",
            new TicketType(100L, FLAGS));

    public static EntityType<LyMeowzEntity> registerEntity(String modId, String path, EntityType.EntityFactory<LyMeowzEntity> factory) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(modId, path));
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.of(factory, MobCategory.MISC)
                        .sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10)
                        .build(key));
    }

    @Nullable
    public static LyMeowzEntity create(EntityType<LyMeowzEntity> type, ServerLevel level) {
        return type.create(level, EntitySpawnReason.EVENT);
    }

    public static void placeAt(Entity e, double x, double y, double z, float yRot, float xRot) {
        e.snapTo(x, y, z, yRot, xRot);
    }

    public static void changeDimension(Entity e, ServerLevel target, Vec3 pos, float yRot, float xRot) {
        e.teleport(new TeleportTransition(target, pos, Vec3.ZERO, yRot, xRot, TeleportTransition.DO_NOTHING));
    }

    public static void keepChunk(ServerLevel level, BlockPos at, int radius) {
        ChunkPos pos = new ChunkPos(at.getX() >> 4, at.getZ() >> 4);
        level.getChunkSource().addTicketWithRadius(FOLLOW, pos, radius);
    }

    public static void recallChunk(ServerLevel level, BlockPos at) {
        ChunkPos pos = new ChunkPos(at.getX() >> 4, at.getZ() >> 4);
        level.getChunkSource().addTicketWithRadius(RECALL, pos, 3);
    }

    @Nullable
    public static ResourceKey<Level> dim(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        return rl == null ? null : ResourceKey.create(Registries.DIMENSION, rl);
    }

    public static String dimId(ResourceKey<Level> key) {
        return key.location().toString();
    }

    @Nullable
    public static GlobalPos respawn(ServerPlayer player) {
        ServerPlayer.RespawnConfig c = player.getRespawnConfig();
        return c == null ? null : c.respawnData().globalPos();
    }

    public static BlockPos worldSpawn(ServerLevel level) {
        return level.getRespawnData().pos();
    }

    public static boolean isOp(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    public static boolean isNight(Level level) {
        return level.isDarkOutside();
    }

    public static boolean isFlower(ItemStack stack) {
        return stack.is(ItemTags.SMALL_FLOWERS) || stack.is(Items.SUNFLOWER) || stack.is(Items.LILAC)
                || stack.is(Items.ROSE_BUSH) || stack.is(Items.PEONY);
    }

    public static void hearts(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.HEART, x, y, z, 5, 0.4, 0.3, 0.4, 0.0);
    }
}
