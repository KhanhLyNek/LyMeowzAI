package com.khanhly.lymeowz.chat;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.world.CompanionTracker;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Chuyển tin nhắn chat của người chơi tới LyMeowz của họ. */
public final class ChatListener {
    private ChatListener() {}

    public static void init() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.decoratedContent().getString();
            MinecraftServer server = ((ServerLevel) sender.level()).getServer();
            // Đợi 1 tick để câu trả lời hiện ra sau tin nhắn của người chơi.
            server.execute(() -> {
                LyMeowzEntity ly = CompanionTracker.find(server, sender.getUUID());
                if (ly != null) ly.brain.hear(sender, text);
            });
        });
    }
}
