package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OceanFieldTest {

    private final WaveSet waves = Synthesis.build(SeaPresets.get("fresco").withChoppiness(1));
    private final OceanField field = new OceanField(waves);

    @Test
    void analyticDerivativesMatchFiniteDifferences() {
        WaveSample s = new WaveSample(), a = new WaveSample(), b = new WaveSample();
        double h = 1e-4;
        for (int i = 0; i < 50; i++) {
            double x = 13.7 * i - 200, z = 7.3 * i + 50, t = 1.9 * i;
            field.lagrangian(x, z, t, waves.count, s);
            field.lagrangian(x + h, z, t, waves.count, a);
            field.lagrangian(x - h, z, t, waves.count, b);
            assertEquals((a.height - b.height) / (2 * h), s.hx, 1e-6);
            assertEquals((a.dx - b.dx) / (2 * h), s.dxx, 1e-6);
            assertEquals((a.dz - b.dz) / (2 * h), s.dxz, 1e-6);
            field.lagrangian(x, z + h, t, waves.count, a);
            field.lagrangian(x, z - h, t, waves.count, b);
            assertEquals((a.height - b.height) / (2 * h), s.hz, 1e-6);
            assertEquals((a.dz - b.dz) / (2 * h), s.dzz, 1e-6);
            // velocidade: derivada no tempo com x₀ fixo
            field.lagrangian(x, z, t + h, waves.count, a);
            field.lagrangian(x, z, t - h, waves.count, b);
            assertEquals((a.dx - b.dx) / (2 * h), s.vx, 1e-6);
            assertEquals((a.height - b.height) / (2 * h), s.vy, 1e-6);
            assertEquals((a.dz - b.dz) / (2 * h), s.vz, 1e-6);
        }
    }

    @Test
    void inversionFindsTheParticleThatLandsOnThePoint() {
        WaveSample s = new WaveSample();
        int worst = 0;
        for (int i = 0; i < 5000; i++) {
            double x = 3.17 * i - 7000, z = -1.31 * i + 400, t = 0.71 * i;
            field.eulerian(x, z, t, waves.count, s, false);
            assertTrue(s.residual < 1e-6, "resíduo " + s.residual);
            assertEquals(x, s.x0 + s.dx, 1e-6);
            assertEquals(z, s.z0 + s.dz, 1e-6);
            worst = Math.max(worst, s.iterations);
        }
        assertTrue(worst <= 5, "Newton levou " + worst + " iterações");
    }

    @Test
    void warmStartTakesOneIterationForAHullThatBarelyMoved() {
        WaveSample s = new WaveSample();
        field.eulerian(10, 20, 100, waves.count, s, false);
        int total = 0;
        for (int tick = 1; tick <= 100; tick++) {
            field.eulerian(10 + tick * 0.05, 20, 100 + tick * 0.05, waves.count, s, true);
            assertTrue(s.residual < 1e-6);
            total += s.iterations;
        }
        assertTrue(total <= 200, "média de " + total / 100.0 + " iterações por tick");
    }

    /** Lipschitz abaixo de 1: o jacobiano nunca chega a zero, a superfície nunca se dobra. */
    @Test
    void surfaceNeverFolds() {
        WaveSet steep = Synthesis.build(SeaPresets.get("tempestade").withChoppiness(1).withScale(2.5));
        assertTrue(steep.lipschitz <= 0.85 + 1e-12);
        OceanField f = new OceanField(steep);
        WaveSample s = new WaveSample();
        double[] n = new double[3];
        double minJ = Double.MAX_VALUE;
        for (int i = 0; i < 20000; i++) {
            f.lagrangian(i * 0.731, i * 1.113, i * 0.0917, steep.count, s);
            minJ = Math.min(minJ, s.jacobian());
            s.normal(n);
            assertTrue(n[1] > 0, "normal virada para baixo");
            assertEquals(1, n[0] * n[0] + n[1] * n[1] + n[2] * n[2], 1e-12);
        }
        double bound = (1 - steep.lipschitz) * (1 - steep.lipschitz);
        assertTrue(minJ >= bound - 1e-9, "J mínimo " + minJ + " abaixo da cota " + bound);
    }

    /** A correção do nível médio: a média euleriana da altura é o nível de repouso (a água não some nas ondas). */
    @Test
    void eulerianMeanLevelIsTheRestLevel() {
        WaveSet w = Synthesis.build(SeaState.wind(12, 0, 200_000).withLengthScale(0.5).withChoppiness(1)
            .withComponents(24).withWavelengths(4, 200));
        OceanField f = new OceanField(w);
        WaveSample s = new WaveSample();
        double sum = 0, sumRaw = 0;
        int n = 0;
        // média espacial numa área grande (muitos comprimentos de onda) e em vários instantes
        for (int t = 0; t < 6; t++) {
            for (int i = 0; i < 400; i++) {
                for (int j = 0; j < 400; j++) {
                    double h = f.eulerian(i * 3.1 + 0.3, j * 3.1 + 0.7, t * 3.3, w.count, s, false);
                    sum += h;
                    sumRaw += h - w.meanLevel;
                    n++;
                }
            }
        }
        double mean = sum / n, raw = sumRaw / n;
        assertEquals(0, mean, 0.15 * w.meanLevel, "média " + mean + " (correção " + w.meanLevel + ")");
        assertEquals(-w.meanLevel, raw, 0.15 * w.meanLevel);
    }

    /**
     * Cristas mais altas e agudas que os cavados (a assimetria de Stokes de 2ª ordem): numa onda trocoidal única, a
     * superfície euleriana é η ≈ a·cos θ + (Q·k·a²/2)·cos 2θ em torno do nível médio, então a crista fica
     * Q·k·a²/2 mais alta e o cavado o mesmo tanto mais raso.
     */
    @Test
    void crestsAreSharperThanTroughs() {
        double a = 1.0, k = 0.3, q = 1.0;
        WaveSet one = new WaveSet(new double[] { a }, new double[] { k }, new double[] { 1 }, new double[] { 0 },
            new double[] { Math.sqrt(Dispersion.G * k) }, new double[] { 0 }, new double[] { q });
        OceanField f = new OceanField(one);
        WaveSample s = new WaveSample();
        double lambda = 2 * Math.PI / k, max = -9, min = 9, mean = 0;
        int n = 4000;
        for (int i = 0; i < n; i++) {
            double h = f.eulerian(i * lambda / n, 0, 0, 1, s, false);
            max = Math.max(max, h);
            min = Math.min(min, h);
            mean += h / n;
        }
        assertEquals(0, mean, 1e-6, "o nível médio euleriano é o de repouso");
        assertEquals(a + q * k * a * a / 2, max, 0.01);
        assertEquals(-(a - q * k * a * a / 2), min, 0.01);
        assertTrue(max > -min);
    }

    @Test
    void noAttenuationMeansFlatWaterAtRest() {
        OceanField f = field.withAttenuation((x, z) -> 0.0);
        WaveSample s = new WaveSample();
        double h = f.eulerian(5, 5, 33, waves.count, s, false);
        assertEquals(0, h, 0);
        assertEquals(5, s.x0, 0);
    }
}
