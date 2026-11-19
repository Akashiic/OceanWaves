package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GridEvaluatorTest {

    private final WaveSet waves = Synthesis.build(SeaPresets.get("forte"));

    @Test
    void gridMatchesPointQueries() {
        GridEvaluator g = new GridEvaluator(waves);
        GridOutput out = new GridOutput();
        OceanField f = new OceanField(waves);
        WaveSample s = new WaveSample();
        double[] nrm = new double[3];
        double ox = 123456.0, oz = -98765.0, sp = 1.0, t = 54321.25;
        g.evaluate(ox, oz, sp, 17, t, null, null, out);
        assertEquals(waves.count, out.components);
        for (int j = 0; j < 17; j++) {
            for (int i = 0; i < 17; i++) {
                int q = j * 17 + i;
                f.lagrangian(ox + i * sp, oz + j * sp, t, waves.count, s);
                assertEquals(i * sp + s.dx, out.x[q], 2e-4);
                assertEquals(j * sp + s.dz, out.z[q], 2e-4);
                assertEquals(s.height, out.y[q], 2e-4);
                s.normal(nrm);
                assertEquals(nrm[0], out.nx[q], 2e-4);
                assertEquals(nrm[1], out.ny[q], 2e-4);
                assertEquals(nrm[2], out.nz[q], 2e-4);
            }
        }
    }

    /** O caminho rápido: as mesmas posições, e normais de diferenças finitas perto das analíticas em ondas longas. */
    @Test
    void fastPathMatchesTheAnalyticOne() {
        WaveSet longWaves = Synthesis.build(SeaPresets.get("forte").withWavelengths(16, 600));
        GridEvaluator g = new GridEvaluator(longWaves);
        GridOutput a = new GridOutput(), f = new GridOutput();
        ShoreField shore = shoreOnTheLeft();
        g.evaluate(-8, 0, 1, 17, 12.5, null, shore, a);
        g.evaluateFast(-8, 0, 1, 17, 12.5, null, shore, f);
        for (int q = 0; q < 17 * 17; q++) {
            assertEquals(a.x[q], f.x[q], 1e-4);
            assertEquals(a.y[q], f.y[q], 1e-4);
            assertEquals(a.z[q], f.z[q], 1e-4);
            // ondas de 16 m ou mais com 1 m de espaçamento: erro de diferença central ~ (k·s)²/6 < 3%
            assertEquals(a.nx[q], f.nx[q], 0.03);
            assertEquals(a.ny[q], f.ny[q], 0.01);
            assertEquals(a.nz[q], f.nz[q], 0.03);
        }
    }

    /** Terra em x < 0, mar daí para leste. */
    static ShoreField shoreOnTheLeft() {
        int w = 48, h = 32;
        boolean[] open = new boolean[w * h];
        float[] depth = new float[w * h];
        for (int i = 0; i < open.length; i++) {
            open[i] = i % w >= 16;
            depth[i] = 30;
        }
        return ShoreField.build(-16, -8, w, h, open, depth, 10, 0.78, 2);
    }

    /** Com as tabelas do quadro o resultado é o mesmo (só muda o custo). */
    @Test
    void frameTablesGiveTheSameSurface() {
        GridEvaluator plain = new GridEvaluator(waves), framed = new GridEvaluator(waves);
        GridOutput a = new GridOutput(), b = new GridOutput();
        double t = 98765.4321;
        framed.beginFrame(t, 62000, -1000, 25, 25, 16);
        for (int cx = 62000; cx < 62025; cx += 6) {
            for (int cz = -1000; cz < -975; cz += 5) {
                plain.evaluateFast(cx * 16.0, cz * 16.0, 2, 9, t, null, null, a);
                framed.evaluateFast(cx * 16.0, cz * 16.0, 2, 9, t, null, null, b);
                for (int q = 0; q < 81; q++) {
                    assertEquals(a.y[q], b.y[q], 2e-4);
                    assertEquals(a.x[q], b.x[q], 2e-4);
                    assertEquals(a.ny[q], b.ny[q], 2e-4);
                }
            }
        }
    }

    @Test
    void fastNeighboursShareVerticesAndNormals() {
        GridEvaluator g = new GridEvaluator(waves);
        GridOutput left = new GridOutput(), right = new GridOutput();
        g.evaluateFast(0, 0, 1, 17, 9.0, null, null, left);
        g.evaluateFast(16, 0, 1, 17, 9.0, null, null, right);
        for (int j = 0; j < 17; j++) {
            int ql = j * 17 + 16, qr = j * 17;
            assertEquals(left.x[ql] - 16, right.x[qr], 1e-3);
            assertEquals(left.y[ql], right.y[qr], 1e-4);
            assertEquals(left.nx[ql], right.nx[qr], 1e-4);
            assertEquals(left.ny[ql], right.ny[qr], 1e-4);
            assertEquals(left.nz[ql], right.nz[qr], 1e-4);
        }
    }

    /** Dois pedaços de resoluções diferentes dão o mesmo vértice na aresta comum: não abre fresta. */
    @Test
    void neighboursOfDifferentResolutionShareTheirEdge() {
        GridEvaluator g = new GridEvaluator(waves);
        SpectralLod lod = SpectralLod.at(0, 70, 0, 63);
        GridOutput fine = new GridOutput(), coarse = new GridOutput();
        double t = 77.7;
        // pedaço fino em x ∈ [64, 80], grosso logo depois, x ∈ [80, 96]
        g.evaluate(64, 0, 1.0, 17, t, lod, null, fine);
        g.evaluate(80, 0, 4.0, 5, t, lod, null, coarse);
        for (int j = 0; j < 5; j++) {
            int qf = (j * 4) * 17 + 16, qc = j * 5;
            assertEquals(fine.x[qf] - 16, coarse.x[qc], 1e-3);
            assertEquals(fine.y[qf], coarse.y[qc], 1e-3);
            assertEquals(fine.z[qf], coarse.z[qc], 1e-3);
        }
    }

    @Test
    void farAwayOnlyLongWavesSurvive() {
        GridEvaluator g = new GridEvaluator(waves);
        GridOutput near = new GridOutput(), far = new GridOutput();
        SpectralLod lod = SpectralLod.at(0, 70, 0, 63);
        g.evaluate(0, 0, 0.5, 33, 0, lod, null, near);
        g.evaluate(3000, 3000, 16, 3, 0, lod, null, far);
        assertTrue(far.components < near.components / 2, near.components + " perto, " + far.components + " longe");
        // perto da câmera o espaçamento é 0,5: só as ondas com menos de 2,5 amostras por comprimento saem
        assertEquals(waves.prefix(0.5 * 2.5), near.components, 1);
    }
}
