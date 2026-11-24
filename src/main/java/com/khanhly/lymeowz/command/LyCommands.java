package com.khanhly.lymeowz.command;

import com.khanhly.lymeowz.chat.Lines;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.world.CompanionStore;
import com.khanhly.lymeowz.world.CompanionTracker;
import com.mojang.brigadier.CommandDispatcher;
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
 * /lymeowz summon | follow | stay | home | sethome | info | say &lt;tin nhắn&gt; | dismiss | reload
 */
public final class LyCommands {
    private LyCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(literal("lymeowz")
                .then(literal("summon").executes(LyCommands::summon))
                .then(literal("follow").executes(c -> act(c, (ly, p) -> { ly.setMode(LyMeowzEntity.Mode.FOLLOW); ly.say(Lines.FOLLOW); })))
                .then(literal("stay").executes(c -> act(c, (ly, p) -> { ly.setMode(LyMeowzEntity.Mode.STAY); ly.say(Lines.STAY); })))
                .then(literal("home").executes(c -> act(c, (ly, p) -> { ly.setMode(LyMeowzEntity.Mode.HOME); ly.say(Lines.GO_HOME); })))
                .then(literal("sethome").executes(c -> act(c, (ly, p) -> {
                    ly.setHome((ServerLevel) p.level(), p.blockPosition());
                    ly.say(Lines.HOME_SET);
                    ly.hearts();
                })))
                .then(literal("info").executes(LyCommands::info))
                .then(literal("say").then(argument("message", StringArgumentType.greedyString())
                        .executes(c -> act(c, (ly, p) -> ly.brain.hear(p, StringArgumentType.getString(c, "message")) ))))
                .then(literal("dismiss").executes(c -> act(c, (ly, p) -> {
                    ly.say("Mugyu~... tạm biệt nha. Gọi /lymeowz summon nếu cậu nhớ mình nhé...");
                    ly.discard();
                })))
                .then(literal("selftest").requires(Compat::isOp).executes(c -> {
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

    private static int summon(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        LyMeowzEntity ly = CompanionTracker.find(server(p), p.getUUID());
        if (ly != null) {
            ly.setMode(LyMeowzEntity.Mode.FOLLOW);
            ly.teleportToOwner(p);
            ly.say(Lines.COME);
        } else if (CompanionStore.byOwner(p.getUUID()) != null) {
            // Cô ấy đang ngủ trong chunk chưa load – tạo lại ngay cạnh bạn (bản cũ sẽ tự gỡ khi được load).
            ly = CompanionTracker.spawnFor(p);
            if (ly != null) ly.say(Lines.WELCOME_BACK);
        } else {
            ly = CompanionTracker.spawnFor(p);
            if (ly != null) { ly.say(Lines.FIRST_MEET); ly.hearts(); }
        }
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        CompanionStore.Data e = CompanionStore.byOwner(p.getUUID());
        LyMeowzEntity ly = CompanionTracker.find(server(p), p.getUUID());
        if (e == null) {
            c.getSource().sendSuccess(() -> Component.literal("Bạn chưa có LyMeowz. Dùng /lymeowz summon"), false);
            return 0;
        }
        String where = e.last == null ? "?" : e.last.dim + " " + e.last.x + ", " + e.last.y + ", " + e.last.z;
        String state = ly == null ? "đang ngủ (chunk chưa load)"
                : ly.getMode() + ", máu " + (int) ly.getHealth() + "/" + (int) ly.getMaxHealth()
                + (ly.getHomePos() != null ? ", nhà " + ly.getHomePos().toShortString() : ", nhà = giường của bạn");
        c.getSource().sendSuccess(() -> Component.literal("LyMeowz ở " + where + " – " + state), false);
        return 1;
    }

    private static net.minecraft.server.MinecraftServer server(ServerPlayer p) {
        return ((ServerLevel) p.level()).getServer();
    }

    private static int act(CommandContext<CommandSourceStack> c, BiConsumer<LyMeowzEntity, ServerPlayer> action) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        LyMeowzEntity ly = CompanionTracker.find(server(p), p.getUUID());
        if (ly == null) {
            if (CompanionStore.byOwner(p.getUUID()) != null) {
                CompanionTracker.recall(p.getUUID());
                c.getSource().sendFailure(Component.literal("LyMeowz đang ở chỗ chưa được load – đang gọi cô ấy dậy, thử lại sau vài giây (hoặc /lymeowz summon)."));
            } else {
                c.getSource().sendFailure(Component.literal("Bạn chưa có LyMeowz. Dùng /lymeowz summon"));
            }
            return 0;
        }
        action.accept(ly, p);
        return 1;
    }
}
