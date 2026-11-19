package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeightPatchTest {

    @Test
    void patchMatchesExactQueries() {
        WaveSet all = Synthesis.build(SeaPresets.get("forte").withChoppiness(1));
        WaveSet hull = all.prefixSet(all.prefix(4.0));
        HeightPatch p = new HeightPatch(hull);
        OceanField exact = new OceanField(hull);
        WaveSample s = new WaveSample();
        double t = 4321.0;
        p.update(1000, 2000, 1048, 2016, 0.5, t, null);
        double worst = 0, sumSq = 0;
        int n = 0;
        for (double x = 1000; x <= 1048; x += 0.77) {
            for (double z = 2000; z <= 2016; z += 0.63) {
                double h = p.height(x, z, null);
                double e = exact.eulerian(x, z, t, hull.count, s, false);
                worst = Math.max(worst, Math.abs(h - e));
                sumSq += (h - e) * (h - e);
                n++;
            }
        }
        double rms = Math.sqrt(sumSq / n);
        assertTrue(rms < 0.02, "erro rms " + rms);
        assertTrue(worst < 0.06, "pior erro " + worst);
    }

    @Test
    void outsideIsNaN() {
        WaveSet w = Synthesis.build(SeaPresets.get("brisa"));
        HeightPatch p = new HeightPatch(w);
        p.update(0, 0, 10, 10, 0.5, 0, null);
        assertTrue(Double.isNaN(p.height(500, 500, null)));
        assertEquals(p.height(5, 5, null), p.height(5, 5, new double[] { Double.NaN, Double.NaN }), 1e-12);
    }
}
