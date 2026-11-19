package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SeaRegionTest {

    @Test
    void circleContainsByColumnCentre() {
        SeaRegion r = SeaRegion.circle(0, 0, 10);
        assertTrue(r.containsColumn(0, 0));
        assertTrue(r.containsColumn(9, 0));
        assertFalse(r.containsColumn(10, 0));
        assertFalse(r.containsColumn(8, 8));
        assertTrue(SeaRegion.EVERYWHERE.containsColumn(1_000_000, -5));
        assertTrue(r.touches(5, 5, 40, 40));
        assertFalse(r.touches(20, 20, 40, 40));
        assertEquals(r, SeaRegion.fromArray(r.toArray()));
        assertEquals(SeaRegion.EVERYWHERE, SeaRegion.fromArray(SeaRegion.EVERYWHERE.toArray()));
    }

    /**
     * A borda da região é uma costa: dentro, longe dela, as ondas são as do mar aberto; na borda e fora, água parada;
     * e a superfície não dobra na transição, num mar de tempestade.
     */
    @Test
    void regionEdgeBehavesLikeAShore() {
        WaveSet waves = Synthesis.build(SeaPresets.get("tempestade").withChoppiness(1));
        SeaRegion region = SeaRegion.circle(100, 100, 60);
        int size = 200;
        boolean[] open = new boolean[size * size];
        float[] depth = new float[size * size];
        Arrays.fill(depth, 30);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                open[z * size + x] = region.containsColumn(x, z);
            }
        }
        ShoreField a = ShoreField.build(0, 0, size, size, open, depth, 10, 0.78, waves.significantHeight,
            waves.lipschitz, waves.maxDisplacement);
        assertEquals(1, a.at(100, 100), 1e-6);
        assertEquals(0, a.at(100, 165), 0);
        assertEquals(0, a.at(10, 10), 0);
        OceanField f = new OceanField(waves, a);
        WaveSample s = new WaveSample();
        double minJ = 9;
        for (double z = 30; z < 170; z += 0.7) {
            for (double x = 30; x < 170; x += 0.7) {
                f.lagrangian(x, z, 12.3, waves.count, s);
                minJ = Math.min(minJ, s.jacobian());
            }
        }
        assertTrue(minJ > 0, "J mínimo " + minJ);
        // fora da região a superfície é a água parada
        assertEquals(0, f.eulerian(15, 15, 12.3, waves.count, s, false), 0);
    }
}
