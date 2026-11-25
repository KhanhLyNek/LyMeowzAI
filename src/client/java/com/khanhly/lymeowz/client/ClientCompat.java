package com.khanhly.lymeowz.client;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Vẽ LyMeowz như một người chơi tay mảnh, có lớp áo ngoài và giáp.
 * <p>
 * Điền skin vào {@code AvatarRenderState.skin}: các mod vẽ người chơi (3D Skin Layers, EMF/ETF...) đọc trường này
 * chứ không đọc texture của renderer – thiếu nó thì cô ấy hiện ra bằng skin mặc định.
 */
public final class ClientCompat {
    private ClientCompat() {}

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(LyMeowzMod.MOD_ID, "textures/entity/lymeowz.png");
    public static final PlayerSkin SKIN = new PlayerSkin(
            new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath(LyMeowzMod.MOD_ID, "entity/lymeowz"), TEXTURE),
            null, null, PlayerModelType.SLIM, true);

    public static void registerRenderer() {
        EntityRendererRegistry.register(LyMeowzMod.LYMEOWZ, Renderer::new);
    }

    static final class Renderer extends HumanoidMobRenderer<LyMeowzEntity, AvatarRenderState, PlayerModel> {
        Renderer(EntityRendererProvider.Context ctx) {
            super(ctx, new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5f);
            ArmorModelSet<HumanoidModel<AvatarRenderState>> armor =
                    ArmorModelSet.bake(ModelLayers.PLAYER_SLIM_ARMOR, ctx.getModelSet(), HumanoidModel::new);
            this.addLayer(new HumanoidArmorLayer<>(this, armor, ctx.getEquipmentRenderer()));
        }

        @Override
        public AvatarRenderState createRenderState() {
            return new AvatarRenderState();
        }

        @Override
        public void extractRenderState(LyMeowzEntity entity, AvatarRenderState state, float partialTick) {
            super.extractRenderState(entity, state, partialTick);
            state.skin = SKIN;
            state.showHat = true;
            state.showJacket = true;
            state.showLeftPants = true;
            state.showRightPants = true;
            state.showLeftSleeve = true;
            state.showRightSleeve = true;
            state.showCape = false;
        }

        @Override
        public Identifier getTextureLocation(AvatarRenderState state) {
            return TEXTURE;
        }
    }
}
