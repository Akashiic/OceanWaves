package com.akashic.oceanwaves.mixin.mixins.client;

import net.minecraft.block.BlockLiquid;
import net.minecraft.world.IBlockAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.akashic.oceanwaves.client.ClientSea;

/**
 * A face de cima da água do mar aberto não é desenhada pelo terreno: o OceanWaves desenha a superfície com ondas no
 * lugar dela. {@code RenderBlocks.renderBlockLiquid} pergunta pela face de cima com as coordenadas do bloco de cima
 * ({@code y + 1}); o Angelica monta os chunks pelo mesmo caminho vanilla, com o {@code IBlockAccess} dele.
 */
@Mixin(BlockLiquid.class)
public abstract class BlockLiquidTopFaceMixin {

    @Inject(method = "shouldSideBeRendered", at = @At("HEAD"), cancellable = true)
    private void oceanwaves$hideSeaTop(IBlockAccess world, int x, int y, int z, int side,
        CallbackInfoReturnable<Boolean> cir) {
        if (side == 1 && ClientSea.hideTopFace(world, x, y - 1, z)) {
            cir.setReturnValue(false);
        }
    }
}
