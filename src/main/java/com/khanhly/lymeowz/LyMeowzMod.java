package com.khanhly.lymeowz;

import com.khanhly.lymeowz.chat.ChatListener;
import com.khanhly.lymeowz.command.LyCommands;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.world.CompanionTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LyMeowzMod implements ModInitializer {
    public static final String MOD_ID = "lymeowz";
    public static final Logger LOGGER = LoggerFactory.getLogger("LyMeowz");

    public static final EntityType<LyMeowzEntity> LYMEOWZ = Registry.register(
            Registries.ENTITY_TYPE,
            id("lymeowz"),
            EntityType.Builder.create(LyMeowzEntity::new, SpawnGroup.MISC)
                    .dimensions(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    .maxTrackingRange(10)
                    .build("lymeowz"));

    public static final Item LYMEOWZ_SPAWN_EGG = Registry.register(
            Registries.ITEM,
            id("lymeowz_spawn_egg"),
            new SpawnEggItem(LYMEOWZ, 0xFFB7D5, 0x8A5CF6, new Item.Settings()));

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LyConfig.load();
        FabricDefaultAttributeRegistry.register(LYMEOWZ, LyMeowzEntity.createAttributes());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(e -> e.add(LYMEOWZ_SPAWN_EGG));

        CompanionTracker.init();
        ChatListener.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> LyCommands.register(dispatcher));

        LOGGER.info("LyMeowz đã thức dậy~ Mugyu~");
    }
}
