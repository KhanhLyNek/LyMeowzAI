package com.khanhly.lymeowz.world;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lưu (theo từng thế giới) mỗi người chơi có LyMeowz nào, cô ấy đang ở đâu,
 * để khi chunk của cô ấy đã unload vẫn tìm lại được.
 */
public class CompanionState extends PersistentState {
    public static final String ID = "lymeowz_companions";

    public static final class Entry {
        public UUID companion;
        public RegistryKey<World> dimension;
        public BlockPos lastPos;
        /** false khi cô ấy được bảo đứng đợi / ở nhà – khi đó không tự kéo cô ấy tới chủ. */
        public boolean follows = true;

        public Entry(UUID companion, RegistryKey<World> dimension, BlockPos lastPos) {
            this.companion = companion;
            this.dimension = dimension;
            this.lastPos = lastPos;
        }
    }

    private final Map<UUID, Entry> byOwner = new HashMap<>();

    public static CompanionState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(
                new PersistentState.Type<>(CompanionState::new, CompanionState::fromNbt, null), ID);
    }

    public Entry get(UUID owner) {
        return byOwner.get(owner);
    }

    public Map<UUID, Entry> all() {
        return byOwner;
    }

    public void put(UUID owner, UUID companion, RegistryKey<World> dim, BlockPos pos, boolean follows) {
        Entry e = byOwner.get(owner);
        if (e == null) {
            e = new Entry(companion, dim, pos.toImmutable());
            e.follows = follows;
            byOwner.put(owner, e);
            markDirty();
        } else if (!e.companion.equals(companion) || e.dimension != dim || !e.lastPos.equals(pos) || e.follows != follows) {
            e.companion = companion;
            e.dimension = dim;
            e.lastPos = pos.toImmutable();
            e.follows = follows;
            markDirty();
        }
    }

    public void remove(UUID owner) {
        if (byOwner.remove(owner) != null) markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList list = new NbtList();
        byOwner.forEach((owner, e) -> {
            NbtCompound c = new NbtCompound();
            c.putUuid("Owner", owner);
            c.putUuid("Companion", e.companion);
            c.putString("Dim", e.dimension.getValue().toString());
            c.putLong("Pos", e.lastPos.asLong());
            c.putBoolean("Follows", e.follows);
            list.add(c);
        });
        nbt.put("Companions", list);
        return nbt;
    }

    public static CompanionState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        CompanionState state = new CompanionState();
        NbtList list = nbt.getList("Companions", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            Identifier dimId = Identifier.tryParse(c.getString("Dim"));
            if (dimId == null) continue;
            Entry e = new Entry(c.getUuid("Companion"), RegistryKey.of(RegistryKeys.WORLD, dimId),
                    BlockPos.fromLong(c.getLong("Pos")));
            e.follows = !c.contains("Follows") || c.getBoolean("Follows");
            state.byOwner.put(c.getUuid("Owner"), e);
        }
        return state;
    }
}
