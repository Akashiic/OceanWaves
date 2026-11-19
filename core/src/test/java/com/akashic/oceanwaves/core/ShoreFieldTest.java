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

    /**
     * A costa não pode dobrar a superfície: num mar de tempestade com faixa de costa estreita, ilhas de um bloco e
     * degraus de profundidade, o jacobiano continua acima de zero em toda a região.
     */
    @Test
    void shoreNeverFoldsTheSurface() {
        WaveSet waves = Synthesis.build(SeaPresets.get("tempestade").withChoppiness(1));
        int w = 96, h = 96;
        boolean[] open = new boolean[w * h];
        float[] depth = new float[w * h];
        SplitMix64 r = new SplitMix64(77);
        for (int i = 0; i < open.length; i++) {
            open[i] = r.nextDouble() > 0.01; // ilhas de um bloco espalhadas
            depth[i] = r.nextDouble() < 0.2 ? 1 : 25; // degraus de profundidade
        }
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < 20; x++) {
                open[z * w + x] = false; // um continente a oeste
            }
        }
        double l = waves.lipschitz, m = waves.maxDisplacement;
        ShoreField shore = ShoreField.build(0, 0, w, h, open, depth, 3, 0.78, waves.significantHeight, l, m);
        double[] grad = new double[2];
        double worst = 0;
        for (double z = 0.5; z < h; z += 0.37) {
            for (double x = 0.5; x < w; x += 0.41) {
                shore.gradient(x, z, grad);
                // a cota do pior caso: A·L + M·|∇A| < 1
                worst = Math.max(worst, shore.at(x, z) * l + m * Math.hypot(grad[0], grad[1]));
            }
        }
        assertTrue(worst < 1, "A·L + M·|∇A| chegou a " + worst);
        OceanField f = new OceanField(waves, shore);
        WaveSample s = new WaveSample();
        double minJ = Double.MAX_VALUE;
        for (int t = 0; t < 4; t++) {
            for (double z = 1; z < h - 1; z += 0.5) {
                for (double x = 1; x < w - 1; x += 0.5) {
                    f.lagrangian(x, z, t * 2.7, waves.count, s);
                    minJ = Math.min(minJ, s.jacobian());
                }
            }
        }
        assertTrue(minJ > 0, "a superfície dobrou na costa: J mínimo " + minJ);
    }

    /** O limite não come as ondas do mar aberto: longe da costa a atenuação volta a 1. */
    @Test
    void clampKeepsOpenSeaOpen() {
        WaveSet waves = Synthesis.build(SeaPresets.get("tempestade").withChoppiness(1));
        int w = 160, h = 8;
        boolean[] open = new boolean[w * h];
        float[] depth = new float[w * h];
        Arrays.fill(open, true);
        Arrays.fill(depth, 30);
        for (int z = 0; z < h; z++) {
            open[z * w] = false;
        }
        ShoreField a = ShoreField.build(0, 0, w, h, open, depth, 10, 0.78, waves.significantHeight, waves.lipschitz,
            waves.maxDisplacement);
        assertEquals(1, a.at(150, 4), 1e-6);
        // até onde a costa ainda segura as ondas, com esse mar: dezenas de blocos, não centenas
        double x = 1;
        while (a.at(x, 4) < 0.9) {
            x += 0.5;
        }
        assertTrue(x < 60, "a onda só chega a 90% em x = " + x);
    }
}
