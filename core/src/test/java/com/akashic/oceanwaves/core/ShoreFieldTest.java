package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ShoreFieldTest {

    @Test
    void distanceTransformIsExact() {
        int w = 23, h = 17;
        float[] f = new float[w * h];
        boolean[] seed = new boolean[w * h];
        SplitMix64 r = new SplitMix64(4);
        for (int i = 0; i < f.length; i++) {
            seed[i] = r.nextDouble() < 0.04;
            f[i] = seed[i] ? 0 : 1e20f;
        }
        ShoreField.edt(f, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < seed.length; i++) {
                    if (seed[i]) {
                        int dx = i % w - x, dy = i / w - y;
                        best = Math.min(best, dx * dx + dy * dy);
                    }
                }
                assertEquals(best, f[y * w + x], 1e-3);
            }
        }
    }

    @Test
    void wavesDieAtTheShoreAndLiveOffshore() {
        int w = 64, h = 8;
        boolean[] open = new boolean[w * h];
        float[] depth = new float[w * h];
        Arrays.fill(open, true);
        Arrays.fill(depth, 20);
        for (int z = 0; z < h; z++) {
            open[z * w] = false; // uma faixa de terra em x = 0
        }
        ShoreField s = ShoreField.build(100, 200, w, h, open, depth, 10, 0.78, 2);
        // toda a aresta da coluna de terra (x de 100 a 101) é zero, e a costa leste dela também
        for (double z = 200; z <= 208; z += 0.5) {
            assertEquals(0, s.at(101, z), 0);
            assertEquals(0, s.at(100.5, z), 0);
        }
        assertEquals(1, s.at(140, 204), 1e-6);
        // cresce suavemente entre as duas
        double prev = 0;
        for (double x = 101; x < 112; x += 0.25) {
            double a = s.at(x, 204);
            assertTrue(a >= prev - 1e-9);
            prev = a;
        }
    }

    @Test
    void shallowWaterLimitsTheWaves() {
        int w = 40, h = 40;
        boolean[] open = new boolean[w * h];
        float[] depth = new float[w * h];
        Arrays.fill(open, true);
        Arrays.fill(depth, 1.0f);
        ShoreField s = ShoreField.build(0, 0, w, h, open, depth, 4, 0.78, 3);
        double a = s.at(20, 20);
        double u = 0.78 * 1.0 / 3;
        assertEquals(1 - (1 - u) * (1 - u), a, 1e-6);
    }
}
