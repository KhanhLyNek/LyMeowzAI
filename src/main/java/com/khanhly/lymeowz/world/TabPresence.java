package com.khanhly.lymeowz.world;

import com.khanhly.lymeowz.LyMeowzMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Field;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cô ấy hiện trong bảng Tab như một người chơi đang online (tên, ping, chế độ sinh tồn).
 * <p>
 * Chỉ là một dòng trong danh sách người chơi gửi xuống máy khách (gói tin "player info"), không tạo người chơi thật.
 * UUID của dòng này có 7 byte đầu là "LyMeowz" để bản mod phía máy khách nhận ra và vẽ mặt cô ấy cạnh tên.
 */
public final class TabPresence {
    private TabPresence() {}

    /** 7 byte đầu UUID của dòng Tab: "LyMeowz". */
    public static final long MARK = 0x4C794D656F777AL;

    private record Shown(String name, String display, int latency) {}

    private static final Map<UUID, Shown> SHOWN = new HashMap<>();
    private static final Map<UUID, Map<UUID, Shown>> SENT = new HashMap<>(); // người chơi → các dòng đã gửi
    private static Field entriesField;
    private static boolean broken;

    public static UUID tabId(String companion) {
        UUID c = UUID.fromString(companion);
        return new UUID((MARK << 8) | (c.getMostSignificantBits() & 0xFF), c.getLeastSignificantBits());
    }

    public static boolean isOurs(UUID id) {
        return id != null && (id.getMostSignificantBits() >>> 8) == MARK;
    }

    public static void tick(MinecraftServer server) {
        if (broken || server.getTickCount() % 20 != 7) return;
        // Những dòng cần có.
        Map<UUID, Shown> want = new HashMap<>();
        for (CompanionStore.Data d : CompanionStore.all()) {
            if (d.companion == null) continue;
            UUID id = tabId(d.companion);
            Shown old = SHOWN.get(id);
            int ping = old == null ? 25 + (int) (Math.random() * 50)
                    : Math.max(12, Math.min(140, old.latency + (int) Math.round((Math.random() - 0.5) * 16)));
            String display = d.name != null ? d.name : "Cô gái lạ";
            want.put(id, new Shown(profileName(d.name), display, ping));
        }
        SHOWN.clear();
        SHOWN.putAll(want);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Map<UUID, Shown> sent = SENT.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
            List<UUID> remove = new ArrayList<>();
            for (UUID id : sent.keySet()) {
                Shown w = want.get(id);
                if (w == null || !w.name.equals(sent.get(id).name)) remove.add(id);
            }
            if (!remove.isEmpty()) {
                p.connection.send(new ClientboundPlayerInfoRemovePacket(remove));
                remove.forEach(sent::remove);
            }
            List<ClientboundPlayerInfoUpdatePacket.Entry> add = new ArrayList<>(), upd = new ArrayList<>();
            for (var e : want.entrySet()) {
                Shown before = sent.get(e.getKey());
                var entry = entry(e.getKey(), e.getValue());
                if (before == null) add.add(entry);
                else if (!Objects.equals(before.display, e.getValue().display) || before.latency != e.getValue().latency) upd.add(entry);
                sent.put(e.getKey(), e.getValue());
            }
            if (!add.isEmpty()) send(p, EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT), add);
            if (!upd.isEmpty()) send(p, EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), upd);
        }
        SENT.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** Người chơi vừa vào lại: gửi lại từ đầu. */
    public static void forget(UUID player) {
        SENT.remove(player);
    }

    public static void reset() {
        SHOWN.clear();
        SENT.clear();
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry entry(UUID id, Shown s) {
        Component display = Component.literal(s.display).withStyle(s.display.equals("Cô gái lạ") ? ChatFormatting.GRAY : ChatFormatting.LIGHT_PURPLE);
        return new ClientboundPlayerInfoUpdatePacket.Entry(id, new GameProfile(id, s.name), true, s.latency,
                GameType.SURVIVAL, display, true, 0, null);
    }

    private static void send(ServerPlayer p, EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions,
                             List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
        try {
            var packet = new ClientboundPlayerInfoUpdatePacket(actions, List.of());
            if (entriesField == null) {
                entriesField = ClientboundPlayerInfoUpdatePacket.class.getDeclaredField("entries");
                entriesField.setAccessible(true);
            }
            entriesField.set(packet, List.copyOf(entries));
            p.connection.send(packet);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            broken = true;
            LyMeowzMod.LOGGER.warn("[lymeowz] Không thêm được cô ấy vào bảng Tab: {}", ex.toString());
        }
    }

    /** Tên hồ sơ: chữ không dấu, không cách, ≤ 16 ký tự (như tên người chơi thật). */
    static String profileName(String name) {
        if (name == null) return "LyMeowz";
        String s = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (s.length() > 16) s = s.substring(0, 16);
        return s.length() < 3 ? "LyMeowz" : s;
    }
}
