package com.akashic.oceanwaves.mixin.mixins.client;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** O quadro atual da animação de um sprite (para copiar a água parada para a textura com repetição). */
@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteAccessor {

    @Accessor("frameCounter")
    int getOceanwavesFrameCounter();
}
