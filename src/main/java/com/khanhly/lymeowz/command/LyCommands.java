package com.khanhly.lymeowz.command;

import com.khanhly.lymeowz.chat.Lines;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.world.CompanionState;
import com.khanhly.lymeowz.world.CompanionTracker;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.function.BiConsumer;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /lymeowz summon | follow | stay | home | sethome | info | say &lt;tin nhắn&gt; | dismiss | reload
 */
public final class LyCommands {
    private LyCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("lymeowz")
                .then(literal("summon").executes(LyCommands::summon))
                .then(literal("follow").executes(c -> act(c, (ly, p) -> { ly.setMode(LyMeowzEntity.Mode.FOLLOW); ly.say(Lines.FOLLOW); })))
                .then(literal("stay").executes(c -> act(c, (ly, p) -> { ly.setMode(LyMeowzEntity.Mode.STAY); ly.say(Lines.STAY); })))
                .then(literal("home").executes(c -> act(c, (ly, p) -> { ly.setMode(LyMeowzEntity.Mode.HOME); ly.say(Lines.GO_HOME); })))
                .then(literal("sethome").executes(c -> act(c, (ly, p) -> {
                    ly.setHome(p.getServerWorld(), p.getBlockPos());
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
                .then(literal("reload").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
                    LyConfig.load();
                    c.getSource().sendFeedback(() -> Text.literal("Đã tải lại config/lymeowz.json"), true);
                    return 1;
                })));
    }

    private static int summon(CommandContext<ServerCommandSource> c) {
        ServerPlayerEntity p = c.getSource().getPlayer();
        if (p == null) return 0;
        LyMeowzEntity ly = CompanionTracker.find(p.getServer(), p.getUuid());
        if (ly != null) {
            ly.setMode(LyMeowzEntity.Mode.FOLLOW);
            ly.teleportToOwner(p);
            ly.say(Lines.COME);
        } else if (CompanionState.get(p.getServer()).get(p.getUuid()) != null) {
            // Cô ấy đang ngủ trong chunk chưa load – tạo lại ngay cạnh bạn (bản cũ sẽ tự gỡ khi được load).
            ly = CompanionTracker.spawnFor(p);
            if (ly != null) ly.say(Lines.WELCOME_BACK);
        } else {
            ly = CompanionTracker.spawnFor(p);
            if (ly != null) { ly.say(Lines.FIRST_MEET); ly.hearts(); }
        }
        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> c) {
        ServerPlayerEntity p = c.getSource().getPlayer();
        if (p == null) return 0;
        CompanionState.Entry e = CompanionState.get(p.getServer()).get(p.getUuid());
        LyMeowzEntity ly = CompanionTracker.find(p.getServer(), p.getUuid());
        if (e == null) {
            c.getSource().sendFeedback(() -> Text.literal("Bạn chưa có LyMeowz. Dùng /lymeowz summon"), false);
            return 0;
        }
        String where = e.dimension.getValue() + " " + e.lastPos.toShortString();
        String state = ly == null ? "đang ngủ (chunk chưa load)"
                : ly.getMode() + ", máu " + (int) ly.getHealth() + "/" + (int) ly.getMaxHealth()
                + (ly.getHomePos() != null ? ", nhà " + ly.getHomePos().toShortString() : ", nhà = giường của bạn");
        c.getSource().sendFeedback(() -> Text.literal("LyMeowz ở " + where + " – " + state), false);
        return 1;
    }

    private static int act(CommandContext<ServerCommandSource> c, BiConsumer<LyMeowzEntity, ServerPlayerEntity> action) {
        ServerPlayerEntity p = c.getSource().getPlayer();
        if (p == null) return 0;
        LyMeowzEntity ly = CompanionTracker.find(p.getServer(), p.getUuid());
        if (ly == null) {
            if (CompanionState.get(p.getServer()).get(p.getUuid()) != null) {
                CompanionTracker.recall(p.getUuid());
                c.getSource().sendError(Text.literal("LyMeowz đang ở chỗ chưa được load – đang gọi cô ấy dậy, thử lại sau vài giây (hoặc /lymeowz summon)."));
            } else {
                c.getSource().sendError(Text.literal("Bạn chưa có LyMeowz. Dùng /lymeowz summon"));
            }
            return 0;
        }
        action.accept(ly, p);
        return 1;
    }
}
