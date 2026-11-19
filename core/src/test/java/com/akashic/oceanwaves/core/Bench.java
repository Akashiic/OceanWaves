package com.akashic.oceanwaves.core;

import java.util.Locale;

/** Medidas de custo: ./gradlew :core:bench. Não é teste: só imprime. */
public final class Bench {

    public static void main(String[] args) {
        WaveSet w = Synthesis.build(SeaPresets.get("fresco"));
        GridEvaluator g = new GridEvaluator(w);
        GridOutput out = new GridOutput();
        OceanField f = new OceanField(w);
        WaveSample s = new WaveSample();
        SpectralLod lod = SpectralLod.at(8, 70, 8, 63);
        double sink = 0;
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            int reps = 2000;
            for (int r = 0; r < reps; r++) {
                g.evaluate(r * 16, 0, 1, 17, r * 0.05, null, null, out);
                sink += out.y[100];
            }
            long t1 = System.nanoTime();
            double gridUs = (t1 - t0) / 1e3 / reps;
            for (int r = 0; r < reps; r++) {
                g.evaluate(r * 16, 0, 0.5, 33, r * 0.05, null, null, out);
                sink += out.y[100];
            }
            long t2 = System.nanoTime();
            double grid33Us = (t2 - t1) / 1e3 / reps;
            int q = 200_000;
            for (int i = 0; i < q; i++) {
                sink += f.eulerian(i * 0.7, i * 0.3, i * 0.01, w.count, s, false);
            }
            long t3 = System.nanoTime();
            double coldUs = (t3 - t2) / 1e3 / q;
            f.eulerian(0, 0, 0, w.count, s, false);
            for (int i = 0; i < q; i++) {
                sink += f.eulerian(i * 0.001, 0, i * 0.0005, w.count, s, true);
            }
            long t4 = System.nanoTime();
            double warmUs = (t4 - t3) / 1e3 / q;
            // uma cena: raio de 12 chunks com o LOD espectral e a resolução escolhida por distância
            int chunks = 0;
            long verts = 0;
            g.beginFrame(3.3, -12, -12, 25, 25, 16);
            for (int cz = -12; cz <= 12; cz++) {
                for (int cx = -12; cx <= 12; cx++) {
                    if (cx * cx + cz * cz > 144) {
                        continue;
                    }
                    double sp = lod.spacingForBox(cx * 16, cz * 16, cx * 16 + 16, cz * 16 + 16);
                    double cell = sp <= 1 ? 1 : sp <= 2 ? 2 : sp <= 4 ? 4 : sp <= 8 ? 8 : 16;
                    int n = (int) Math.round(16 / cell) + 1;
                    g.evaluateFast(cx * 16, cz * 16, cell, n, 3.3, lod, null, out);
                    chunks++;
                    verts += (long) n * n;
                }
            }
            long t5 = System.nanoTime();
            System.out.printf(Locale.ROOT,
                "%d componentes | grade 17x17: %.1f us | 33x33: %.1f us | consulta fria: %.2f us | quente: %.2f us | cena de %d chunks (%d vértices): %.2f ms%n",
                w.count, gridUs, grid33Us, coldUs, warmUs, chunks, verts, (t5 - t4) / 1e6);
        }
        System.out.println(sink == 42 ? "" : " ");
    }
}
