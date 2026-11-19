package com.akashic.oceanwaves.mixin.mixins.client;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S03PacketTimeUpdate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.oceanwaves.client.ClientSea;

/**
 * O relógio do mar vive do tempo total do mundo que o servidor vanilla manda a cada 20 ticks
 * ({@code MinecraftServer.updateTimeLightAndEntities}): não precisa do mod no servidor.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientTimeMixin {

    @Inject(method = "handleTimeUpdate", at = @At("HEAD"))
    private void oceanwaves$observeTime(S03PacketTimeUpdate packet, CallbackInfo ci) {
        ClientSea.observeTime(packet.func_149366_c());
    }
}
