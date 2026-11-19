package com.akashic.oceanwaves;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashic.oceanwaves.net.OceanNet;
import com.akashic.oceanwaves.server.OceanCommand;
import com.akashic.oceanwaves.server.ServerSea;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

/**
 * OceanWaves: ondas oceânicas para o 1.7.10.
 *
 * <p>
 * O mar é uma função determinística de um estado pequeno ({@code SeaState}) e do tempo total do mundo. O servidor
 * decide o estado e o manda aos clientes; o tempo os clientes já recebem do vanilla (o pacote de tempo, a cada
 * segundo). Cliente sem o mod no servidor: desenha o mar da própria configuração, com o mesmo relógio (as ondas
 * continuam iguais entre jogadores que tenham a mesma configuração). Servidor sem o mod no cliente: nada muda para
 * esse jogador ({@code acceptableRemoteVersions = "*"}).
 */
@Mod(modid = OceanWavesMod.MODID, name = OceanWavesMod.NAME, version = Tags.VERSION, acceptableRemoteVersions = "*")
public final class OceanWavesMod {

    public static final String MODID = "oceanwaves";
    public static final String NAME = "OceanWaves";
    public static final Logger LOG = LogManager.getLogger(NAME);

    @SidedProxy(
        clientSide = "com.akashic.oceanwaves.client.ClientProxy",
        serverSide = "com.akashic.oceanwaves.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        OceanConfig.load(event.getSuggestedConfigurationFile());
        OceanNet.init();
        FMLCommonHandler.instance()
            .bus()
            .register(ServerSea.INSTANCE);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(ServerSea.INSTANCE);
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        ServerSea.INSTANCE.start();
        event.registerServerCommand(new OceanCommand());
    }

    @Mod.EventHandler
    public void serverStarted(cpw.mods.fml.common.event.FMLServerStartedEvent event) {
        ServerSea.INSTANCE.started();
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        ServerSea.INSTANCE.stop();
    }
}
