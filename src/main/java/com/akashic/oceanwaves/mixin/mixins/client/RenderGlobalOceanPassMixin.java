package com.akashic.oceanwaves.mixin.mixins.client;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.akashic.oceanwaves.client.OceanRenderer;

/**
 * O mar é desenhado no fim do passe translúcido do terreno (passe 1), onde a água vanilla seria: antes das entidades
 * translúcidas, das partículas e da chuva. Onde quer que o passe 1 rode (o vanilla, ou o Angelica quando adia o
 * translúcido com shaders), o mar vem logo depois.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalOceanPassMixin {

    @Inject(method = "sortAndRender", at = @At("RETURN"))
    private void oceanwaves$drawSea(EntityLivingBase viewer, int pass, double partialTicks,
        CallbackInfoReturnable<Integer> cir) {
        if (pass == 1) {
            OceanRenderer.INSTANCE.render((float) partialTicks);
        }
    }
}
