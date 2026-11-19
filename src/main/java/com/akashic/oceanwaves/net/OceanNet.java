package com.akashic.oceanwaves.net;

import com.akashic.oceanwaves.OceanWavesMod;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/** O canal {@code oceanwaves}: por enquanto só o estado do mar, do servidor para o cliente. */
public final class OceanNet {

    public static SimpleNetworkWrapper channel;

    private OceanNet() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(OceanWavesMod.MODID);
        channel.registerMessage(MessageSeaState.Handler.class, MessageSeaState.class, 0, Side.CLIENT);
    }
}
