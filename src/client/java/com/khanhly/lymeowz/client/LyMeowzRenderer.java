package com.khanhly.lymeowz.client;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/** Vẽ LyMeowz bằng model người chơi tay mảnh (kiểu Alex) với skin riêng. */
public class LyMeowzRenderer extends BipedEntityRenderer<LyMeowzEntity, PlayerEntityModel<LyMeowzEntity>> {
    private static final Identifier TEXTURE = LyMeowzMod.id("textures/entity/lymeowz.png");

    public LyMeowzRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_SLIM), true), 0.5f);
    }

    @Override
    public Identifier getTexture(LyMeowzEntity entity) {
        return TEXTURE;
    }
}
