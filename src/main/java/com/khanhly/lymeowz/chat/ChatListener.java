package com.khanhly.lymeowz.chat;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.world.CompanionTracker;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/** Chuyển tin nhắn chat của người chơi tới LyMeowz của họ. */
public final class ChatListener {
    private ChatListener() {}

    public static void init() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString();
            // Đợi 1 tick để câu trả lời hiện ra sau tin nhắn của người chơi.
            sender.getServer().execute(() -> {
                LyMeowzEntity ly = CompanionTracker.find(sender.getServer(), sender.getUuid());
                if (ly != null) ly.brain.hear(sender, text);
            });
        });
    }
}
