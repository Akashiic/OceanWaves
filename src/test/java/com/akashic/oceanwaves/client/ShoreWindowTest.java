package com.akashic.oceanwaves.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashic.oceanwaves.core.Attenuation;
import com.akashic.oceanwaves.core.SeaPresets;
import com.akashic.oceanwaves.core.SeaRegion;
import com.akashic.oceanwaves.core.ShoreField;
import com.akashic.oceanwaves.core.SplitMix64;
import com.akashic.oceanwaves.core.Synthesis;
import com.akashic.oceanwaves.core.WaveSet;

/**
 * A janela da costa lida chunk a chunk dá o mesmo que a leitura coluna a coluna de antes, e pula o mar aberto fundo.
 */
class ShoreWindowTest {

    private static final double WIDTH = 10, GAMMA = 0.78;
    private final WaveSet waves = Synthesis.build(SeaPresets.get("moderado"));
    private final ShoreWindow window = new ShoreWindow();

    /** A janela como o código antigo a montava: uma coluna por vez, com a busca do chunk de cada uma. */
    private ShoreField reference(ColumnRing ring, OceanChunks.Column c, SeaRegion region) {
        int m = ShoreWindow.margin(WIDTH, waves);
        int size = 16 + 2 * m;
        boolean[] open = new boolean[size * size];
        float[] depth = new float[size * size];
        int ox = (c.cx << 4) - m, oz = (c.cz << 4) - m;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int wx = ox + x, wz = oz + z;
                OceanChunks.Column n = ring.peek(wx >> 4, wz >> 4);
                int i = z * size + x;
                if (n == null || !n.loaded) {
                    open[i] = region.containsColumn(wx, wz);
                    depth[i] = 64;
                } else {
                    int j = (wz & 15) * 16 + (wx & 15);
                    open[i] = n.open[j];
                    depth[i] = n.depth[j];
                }
            }
        }
        return ShoreField.build(
            ox,
            oz,
            size,
            size,
            open,
            depth,
            WIDTH,
            GAMMA,
            waves.significantHeight,
            waves.lipschitz,
            waves.maxDisplacement);
    }

    private static void assertEqualField(Attenuation a, ShoreField ref, OceanChunks.Column c) {
        for (int z = 0; z <= 16; z++) {
            for (int x = 0; x <= 16; x++) {
                double wx = c.cx * 16 + x, wz = c.cz * 16 + z;
                assertEquals(ref.at(wx, wz), a.at(wx, wz), 0, "chunk (" + c.cx + "," + c.cz + ") em " + x + "," + z);
            }
        }
    }

    @Test
    void chunkWiseWindowEqualsTheColumnByColumnOne() {
        SyntheticSea sea = new SyntheticSea(4, 30);
        SplitMix64 r = new SplitMix64(11);
        for (int i = 0; i < 40; i++) {
            sea.island(
                (int) (r.nextDouble() * 100) - 50,
                (int) (r.nextDouble() * 100) - 50,
                1 + (int) (r.nextDouble() * 5));
        }
        sea.shoal(-20, -20, 30, 12, 0.7f);
        ColumnRing ring = sea.ringOf(3);
        // um chunk que "não chegou" e um fora do anel: contam como mar (menos fora da região)
        ring.peek(2, 1).loaded = false;
        for (SeaRegion region : new SeaRegion[] { SeaRegion.EVERYWHERE, SeaRegion.circle(0, 0, 40) }) {
            for (int cz = -2; cz <= 2; cz++) {
                for (int cx = -2; cx <= 2; cx++) {
                    OceanChunks.Column c = ring.peek(cx, cz);
                    Attenuation a = window.build(ring, c, waves, WIDTH, GAMMA, region);
                    ShoreField ref = reference(ring, c, region);
                    if (a == Attenuation.OPEN_SEA) {
                        assertTrue(ref.uniform(), "a janela pulada tem de dar 1 em tudo na conta completa");
                    } else {
                        assertEqualField(a, ref, c);
                    }
                }
            }
        }
    }

    @Test
    void openDeepSeaWindowSkipsTheComputationAndIsTheSentinel() {
        SyntheticSea sea = new SyntheticSea(3, 40);
        ColumnRing ring = sea.ringOf(2);
        OceanChunks.Column c = ring.peek(0, 0);
        assertSame(Attenuation.OPEN_SEA, window.build(ring, c, waves, WIDTH, GAMMA, SeaRegion.EVERYWHERE));
        // o mesmo no canto do anel, onde a janela tem vizinhos que não chegaram (contam como mar)
        assertSame(
            Attenuation.OPEN_SEA,
            window.build(ring, ring.peek(2, 2), waves, WIDTH, GAMMA, SeaRegion.EVERYWHERE));
    }

    @Test
    void shallowOrCoastalWindowIsComputedAndNeverNull() {
        SyntheticSea sea = new SyntheticSea(3, 40);
        sea.shoal(0, 0, 16, 16, 0.5f);
        ColumnRing ring = sea.ringOf(2);
        Attenuation shallow = window.build(ring, ring.peek(0, 0), waves, WIDTH, GAMMA, SeaRegion.EVERYWHERE);
        assertNotNull(shallow);
        assertNotSame(Attenuation.OPEN_SEA, shallow);
        assertTrue(shallow.at(8, 8) < 1, "fundo raso segura a onda");
        // fora da região, a borda é costa: nenhuma janela de mar aberto
        Attenuation edge = window.build(ring, ring.peek(0, 0), waves, WIDTH, GAMMA, SeaRegion.circle(0, 0, 12));
        assertNotSame(Attenuation.OPEN_SEA, edge);
    }

    @Test
    void windowAtTheDepthLimitMatchesTheFullComputation() {
        // a profundidade mínima em que o mar aberto não é mais seguro pela quebra: dos dois lados do limite, a janela
        // pulada e a conta completa concordam
        double limit = Math.max(0.01, waves.significantHeight) / GAMMA;
        for (double d : new double[] { limit * 0.999, limit, limit * 1.001, limit * 1.5 }) {
            SyntheticSea sea = new SyntheticSea(3, (float) d);
            ColumnRing ring = sea.ringOf(2);
            OceanChunks.Column c = ring.peek(0, 0);
            Attenuation a = window.build(ring, c, waves, WIDTH, GAMMA, SeaRegion.EVERYWHERE);
            ShoreField ref = reference(ring, c, SeaRegion.EVERYWHERE);
            if (a == Attenuation.OPEN_SEA) {
                assertTrue(ref.uniform(), "pulou uma janela que a conta completa não dá 1: fundo " + d);
            } else {
                assertEqualField(a, ref, c);
            }
        }
    }
}
