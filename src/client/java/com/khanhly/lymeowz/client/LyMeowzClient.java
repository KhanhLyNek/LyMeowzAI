package com.khanhly.lymeowz.client;

import com.khanhly.lymeowz.LyMeowzMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class LyMeowzClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(LyMeowzMod.LYMEOWZ, LyMeowzRenderer::new);
    }
}
