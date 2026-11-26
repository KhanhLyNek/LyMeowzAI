package com.khanhly.lymeowz.command;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.MindData;
import com.khanhly.lymeowz.survival.Roadmap;
import com.khanhly.lymeowz.world.CompanionStore;
import com.khanhly.lymeowz.world.CompanionTracker;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /lymeowz info | say &lt;tin nhắn&gt; – ai cũng dùng được.
 * <br>Quản trị (OP): summon | inv | friendship &lt;-100..100&gt; | dismiss | selftest [survival] | reload
 */
public final class LyCommands {
    private LyCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(literal("lymeowz")
                .then(literal("info").executes(LyCommands::info))
                .then(literal("say").then(argument("message", StringArgumentType.greedyString())
                        .executes(c -> act(c, (ly, p) -> ly.brain.hear(p, StringArgumentType.getString(c, "message"))))))
                .then(literal("summon").requires(Compat::isOp).executes(LyCommands::summon))
                .then(literal("inv").requires(Compat::isOp).executes(c -> act(c, (ly, p) -> ly.openInventory(p))))
                .then(literal("friendship").requires(Compat::isOp).then(argument("value", IntegerArgumentType.integer(-100, 100))
                        .executes(c -> act(c, (ly, p) -> {
                            MindData.Relation r = ly.mind().data().relations.computeIfAbsent(p.getUUID().toString(), k -> new MindData.Relation());
                            r.name = p.getName().getString();
                            r.affinity = IntegerArgumentType.getInteger(c, "value");
                            CompanionStore.markDirty();
                            c.getSource().sendSuccess(() -> Component.literal("Độ thân = " + (int) r.affinity), false);
                        }))))
                .then(literal("dismiss").requires(Compat::isOp).executes(c -> act(c, (ly, p) -> {
                    ly.speak(com.khanhly.lymeowz.speech.Speech.Act.BYE);
                    ly.discard();
                })))
                .then(literal("perf").requires(Compat::isOp).executes(c -> {
                    var src = c.getSource();
                    long now = src.getServer().getTickCount();
                    if (!com.khanhly.lymeowz.util.Perf.on) {
                        com.khanhly.lymeowz.util.Perf.start(now);
                        src.sendSuccess(() -> Component.literal("Bắt đầu đo hiệu năng LyMeowz. Gõ lại /lymeowz perf để xem kết quả."), false);
                    } else {
                        for (String l : com.khanhly.lymeowz.util.Perf.report(now)) src.sendSuccess(() -> Component.literal(l), false);
                        com.khanhly.lymeowz.util.Perf.on = false;
                    }
                    return 1;
                }))
                .then(literal("selftest").requires(Compat::isOp)
                        .then(literal("survival").executes(c -> {
                            var src = c.getSource();
                            if (SurvivalTest.running()) return 0;
                            SurvivalTest.start(src.getServer(), line -> src.sendSuccess(() -> Component.literal(line), false));
                            return 1;
                        }))
                        .executes(c -> {
                            var src = c.getSource();
                            if (SelfTest.running()) return 0;
                            SelfTest.start(src.getServer(), line -> src.sendSuccess(() -> Component.literal(line), false));
                            return 1;
                        }))
                .then(literal("reload").requires(Compat::isOp).executes(c -> {
                    LyConfig.load();
                    c.getSource().sendSuccess(() -> Component.literal("Đã tải lại config/lymeowz.json"), true);
                    return 1;
                })));
    }

    /** Tạo cô ấy (nếu thế giới chưa có), hoặc đưa cô ấy tới gần bạn. */
    private static int summon(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        LyMeowzEntity ly = CompanionTracker.nearest(p);
        if (ly != null) {
            ly.teleportNear((ServerLevel) p.level(), p.blockPosition(), 3, 6);
        } else if (CompanionStore.isEmpty()) {
            ly = CompanionTracker.spawnNear(p, 4, 8);
        } else {
            CompanionStore.Data d = CompanionStore.list().get(0);
            ly = CompanionTracker.respawn((ServerLevel) p.level(), d);
            if (ly != null) ly.teleportNear((ServerLevel) p.level(), p.blockPosition(), 3, 6);
        }
        return ly != null ? 1 : 0;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        if (CompanionStore.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("Thế giới này chưa có cô ấy."), false);
            return 0;
        }
        LyMeowzEntity ly = CompanionTracker.nearest(p);
        if (ly == null) {
            c.getSource().sendSuccess(() -> Component.literal("Cô ấy đang ở một nơi chưa được load."), false);
            return 0;
        }
        String n = ly.displayName();
        float a = ly.affinity(p);
        String tier = a >= LyMeowzEntity.CLOSE ? "bạn thân" : a >= LyMeowzEntity.FRIEND ? "bạn bè"
                : a >= LyMeowzEntity.ACQUAINTANCE ? "người quen" : a > -30 ? "người lạ" : "ghét bạn";
        String msg = (n != null ? n : "Cô gái lạ") + " – " + ly.status()
                + "\nĐộ thân với bạn: " + (int) a + "/100 (" + tier + ")"
                + (Compat.isOp(c.getSource()) ? "\nỞ: " + Compat.dimId(ly.level().dimension()) + " " + ly.blockPosition().toShortString()
                + (ly.getHomePos() != null ? ", nhà " + ly.getHomePos().toShortString() : ", chưa có nhà")
                + "\n" + Roadmap.describe(ly, true) : "");
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int act(CommandContext<CommandSourceStack> c, BiConsumer<LyMeowzEntity, ServerPlayer> action) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        LyMeowzEntity ly = CompanionTracker.nearest(p);
        if (ly == null) {
            c.getSource().sendFailure(Component.literal("Không tìm thấy cô ấy (chưa có, hoặc đang ở nơi chưa được load)."));
            return 0;
        }
        action.accept(ly, p);
        return 1;
    }
}
