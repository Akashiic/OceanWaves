package com.akashic.oceanwaves.api;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.akashic.oceanwaves.OceanConfig;
import com.akashic.oceanwaves.core.OceanField;
import com.akashic.oceanwaves.core.SeaState;
import com.akashic.oceanwaves.core.ShoreField;
import com.akashic.oceanwaves.core.WaveSample;
import com.akashic.oceanwaves.core.WaveSet;
import com.akashic.oceanwaves.server.ServerSea;
import com.akashic.oceanwaves.world.OceanColumns;

/**
 * A API do OceanWaves para outros mods e para plugins Bukkit no Crucible (o jar do mod leva o núcleo
 * {@code com.akashic.oceanwaves.core}; um plugin compila contra ele como {@code compileOnly}). Só tipos do Java e do
 * núcleo nas assinaturas: nenhum tipo do Minecraft ou do Bukkit. Chamar na thread principal do servidor.
 *
 * <p>
 * O mar da física é o mesmo que os clientes desenham: mesmo estado, mesmas componentes (bit a bit) e o mesmo
 * tempo, o total do mundo em segundos ({@link #time}).
 */
public final class OceanWaves {

    public static final int API_VERSION = 1;

    private OceanWaves() {}

    /** O estado do mar da dimensão, ou null se ela não tem ondas. */
    public static SeaState state(int dimension) {
        return ServerSea.INSTANCE.state(dimension);
    }

    /** As componentes do mar da dimensão (as mesmas que os clientes desenham), ou null. */
    public static WaveSet waves(int dimension) {
        return ServerSea.INSTANCE.waves(dimension);
    }

    /** Troca o mar da dimensão (null: sem ondas) e avisa os clientes. */
    public static void setState(int dimension, SeaState state) {
        ServerSea.INSTANCE.set(dimension, state);
    }

    /** O tempo do mar: o tempo total do mundo da dimensão em segundos (ticks / 20). NaN se a dimensão não existe. */
    public static double time(int dimension) {
        WorldServer w = DimensionManager.getWorld(dimension);
        return w == null ? Double.NaN : w.getTotalWorldTime() / 20.0;
    }

    /** O Y da superfície em repouso: o topo da água da fonte no nível do mar. */
    public static double restLevel() {
        return OceanConfig.seaLevel - 1 + OceanColumns.SOURCE_TOP;
    }

    /**
     * A atenuação da costa numa região, das colunas reais do mundo (a mesma regra e a mesma conta dos clientes).
     * Colunas de chunks não carregados contam como mar aberto. Para um casco: a região que ele cobre, com uma folga
     * de {@code OceanConfig.shoreWidth}.
     */
    public static ShoreField shore(int dimension, int minX, int minZ, int maxX, int maxZ) {
        WorldServer w = DimensionManager.getWorld(dimension);
        WaveSet ws = waves(dimension);
        int width = maxX - minX + 1, height = maxZ - minZ + 1;
        boolean[] open = new boolean[width * height];
        float[] depth = new float[width * height];
        int y = OceanConfig.seaLevel - 1;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int wx = minX + x, wz = minZ + z, i = z * width + x;
                if (w == null || !w.getChunkProvider()
                    .chunkExists(wx >> 4, wz >> 4)) {
                    open[i] = true;
                    depth[i] = 64;
                    continue;
                }
                open[i] = OceanColumns.isOpenSea(w, wx, y, wz);
                depth[i] = open[i] ? OceanColumns.depth(w, wx, y, wz, 64) : 0;
            }
        }
        if (ws == null) {
            return ShoreField
                .build(minX, minZ, width, height, open, depth, OceanConfig.shoreWidth, OceanConfig.breakerIndex, 1);
        }
        return ShoreField.build(
            minX,
            minZ,
            width,
            height,
            open,
            depth,
            OceanConfig.shoreWidth,
            OceanConfig.breakerIndex,
            ws.significantHeight,
            ws.lipschitz,
            ws.maxDisplacement);
    }

    /**
     * O Y da superfície no ponto (x, z) do mundo, agora, em mar aberto (sem a costa). Para muitas consultas por tick
     * use {@code HeightPatch} ou {@code OceanField} direto, com um {@link WaveSample} reaproveitado.
     */
    public static double surfaceY(int dimension, double x, double z) {
        WaveSet ws = waves(dimension);
        if (ws == null) {
            return restLevel();
        }
        return restLevel() + new OceanField(ws).eulerian(x, z, time(dimension), ws.count, new WaveSample(), false);
    }
}
