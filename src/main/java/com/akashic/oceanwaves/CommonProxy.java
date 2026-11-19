package com.akashic.oceanwaves;

import com.akashic.oceanwaves.net.MessageSeaState;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {}

    public void init(FMLInitializationEvent event) {}

    /** Um estado de mar vindo do servidor (só o cliente faz algo). */
    public void receive(MessageSeaState message) {}
}
