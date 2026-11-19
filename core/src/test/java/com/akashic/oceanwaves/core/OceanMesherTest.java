package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OceanMesherTest {

    private final WaveSet waves = Synthesis.build(SeaPresets.get("moderado"));

    /** A área coberta pelos quads (em repouso) é exatamente a das colunas de mar, em qualquer espaçamento. */
    @Test
    void coversExactlyTheOpenColumns() {
        boolean[] open = new boolean[256];
        SplitMix64 r = new SplitMix64(9);
        int columns = 0;
        for (int i = 0; i < 256; i++) {
            open[i] = r.nextDouble() < 0.7;
            columns += open[i] ? 1 : 0;
        }
        OceanMesher m = new OceanMesher(waves);
        for (double s : new double[] { 0.5, 1, 2, 4, 8, 16 }) {
            FloatBuffer buf = new FloatBuffer();
            m.mesh(3, -2, s, open, 10, null, null, buf);
            double area = 0;
            boolean[] covered = new boolean[256];
            for (int k = 0; k < buf.size; k += 4 * OceanMesher.FLOATS) {
                float u0 = buf.data[k + 7], v0 = buf.data[k + 8];
                float u1 = buf.data[k + 2 * OceanMesher.FLOATS + 7], v1 = buf.data[k + 2 * OceanMesher.FLOATS + 8];
                area += (u1 - u0) * (v1 - v0);
                int col = (int) Math.floor((v0 + v1) / 2) * 16 + (int) Math.floor((u0 + u1) / 2);
                if ((u1 - u0) <= 1) {
                    assertTrue(open[col], "quad fora do mar em " + col + " com espaçamento " + s);
                }
                covered[col] = true;
            }
            assertEquals(columns, area, 1e-3, "espaçamento " + s);
        }
    }

    @Test
    void fullChunkAtCoarseSpacingIsFewQuads() {
        boolean[] open = new boolean[256];
        Arrays.fill(open, true);
        OceanMesher m = new OceanMesher(waves);
        FloatBuffer buf = new FloatBuffer();
        assertEquals(4, m.mesh(0, 0, 8, open, 0, null, null, buf));
        buf.clear();
        assertEquals(1024, m.mesh(0, 0, 0.5, open, 0, null, null, buf));
        Arrays.fill(open, false);
        buf.clear();
        assertEquals(0, m.mesh(0, 0, 1, open, 0, null, null, buf));
    }

    @Test
    void spacingGrowsWithDistance() {
        SpectralLod lod = SpectralLod.at(0, 70, 0, 63);
        assertEquals(1, OceanMesher.spacingFor(lod, 0, 0, 1));
        assertTrue(OceanMesher.spacingFor(lod, 20, 0, 1) >= 4);
        assertEquals(16, OceanMesher.spacingFor(lod, 200, 0, 1));
    }
}
