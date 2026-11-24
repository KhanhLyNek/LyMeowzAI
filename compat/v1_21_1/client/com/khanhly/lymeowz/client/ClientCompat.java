package com.khanhly.lymeowz.client;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Vẽ LyMeowz bằng model người chơi tay mảnh (1.21–1.21.1). */
public final class ClientCompat {
    private ClientCompat() {}

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(LyMeowzMod.MOD_ID, "textures/entity/lymeowz.png");

    public static void registerRenderer() {
        EntityRendererRegistry.register(LyMeowzMod.LYMEOWZ, Renderer::new);
    }

    static final class Renderer extends HumanoidMobRenderer<LyMeowzEntity, PlayerModel<LyMeowzEntity>> {
        Renderer(EntityRendererProvider.Context ctx) {
            super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5f);
        }

        @Override
        public ResourceLocation getTextureLocation(LyMeowzEntity entity) {
            return TEXTURE;
        }
    }
}
