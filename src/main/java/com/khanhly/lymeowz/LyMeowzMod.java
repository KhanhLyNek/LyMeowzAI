package com.khanhly.lymeowz;

import com.khanhly.lymeowz.chat.ChatListener;
import com.khanhly.lymeowz.command.LyCommands;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.world.CompanionTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LyMeowzMod implements ModInitializer {
    public static final String MOD_ID = "lymeowz";
    public static final Logger LOGGER = LoggerFactory.getLogger("LyMeowz");

    public static final EntityType<LyMeowzEntity> LYMEOWZ = Compat.registerEntity(MOD_ID, "lymeowz", LyMeowzEntity::new);

    @Override
    public void onInitialize() {
        LyConfig.load();
        FabricDefaultAttributeRegistry.register(LYMEOWZ, LyMeowzEntity.createAttributes());
        CompanionTracker.init();
        ChatListener.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> LyCommands.register(dispatcher));
        LOGGER.info("LyMeowz đã thức dậy~ Mugyu~");
    }
}
