package com.khanhly.lymeowz.client.mixin;

import com.khanhly.lymeowz.client.ClientCompat;
import com.khanhly.lymeowz.world.TabPresence;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dòng Tab của cô ấy: vẽ mặt cô ấy (skin trong mod) thay cho skin mặc định. */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
    @Shadow @Final private GameProfile profile;

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void lymeowz$skin(CallbackInfoReturnable<PlayerSkin> cir) {
        if (TabPresence.isOurs(profile.id())) cir.setReturnValue(ClientCompat.SKIN);
    }
}
