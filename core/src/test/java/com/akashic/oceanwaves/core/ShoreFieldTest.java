package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Dois chunks vizinhos, cada um com a atenuação calculada na própria região (o chunk e uma folga), têm de dar os
     * mesmos valores na borda comum, num terreno com profundidade variada e um mar de tempestade. Com a folga antiga
     * (a largura da costa + 1) os valores divergem e a superfície abre uma fresta na borda (visto no jogo); com a folga
     * de {@link ShoreField#influenceRadius} são iguais.
     */
    @Test
    void neighbouringChunksAgreeOnTheirEdge() {
        WaveSet waves = Synthesis.build(SeaPresets.get("tempestade").withChoppiness(1));
        double width = 10, l = waves.lipschitz, m = waves.maxDisplacement;
        // um "mundo" de 160 x 160 colunas de mar fundo com uma ilha de 3 x 3 a 15 blocos a oeste da borda comum
        // (x = 80): a região do chunk de oeste a vê, a do chunk de leste (com a folga antiga) não
        int size = 160;
        boolean[] worldOpen = new boolean[size * size];
        float[] worldDepth = new float[size * size];
        Arrays.fill(worldOpen, true);
        Arrays.fill(worldDepth, 30);
        for (int z = 70; z < 73; z++) {
            for (int x = 64; x < 67; x++) {
                worldOpen[z * size + x] = false;
            }
        }
        int newMargin = (int) Math.ceil(ShoreField.influenceRadius(width, l, m)) + 1;
        assertTrue(newMargin > (int) Math.ceil(width) + 1, "a tempestade influi além da costa");
        double oldWorst = edgeMismatch(worldOpen, worldDepth, size, (int) Math.ceil(width) + 1, width, l, m, waves);
        double newWorst = edgeMismatch(worldOpen, worldDepth, size, newMargin, width, l, m, waves);
        assertTrue(oldWorst > 1e-3, "a folga antiga deveria divergir: " + oldWorst);
        assertTrue(newWorst < 1e-5, "com a folga nova a borda diverge " + newWorst);
    }

    /** A maior diferença de atenuação na borda x = 80 entre o chunk de x 64..80 e o de x 80..96. */
    private static double edgeMismatch(boolean[] open, float[] depth, int size, int margin, double width, double l,
        double m, WaveSet waves) {
        ShoreField a = region(open, depth, size, 64, 64, margin, width, l, m, waves);
        ShoreField b = region(open, depth, size, 80, 64, margin, width, l, m, waves);
        double worst = 0;
        for (int z = 64; z <= 80; z++) {
            worst = Math.max(worst, Math.abs(a.cornerAt(80, z) - b.cornerAt(80, z)));
        }
        return worst;
    }

    private static ShoreField region(boolean[] open, float[] depth, int size, int cx, int cz, int margin, double width,
        double l, double m, WaveSet waves) {
        int w = 16 + 2 * margin;
        boolean[] o = new boolean[w * w];
        float[] d = new float[w * w];
        for (int z = 0; z < w; z++) {
            for (int x = 0; x < w; x++) {
                int wx = cx - margin + x, wz = cz - margin + z;
                o[z * w + x] = open[wz * size + wx];
                d[z * w + x] = depth[wz * size + wx];
            }
        }
        return ShoreField.build(cx - margin, cz - margin, w, w, o, d, width, 0.78, waves.significantHeight, l, m);
    }

    /** PERF-3: sem costa na região todos os cantos valem exatamente 1 e o campo é "uniforme" (caminho barato da grade). */
    @Test
    void anOpenDeepRegionIsUniformAndExactlyOne() {
        WaveSet waves = Synthesis.build(SeaPresets.get("tempestade").withChoppiness(1));
        int w = 60, h = 60;
        boolean[] open = new boolean[w * h];
        float[] depth = new float[w * h];
        Arrays.fill(open, true);
        Arrays.fill(depth, 40);
        // com o limite de gradiente (o par log/exp pode dar 0,99999994) e sem ele
        ShoreField a = ShoreField.build(5, -7, w, h, open, depth, 10, 0.78, waves.significantHeight, waves.lipschitz,
            waves.maxDisplacement);
        ShoreField b = ShoreField.build(5, -7, w, h, open, depth, 10, 0.78, waves.significantHeight);
        for (ShoreField f : new ShoreField[] { a, b }) {
            assertTrue(f.uniform());
            for (int z = -7; z <= -7 + h; z++) {
                for (int x = 5; x <= 5 + w; x++) {
                    assertEquals(1f, f.cornerAt(x, z), 0f);
                }
            }
        }
    }

    @Test
    void aRegionWithCoastOrShoalsIsNotUniform() {
        int w = 60, h = 60;
        boolean[] open = new boolean[w * h];
        float[] depth = new float[w * h];
        Arrays.fill(open, true);
        Arrays.fill(depth, 40);
        ShoreField deep = ShoreField.build(0, 0, w, h, open, depth, 10, 0.78, 2);
        assertTrue(deep.uniform());
        open[30 * w + 30] = false; // um bloco de terra
        assertFalse(ShoreField.build(0, 0, w, h, open, depth, 10, 0.78, 2).uniform());
        open[30 * w + 30] = true;
        Arrays.fill(depth, 1f); // fundo raso em tudo: a onda quebra
        assertFalse(ShoreField.build(0, 0, w, h, open, depth, 10, 0.78, 2).uniform());
    }

    /** O caminho uniforme dá a mesma superfície que o caminho por ponto, num campo todo 1. */
    @Test
    void theUniformPathGivesTheSameSurfaceAsTheSlowOne() {
        WaveSet waves = Synthesis.build(SeaPresets.get("moderado"));
        int m = (int) Math.ceil(ShoreField.influenceRadius(10, waves.lipschitz, waves.maxDisplacement)) + 1;
        int size = 16 + 2 * m;
        boolean[] open = new boolean[size * size];
        float[] depth = new float[size * size];
        Arrays.fill(open, true);
        Arrays.fill(depth, 50);
        ShoreField f = ShoreField.build(-m, -m, size, size, open, depth, 10, 0.78, waves.significantHeight,
            waves.lipschitz, waves.maxDisplacement);
        assertTrue(f.uniform());
        Attenuation slow = new Attenuation() {
            @Override
            public double at(double x, double z) {
                return f.at(x, z);
            }

            @Override
            public boolean uniform() {
                return false;
            }
        };
        GridEvaluator g = new GridEvaluator(waves);
        GridOutput fast = new GridOutput(), ref = new GridOutput();
        SpectralLod lod = SpectralLod.at(40, 70, 40, 63);
        double worst = 0;
        for (double spacing : new double[] { 0.5, 1, 2, 4 }) {
            int n = (int) Math.round(16 / spacing) + 1;
            g.evaluateFast(0, 0, spacing, n, 3.3, lod, f, fast);
            g.evaluateFast(0, 0, spacing, n, 3.3, lod, slow, ref);
            for (int i = 0; i < n * n; i++) {
                worst = Math.max(worst, Math.abs(fast.x[i] - ref.x[i]));
                worst = Math.max(worst, Math.abs(fast.y[i] - ref.y[i]));
                worst = Math.max(worst, Math.abs(fast.z[i] - ref.z[i]));
                worst = Math.max(worst, Math.abs(fast.nx[i] - ref.nx[i]));
                worst = Math.max(worst, Math.abs(fast.ny[i] - ref.ny[i]));
                worst = Math.max(worst, Math.abs(fast.nz[i] - ref.nz[i]));
            }
        }
        assertEquals(0, worst, 1e-6);
    }

    /**
     * Um chunk de janela uniforme (a ilha está além da folga dele) e o vizinho, de janela calculada (a ilha está nela),
     * dão exatamente 1 na borda que dividem: sem fresta entre o caminho uniforme e o calculado.
     */
    @Test
    void aUniformChunkAndAComputedNeighbourAgreeExactlyOnTheirEdge() {
        WaveSet waves = Synthesis.build(SeaPresets.get("tempestade").withChoppiness(1));
        double width = 10, l = waves.lipschitz, mx = waves.maxDisplacement;
        int margin = (int) Math.ceil(ShoreField.influenceRadius(width, l, mx)) + 1;
        int size = 600;
        boolean[] open = new boolean[size * size];
        float[] depth = new float[size * size];
        Arrays.fill(open, true);
        Arrays.fill(depth, 40);
        // ilha a 8 blocos além da folga da janela do chunk A (x 200..216), a leste do chunk B (x 216..232)
        int islandX = 216 + 16 + margin + 8 - 16;
        for (int z = 300; z < 303; z++) {
            for (int x = islandX; x < islandX + 3; x++) {
                open[z * size + x] = false;
            }
        }
        ShoreField a = region(open, depth, size, 200, 296, margin, width, l, mx, waves);
        ShoreField b = region(open, depth, size, 216, 296, margin, width, l, mx, waves);
        assertTrue(a.uniform(), "o chunk A não vê a ilha");
        assertFalse(b.uniform(), "o chunk B vê a ilha");
        for (int z = 296; z <= 312; z++) {
            assertEquals(a.cornerAt(216, z), b.cornerAt(216, z), 0f, "z = " + z);
        }
    }
}
