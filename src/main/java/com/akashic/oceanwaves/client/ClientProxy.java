package com.akashic.oceanwaves.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import com.akashic.oceanwaves.CommonProxy;
import com.akashic.oceanwaves.core.WaveSet;
import com.akashic.oceanwaves.net.MessageSeaState;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class ClientProxy extends CommonProxy {

    private Object lastWorld;

    @Override
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        MinecraftForge.EVENT_BUS.register(this);
        // harness de desenvolvimento: só com o arquivo na pasta do cliente, e só existe no jar de dev
        java.io.File flag = new java.io.File(Minecraft.getMinecraft().mcDataDir, "oceanwaves-harness");
        if (flag.isFile()) {
            try {
                String selection = new String(java.nio.file.Files.readAllBytes(flag.toPath()), "UTF-8");
                Class.forName("com.akashic.oceanwaves.dev.OceanHarness")
                    .getMethod("install", String.class)
                    .invoke(null, selection);
            } catch (ReflectiveOperationException | java.io.IOException ex) {
                com.akashic.oceanwaves.OceanWavesMod.LOG
                    .warn("Harness pedido mas ausente deste jar: {}", ex.toString());
            }
        }
    }

    @Override
    public void receive(MessageSeaState message) {
        ClientSea.receive(message);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            OceanRenderer.INSTANCE.onWorldChange();
        }
        ClientSea.tick(mc);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            OceanRenderer.INSTANCE.newFrame();
        }
    }

    /** Linhas no F3: o mar, o relógio e quanto custa. */
    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.gameSettings.showDebugInfo) {
            return;
        }
        if (ClientSea.rendererFailed()) {
            e.left.add("[Ondas] desligadas por erro: " + ClientSea.rendererFailure() + " (ver o log)");
            return;
        }
        if (!ClientSea.active()) {
            e.left.add("[Ondas] desligadas");
            return;
        }
        WaveSet w = ClientSea.waves();
        OceanRenderer r = OceanRenderer.INSTANCE;
        e.left.add(
            String.format(
                Locale.ROOT,
                "[Ondas] %s, Hs %.2f m, %d comp., L %.2f (%s)",
                ClientSea.fromServer() ? "do servidor" : "local",
                w.significantHeight,
                w.count,
                w.lipschitz,
                ClientSea.state()));
        e.left.add(
            String.format(
                Locale.ROOT,
                "[Ondas] %d chunks, %d quads, malha %.2f ms, envio %.2f ms; relógio %s, %.1f TPS, t = %.2f s",
                r.lastChunks,
                r.lastQuads,
                r.averageMeshMs(),
                r.averageDrawMs(),
                ClientSea.CLOCK.synced() ? "do servidor" : "local",
                ClientSea.CLOCK.serverTps(),
                ClientSea.time(0)));
    }
}
