package com.khanhly.lymeowz.client;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

/** Vẽ LyMeowz bằng model người chơi tay mảnh (1.21.9+: AvatarRenderState). */
public final class ClientCompat {
    private ClientCompat() {}

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(LyMeowzMod.MOD_ID, "textures/entity/lymeowz.png");

    public static void registerRenderer() {
        EntityRendererRegistry.register(LyMeowzMod.LYMEOWZ, Renderer::new);
    }

    static final class Renderer extends HumanoidMobRenderer<LyMeowzEntity, AvatarRenderState, PlayerModel> {
        Renderer(EntityRendererProvider.Context ctx) {
            super(ctx, new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5f);
        }

        @Override
        public AvatarRenderState createRenderState() {
            return new AvatarRenderState();
        }

        @Override
        public void extractRenderState(LyMeowzEntity entity, AvatarRenderState state, float partialTick) {
            super.extractRenderState(entity, state, partialTick);
            // Hiện lớp ngoài của skin (tóc, tai mèo, áo khoác...).
            state.showHat = true;
            state.showJacket = true;
            state.showLeftPants = true;
            state.showRightPants = true;
            state.showLeftSleeve = true;
            state.showRightSleeve = true;
        }

        @Override
        public Identifier getTextureLocation(AvatarRenderState state) {
            return TEXTURE;
        }
    }
}
