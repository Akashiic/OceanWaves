package com.akashic.oceanwaves.client;

import java.util.Arrays;

import com.akashic.oceanwaves.core.SeaRegion;

/**
 * Um "mundo" sintético de colunas de mar (terra e profundidade), para testar o cache de chunks e a costa sem Minecraft.
 * Cobre os chunks [-half, half) nos dois eixos.
 */
final class SyntheticSea {

    final int half;
    final int size;
    final boolean[] open;
    final float[] depth;

    SyntheticSea(int halfChunks, float seaDepth) {
        half = halfChunks;
        size = 2 * halfChunks * 16;
        open = new boolean[size * size];
        depth = new float[size * size];
        Arrays.fill(open, true);
        Arrays.fill(depth, seaDepth);
    }

    private int at(int wx, int wz) {
        return (wz + half * 16) * size + (wx + half * 16);
    }

    /** Uma ilha (terra) de lado {@code side} com o canto mínimo em (x, z). */
    void island(int x, int z, int side) {
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                open[at(x + i, z + j)] = false;
                depth[at(x + i, z + j)] = 0;
            }
        }
    }

    /** Fundo raso num retângulo. */
    void shoal(int x, int z, int w, int h, float d) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                depth[at(x + i, z + j)] = d;
            }
        }
    }

    /** Escreve o chunk (cx, cz) no anel, como a leitura do mundo faria. Não marca vizinhos. */
    OceanChunks.Column load(ColumnRing ring, int cx, int cz) {
        OceanChunks.Column c = ring.get(cx, cz);
        boolean any = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int w = at(cx * 16 + x, cz * 16 + z);
                c.open[z * 16 + x] = open[w];
                c.depth[z * 16 + x] = open[w] ? depth[w] : 0;
                any |= open[w];
            }
        }
        c.any = any;
        c.loaded = true;
        c.dirty = false;
        return c;
    }

    /** Todos os chunks de [-r, r] no anel, lidos (a costa de cada um por fazer). */
    ColumnRing ringOf(int radius) {
        ColumnRing ring = new ColumnRing();
        ring.ensure(radius);
        for (int cz = -radius; cz <= radius; cz++) {
            for (int cx = -radius; cx <= radius; cx++) {
                load(ring, cx, cz);
            }
        }
        return ring;
    }

    /** O que a leitura do mundo faz quando um chunk muda: relê e marca a costa dos 5 × 5. */
    void reload(ColumnRing ring, int cx, int cz) {
        load(ring, cx, cz);
        ring.touchShores(cx, cz, 2);
    }

    static SeaRegion everywhere() {
        return SeaRegion.EVERYWHERE;
    }
}
