package com.akashic.oceanwaves.net;

import com.akashic.oceanwaves.OceanWavesMod;
import com.akashic.oceanwaves.core.SeaRegion;
import com.akashic.oceanwaves.core.SeaState;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * O mar de uma dimensão: o estado (os 17 números do {@link SeaState}), o nível do mar e os parâmetros da costa. Ou
 * "sem mar" nesta dimensão. Versão 1; campos novos vão no fim.
 */
public final class MessageSeaState implements IMessage {

    /** 2: a região (onde há ondas) no fim. Um cliente da versão 1 lê o começo e ignora o resto. */
    public static final int VERSION = 2;

    public int dimension;
    public SeaState state;
    public int seaLevel;
    public double shoreWidth;
    public double breakerIndex;
    public SeaRegion region = SeaRegion.EVERYWHERE;

    public MessageSeaState() {}

    public MessageSeaState(int dimension, SeaState state, int seaLevel, double shoreWidth, double breakerIndex) {
        this.dimension = dimension;
        this.state = state;
        this.seaLevel = seaLevel;
        this.shoreWidth = shoreWidth;
        this.breakerIndex = breakerIndex;
    }

    public MessageSeaState(int dimension, SeaState state, int seaLevel, double shoreWidth, double breakerIndex,
        SeaRegion region) {
        this(dimension, state, seaLevel, shoreWidth, breakerIndex);
        this.region = region == null ? SeaRegion.EVERYWHERE : region;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(VERSION);
        buf.writeInt(dimension);
        buf.writeBoolean(state != null);
        if (state != null) {
            double[] v = state.toArray();
            buf.writeByte(v.length);
            for (double d : v) {
                buf.writeDouble(d);
            }
        }
        buf.writeInt(seaLevel);
        buf.writeDouble(shoreWidth);
        buf.writeDouble(breakerIndex);
        for (double d : region.toArray()) {
            buf.writeDouble(d);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int version = buf.readUnsignedByte();
        dimension = buf.readInt();
        if (buf.readBoolean()) {
            double[] v = new double[buf.readUnsignedByte()];
            for (int i = 0; i < v.length; i++) {
                v[i] = buf.readDouble();
            }
            try {
                state = SeaState.fromArray(v);
            } catch (IllegalArgumentException e) {
                OceanWavesMod.LOG.warn("Estado do mar recusado (versão {} do servidor): {}", version, e.getMessage());
                state = null;
            }
        }
        seaLevel = buf.readInt();
        shoreWidth = buf.readDouble();
        breakerIndex = buf.readDouble();
        if (version >= 2 && buf.readableBytes() >= 32) {
            double[] r = new double[4];
            for (int i = 0; i < 4; i++) {
                r[i] = buf.readDouble();
            }
            region = SeaRegion.fromArray(r);
        } else {
            region = SeaRegion.EVERYWHERE;
        }
    }

    public static final class Handler implements IMessageHandler<MessageSeaState, IMessage> {

        @Override
        public IMessage onMessage(MessageSeaState message, MessageContext ctx) {
            // chega na thread da rede: o cliente aplica no próximo tick
            OceanWavesMod.proxy.receive(message);
            return null;
        }
    }
}
