package com.akashic.oceanwaves.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;

import com.akashic.oceanwaves.OceanConfig;
import com.akashic.oceanwaves.OceanWavesMod;
import com.akashic.oceanwaves.core.SeaState;
import com.akashic.oceanwaves.core.Synthesis;
import com.akashic.oceanwaves.core.WaveSet;
import com.akashic.oceanwaves.net.MessageSeaState;
import com.akashic.oceanwaves.net.OceanNet;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * O mar de cada dimensão no servidor: a autoridade. Manda o estado a quem entra, troca de dimensão ou renasce, e a
 * todos da dimensão quando muda. O tempo não precisa de pacote: o vanilla já manda o tempo total do mundo a cada
 * segundo (S03PacketTimeUpdate), e é dele que o relógio do cliente vive.
 */
public final class ServerSea {

    public static final ServerSea INSTANCE = new ServerSea();

    private final Map<Integer, SeaState> states = new ConcurrentHashMap<>();
    private final Map<Integer, WaveSet> waves = new ConcurrentHashMap<>();

    private ServerSea() {}

    public void start() {
        states.clear();
        waves.clear();
        SeaState s = OceanConfig.seaState();
        for (int d : OceanConfig.dimensions) {
            states.put(d, s);
        }
        OceanWavesMod.LOG.info("Mar nas dimensões {}: {}", OceanConfig.dimensions, s);
    }

    public void stop() {
        states.clear();
        waves.clear();
    }

    public SeaState state(int dimension) {
        return states.get(dimension);
    }

    public WaveSet waves(int dimension) {
        SeaState s = states.get(dimension);
        if (s == null) {
            return null;
        }
        return waves.computeIfAbsent(dimension, d -> Synthesis.build(s));
    }

    /** Troca o mar de uma dimensão (null: sem ondas) e avisa quem está nela. */
    public void set(int dimension, SeaState state) {
        if (state == null) {
            states.remove(dimension);
        } else {
            states.put(dimension, state);
        }
        waves.remove(dimension);
        OceanNet.channel.sendToDimension(message(dimension), dimension);
    }

    private MessageSeaState message(int dimension) {
        return new MessageSeaState(
            dimension,
            states.get(dimension),
            OceanConfig.seaLevel,
            OceanConfig.shoreWidth,
            OceanConfig.breakerIndex);
    }

    private void send(EntityPlayerMP player) {
        OceanNet.channel.sendTo(message(player.dimension), player);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            send((EntityPlayerMP) e.player);
        }
    }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            send((EntityPlayerMP) e.player);
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            send((EntityPlayerMP) e.player);
        }
    }
}
