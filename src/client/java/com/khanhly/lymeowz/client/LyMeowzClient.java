package com.khanhly.lymeowz.client;

import net.fabricmc.api.ClientModInitializer;

public class LyMeowzClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCompat.registerRenderer();
    }
}
