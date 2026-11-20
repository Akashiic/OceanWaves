package com.akashic.oceanwaves.client;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.akashic.oceanwaves.core.Attenuation;
import com.akashic.oceanwaves.core.FloatBuffer;
import com.akashic.oceanwaves.core.GridEvaluator;
import com.akashic.oceanwaves.core.GridOutput;
import com.akashic.oceanwaves.core.OceanMesher;
import com.akashic.oceanwaves.core.SeaPresets;
import com.akashic.oceanwaves.core.SeaRegion;
import com.akashic.oceanwaves.core.ShoreField;
import com.akashic.oceanwaves.core.SpectralLod;
import com.akashic.oceanwaves.core.Synthesis;
import com.akashic.oceanwaves.core.WaveSet;

/**
 * Medidas do caminho de CPU do renderizador, sem Minecraft: {@code ./gradlew clientBench}. Não é teste: só imprime.
 * Cada bloco mede o "antes" (uma réplica do código que existia) e o "depois" (o código de agora) na mesma execução.
 *
 * <ul>
 * <li>A: avaliação da grade com a costa de mar aberto como ShoreField (o que o desenho recebia: não uniforme) e como
 * sentinela (o que recebe agora);</li>
 * <li>B: refazer a costa de um chunk que chegou (os 25 vizinhos marcados): o jeito antigo (todos no mesmo quadro, uma
 * busca com {@code Long} por coluna) e o de agora (orçamento por quadro; janelas de mar aberto sem conta);</li>
 * <li>C: lixo por quadro dos laços de consulta de chunk, ordenação e vetor de inícios;</li>
 * <li>D: emissão dos vértices num Tessellator emulado (8 ints por vértice, como o vanilla; não é o do Angelica, que
 * custa de 3 a 5 vezes mais por chamada) com e sem as chamadas que o desenho deixou de fazer;</li>
 * <li>E: a malha com o espaçamento mínimo do padrão sem e com shaderpack, e a passagem de sombra.</li>
 * </ul>
 */
public final class ClientPathBench {

    static volatile double sink;
    private static final double WIDTH = 10, GAMMA = 0.78;

    public static void main(String[] args) {
        System.out.println(
            "java " + System.getProperty("java.version")
                + ", "
                + Runtime.getRuntime()
                    .availableProcessors()
                + " núcleos");
        WaveSet waves = Synthesis.build(
            SeaPresets.get("moderado")
                .withComponents(64));
        for (int round = 0; round < 2; round++) {
            System.out.println("--- rodada " + (round + 1) + (round == 0 ? " (JIT frio)" : ""));
            a(waves);
            b(waves);
            c();
            d(waves);
            e(waves);
        }
    }

    // ---------------------------------------------------------------- cena

    /** Os chunks do campo de visão (um leque de ±62° para +z, mais os 3 × 3 da câmera), do mais perto ao mais longe. */
    static List<int[]> fan(int radius) {
        List<int[]> vis = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > radius * radius + 1) {
                    continue;
                }
                if (!(Math.abs(dx) <= 1 && Math.abs(dz) <= 1) && Math.abs(Math.toDegrees(Math.atan2(dx, dz))) > 62) {
                    continue;
                }
                vis.add(new int[] { dx, dz });
            }
        }
        vis.sort((p, q) -> Integer.compare(p[0] * p[0] + p[1] * p[1], q[0] * q[0] + q[1] * q[1]));
        return vis;
    }

    /** O que o ShoreField era para o desenho: sem {@code uniform()} (o padrão do Attenuation). */
    static final class Legacy implements Attenuation {

        final Attenuation inner;

        Legacy(Attenuation inner) {
            this.inner = inner;
        }

        @Override
        public double at(double x, double z) {
            return inner.at(x, z);
        }

        @Override
        public boolean uniform() {
            return false;
        }
    }

    private static double best(double[] v) {
        double m = Double.MAX_VALUE;
        for (double d : v) {
            m = Math.min(m, d);
        }
        return m;
    }

    // ---------------------------------------------------------------- A

    private static void a(WaveSet waves) {
        int radius = 8;
        List<int[]> vis = fan(radius);
        ShoreWindow window = new ShoreWindow();
        for (String scene : new String[] { "alto-mar", "ilha a 40 blocos" }) {
            SyntheticSea sea = new SyntheticSea(10, 40);
            if (scene.startsWith("ilha")) {
                sea.island(40, 40, 24);
            }
            ColumnRing ring = sea.ringOf(9);
            int n = vis.size();
            Attenuation[] legacy = new Attenuation[n], now = new Attenuation[n];
            int uniform = 0;
            for (int i = 0; i < n; i++) {
                OceanChunks.Column c = ring.peek(vis.get(i)[0], vis.get(i)[1]);
                Attenuation a = window.build(ring, c, waves, WIDTH, GAMMA, SeaRegion.EVERYWHERE);
                now[i] = a;
                uniform += a == Attenuation.OPEN_SEA ? 1 : 0;
                // o que o desenho recebia antes: sempre um ShoreField (nunca uniforme)
                legacy[i] = a == Attenuation.OPEN_SEA ? new Legacy(openField(c, waves)) : new Legacy(a);
            }
            double[] before = new double[5], after = new double[5];
            for (int rep = 0; rep < 5; rep++) {
                before[rep] = evalFrame(waves, vis, legacy);
                after[rep] = evalFrame(waves, vis, now);
            }
            System.out.printf(
                Locale.ROOT,
                "A %-17s %d chunks (%d uniformes): avaliação %.2f ms antes, %.2f ms depois (melhor de 5 séries)%n",
                scene,
                n,
                uniform,
                best(before),
                best(after));
        }
    }

    private static ShoreField openField(OceanChunks.Column c, WaveSet waves) {
        int m = ShoreWindow.margin(WIDTH, waves);
        int size = 16 + 2 * m;
        boolean[] open = new boolean[size * size];
        float[] depth = new float[size * size];
        java.util.Arrays.fill(open, true);
        java.util.Arrays.fill(depth, 40);
        return ShoreField.build(
            (c.cx << 4) - m,
            (c.cz << 4) - m,
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

    /** Média por quadro, em ms, de 100 quadros depois de 100 de aquecimento. */
    private static double evalFrame(WaveSet waves, List<int[]> vis, Attenuation[] att) {
        GridEvaluator g = new GridEvaluator(waves);
        GridOutput out = new GridOutput();
        double camX = 8, camY = 38, camZ = 8, rest = 30 + 8.0 / 9;
        int radius = 8;
        long total = 0;
        int frames = 200;
        for (int f = 0; f < frames; f++) {
            double t = 1000 + f / 60.0;
            SpectralLod lod = new SpectralLod(camX, camY, camZ, rest, 1.0, 0.02, 2.5, 5.0);
            g.beginFrame(t, -radius - 1, -radius - 1, 2 * radius + 3, 2 * radius + 3, 16);
            long t0 = System.nanoTime();
            for (int i = 0; i < vis.size(); i++) {
                int cx = vis.get(i)[0], cz = vis.get(i)[1];
                double s = OceanMesher.spacingFor(lod, cx, cz, 1.0);
                g.evaluateFast(cx * 16.0, cz * 16.0, s, (int) Math.round(16 / s) + 1, t, lod, att[i], out);
            }
            if (f >= frames / 2) {
                total += System.nanoTime() - t0;
            }
            sink += out.y[3];
        }
        return total / 1e6 / (frames - frames / 2);
    }

    // ---------------------------------------------------------------- B

    /** O rebuildShore de antes: alocação por chamada e uma busca de {@code Long} por coluna da janela. */
    static ShoreField oldRebuildShore(Map<Long, OceanChunks.Column> map, OceanChunks.Column c, WaveSet waves) {
        int m = (int) Math.ceil(ShoreField.influenceRadius(WIDTH, waves.lipschitz, waves.maxDisplacement)) + 1;
        m = Math.min(m, 32);
        int size = 16 + 2 * m;
        boolean[] open = new boolean[size * size];
        float[] depth = new float[size * size];
        int ox = (c.cx << 4) - m, oz = (c.cz << 4) - m;
        SeaRegion region = SeaRegion.EVERYWHERE;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int wx = ox + x, wz = oz + z;
                OceanChunks.Column n = map.get(((long) (wx >> 4) << 32) ^ ((wz >> 4) & 0xFFFFFFFFL));
                int i = z * size + x;
                if (n == null || n.dirty || !n.loaded) {
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

    private static void b(WaveSet waves) {
        for (String scene : new String[] { "mar aberto", "junto à ilha" }) {
            SyntheticSea sea = new SyntheticSea(8, 40);
            if (scene.startsWith("junto")) {
                sea.island(2, 2, 6);
            }
            ColumnRing ring = sea.ringOf(7);
            Map<Long, OceanChunks.Column> map = new HashMap<>();
            for (int cz = -7; cz <= 7; cz++) {
                for (int cx = -7; cx <= 7; cx++) {
                    map.put(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), ring.peek(cx, cz));
                }
            }
            // antes: 25 costas no mesmo quadro (um chunk chegou: os 5 x 5 em volta, todos os visíveis)
            double[] oldMs = new double[15];
            for (int rep = 0; rep < oldMs.length; rep++) {
                long t0 = System.nanoTime();
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        sink += oldRebuildShore(map, ring.peek(dx, dz), waves).at(8, 8);
                    }
                }
                oldMs[rep] = (System.nanoTime() - t0) / 1e6;
            }
            // depois: orçamento de 2 por quadro; o pior quadro e quantos quadros até ficar tudo pronto
            ShoreScheduler s = new ShoreScheduler();
            ShoreWindow window = new ShoreWindow();
            ShoreScheduler.Builder builder = c -> window.build(ring, c, waves, WIDTH, GAMMA, SeaRegion.EVERYWHERE);
            // tudo pronto antes do chunk chegar
            for (int g = 0; g < 400; g++) {
                s.update(ring, builder, 0, 0, 2);
            }
            double[] worst = new double[15], total = new double[15];
            int frames = 0, built = 0, openSea = 0;
            for (int rep = 0; rep < worst.length; rep++) {
                ring.touchShores(0, 0, 2);
                frames = 0;
                built = 0;
                openSea = 0;
                long all = 0;
                long max = 0;
                while (true) {
                    long t0 = System.nanoTime();
                    s.update(ring, builder, 0, 0, 2);
                    long dt = System.nanoTime() - t0;
                    frames++;
                    built += s.lastBuilt;
                    openSea += s.lastOpenSea;
                    all += dt;
                    max = Math.max(max, dt);
                    if (s.lastBuilt == 0 && s.lastOpenSea == 0 && s.lastWaiting == 0) {
                        break;
                    }
                }
                worst[rep] = max / 1e6;
                total[rep] = all / 1e6;
            }
            System.out.printf(
                Locale.ROOT,
                "B %-12s chegada de um chunk: antes %.2f ms de uma vez; depois %d costas de verdade + %d de mar aberto em %d"
                    + " quadros, pior quadro %.2f ms, total %.2f ms (melhor de 15)%n",
                scene,
                best(oldMs),
                built,
                openSea,
                frames - 1,
                best(worst),
                best(total));
        }
    }

    // ---------------------------------------------------------------- C

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(
            Thread.currentThread()
                .getId());
    }

    private static void c() {
        int radius = 8;
        List<int[]> order = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radius * radius + 1) {
                    order.add(new int[] { dx, dz });
                }
            }
        }
        order.sort((p, q) -> Integer.compare(p[0] * p[0] + p[1] * p[1], q[0] * q[0] + q[1] * q[1]));
        int[] spiral = new int[order.size() * 2];
        for (int i = 0; i < order.size(); i++) {
            spiral[2 * i] = order.get(i)[0];
            spiral[2 * i + 1] = order.get(i)[1];
        }
        Map<Long, OceanChunks.Column> map = new HashMap<>();
        ColumnRing ring = new ColumnRing();
        ring.ensure(radius);
        List<OceanChunks.Column> visibleOld = new ArrayList<>(), visibleNew = new ArrayList<>();
        for (int o = 0; o < spiral.length; o += 2) {
            map.put(
                ((long) spiral[o] << 32) ^ (spiral[o + 1] & 0xFFFFFFFFL),
                new OceanChunks.Column(spiral[o], spiral[o + 1]));
            ring.get(spiral[o], spiral[o + 1]);
        }
        int frames = 20_000;
        double camX = 3.3, camZ = -5.1;
        int[] startsNew = new int[0];
        // antes: computeIfAbsent com chave encaixotada e lambda, sort com lambda, new int[] por quadro
        long a0 = allocated();
        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            visibleOld.clear();
            for (int o = 0; o < spiral.length; o += 2) {
                int cx = spiral[o], cz = spiral[o + 1];
                OceanChunks.Column col = map
                    .computeIfAbsent(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new OceanChunks.Column(cx, cz));
                if (col.cx * 16 + 8 > camX - 80) {
                    visibleOld.add(col);
                }
            }
            final double fx = camX + f * 0.001;
            visibleOld.sort((p, q) -> Double.compare(d2(p, fx, camZ), d2(q, fx, camZ)));
            int[] starts = new int[visibleOld.size() + 1];
            sink += starts.length + visibleOld.get(0).cx;
        }
        long t1 = System.nanoTime();
        long a1 = allocated();
        // depois: o anel, inserção sem lambda, vetor reaproveitado
        for (int f = 0; f < frames; f++) {
            visibleNew.clear();
            for (int o = 0; o < spiral.length; o += 2) {
                OceanChunks.Column col = ring.get(spiral[o], spiral[o + 1]);
                if (col.cx * 16 + 8 > camX - 80) {
                    visibleNew.add(col);
                }
            }
            double fx = camX + f * 0.001;
            for (int i = 1; i < visibleNew.size(); i++) {
                OceanChunks.Column col = visibleNew.get(i);
                double d = d2(col, fx, camZ);
                int j = i - 1;
                while (j >= 0 && d2(visibleNew.get(j), fx, camZ) > d) {
                    visibleNew.set(j + 1, visibleNew.get(j));
                    j--;
                }
                visibleNew.set(j + 1, col);
            }
            if (startsNew.length < visibleNew.size() + 1) {
                startsNew = new int[visibleNew.size() + 1];
            }
            sink += startsNew.length + visibleNew.get(0).cx;
        }
        long t2 = System.nanoTime();
        long a2 = allocated();
        System.out.printf(
            Locale.ROOT,
            "C lixo por quadro (%d chunks na espiral): antes %d bytes, %.1f us; depois %d bytes, %.1f us%n",
            spiral.length / 2,
            (a1 - a0) / frames,
            (t1 - t0) / 1e3 / frames,
            (a2 - a1) / frames,
            (t2 - t1) / 1e3 / frames);
    }

    private static double d2(OceanChunks.Column c, double x, double z) {
        double dx = c.cx * 16 + 8 - x, dz = c.cz * 16 + 8 - z;
        return dx * dx + dz * dz;
    }

    // ---------------------------------------------------------------- D

    /** O Tessellator vanilla, emulado: 8 ints por vértice; conta as chamadas. */
    static final class FakeTess {

        int[] raw = new int[1 << 16];
        int idx;
        double xo, yo, zo;
        int color, normal, bright;
        long colorCalls, brightCalls, normalCalls, vertexCalls;

        void start() {
            idx = 0;
        }

        void setColorRGBA(int r, int g, int b, int a) {
            color = a << 24 | b << 16 | g << 8 | r;
            colorCalls++;
        }

        void setBrightness(int b) {
            bright = b;
            brightCalls++;
        }

        void setNormal(float x, float y, float z) {
            byte bx = (byte) (int) (x * 127), by = (byte) (int) (y * 127), bz = (byte) (int) (z * 127);
            normal = bx & 255 | (by & 255) << 8 | (bz & 255) << 16;
            normalCalls++;
        }

        void addVertexWithUV(double x, double y, double z, double u, double v) {
            if (idx + 8 > raw.length) {
                raw = java.util.Arrays.copyOf(raw, raw.length * 2);
            }
            raw[idx] = Float.floatToRawIntBits((float) (x + xo));
            raw[idx + 1] = Float.floatToRawIntBits((float) (y + yo));
            raw[idx + 2] = Float.floatToRawIntBits((float) (z + zo));
            raw[idx + 3] = Float.floatToRawIntBits((float) u);
            raw[idx + 4] = Float.floatToRawIntBits((float) v);
            raw[idx + 5] = color;
            raw[idx + 6] = normal;
            raw[idx + 7] = bright;
            idx += 8;
            vertexCalls++;
        }
    }

    /** A cor do SurfaceShader sem a luz própria (com shaderpack): o tom da água e a espuma. */
    static final class PackShade implements OceanMesher.Shade {

        final float[] tint = { .25f, .35f, .85f };
        final int rgb = 0x3F76E4;

        @Override
        public int shade(int col, float px, float py, float pz, float nx, float ny, float nz, float foam) {
            float r = (rgb >> 16 & 255) / 255f * tint[0], g = (rgb >> 8 & 255) / 255f * tint[1],
                b = (rgb & 255) / 255f * tint[2], a = .78f;
            if (foam > 0) {
                float f = foam * 0.7f;
                r += (1 - r) * f;
                g += (1 - g) * f;
                b += (1 - b) * f;
                a += (1 - a) * foam * 0.5f;
            }
            return to255(r) << 24 | to255(g) << 16 | to255(b) << 8 | to255(a);
        }

        static int to255(float v) {
            return v <= 0 ? 0 : v >= 1 ? 255 : (int) (v * 255 + 0.5f);
        }
    }

    /** A cor do SurfaceShader com luz própria (sem shaderpack): uma conta por vértice (réplica enxuta). */
    static final class OwnShade implements OceanMesher.Shade {

        @Override
        public int shade(int col, float px, float py, float pz, float nx, float ny, float nz, float foam) {
            float l = 0.55f + 0.45f * Math.max(0, ny);
            float r = 0.25f * l + 0.1f * foam, g = 0.35f * l + 0.1f * foam, b = 0.85f * l + 0.1f * foam;
            return PackShade.to255(r) << 24 | PackShade.to255(g) << 16
                | PackShade.to255(b) << 8
                | PackShade.to255(0.78f);
        }
    }

    private static void d(WaveSet waves) {
        int radius = 8;
        List<int[]> vis = fan(radius);
        OceanMesher mesher = new OceanMesher(waves);
        double camX = 8, camY = 38, camZ = 8, rest = 30 + 8.0 / 9;
        boolean[] open = new boolean[256];
        java.util.Arrays.fill(open, true);
        double[] nb = new double[4];
        for (boolean own : new boolean[] { true, false }) {
            OceanMesher.Shade shade = own ? new OwnShade() : new PackShade();
            FloatBuffer buf = new FloatBuffer();
            SpectralLod lod = new SpectralLod(camX, camY, camZ, rest, 1.0, 0.02, 2.5, 5.0);
            mesher.grid()
                .beginFrame(1000, -radius - 1, -radius - 1, 2 * radius + 3, 2 * radius + 3, 16);
            int[] starts = new int[vis.size() + 1];
            int[] light = new int[256];
            java.util.Arrays.fill(light, 15 << 20);
            buf.clear();
            for (int i = 0; i < vis.size(); i++) {
                int cx = vis.get(i)[0], cz = vis.get(i)[1];
                starts[i] = buf.size;
                double s = OceanMesher.spacingFor(lod, cx, cz, 1.0);
                for (int k = 0; k < 4; k++) {
                    nb[k] = s;
                }
                mesher.mesh(cx, cz, s, nb, open, 1000, lod, Attenuation.OPEN_SEA, shade, buf);
            }
            starts[vis.size()] = buf.size;
            FakeTess tess = new FakeTess();
            double oldMs = Double.MAX_VALUE, newMs = Double.MAX_VALUE;
            long oldCalls = 0, newCalls = 0;
            for (int rep = 0; rep < 12; rep++) {
                tess.colorCalls = tess.brightCalls = tess.normalCalls = tess.vertexCalls = 0;
                long t0 = System.nanoTime();
                for (int q = 0; q < 20; q++) {
                    emitOld(tess, buf.data, starts, vis, own, light);
                }
                oldMs = Math.min(oldMs, (System.nanoTime() - t0) / 1e6 / 20);
                oldCalls = (tess.colorCalls + tess.brightCalls + tess.normalCalls + tess.vertexCalls) / 20;
                tess.colorCalls = tess.brightCalls = tess.normalCalls = tess.vertexCalls = 0;
                t0 = System.nanoTime();
                for (int q = 0; q < 20; q++) {
                    emitNew(tess, buf.data, starts, vis, own, light);
                }
                newMs = Math.min(newMs, (System.nanoTime() - t0) / 1e6 / 20);
                newCalls = (tess.colorCalls + tess.brightCalls + tess.normalCalls + tess.vertexCalls) / 20;
            }
            int verts = (starts[vis.size()]) / OceanMesher.FLOATS;
            System.out.printf(
                Locale.ROOT,
                "D emissão %-18s %d vértices: antes %.2f ms (%d chamadas ao Tessellator), depois %.2f ms (%d chamadas)%n",
                own ? "(luz própria)" : "(shaderpack)",
                verts,
                oldMs,
                oldCalls,
                newMs,
                newCalls);
        }
    }

    /** O laço de submit de antes: cor, luz (sem own) e normal em todo vértice. */
    private static void emitOld(FakeTess tess, float[] d, int[] starts, List<int[]> vis, boolean own, int[] light) {
        tess.start();
        for (int i = 0; i + 1 < starts.length; i++) {
            tess.xo = vis.get(i)[0] * 16.0 - 8;
            tess.yo = -7;
            tess.zo = vis.get(i)[1] * 16.0 - 8;
            double tu = (vis.get(i)[0] & 63) * 16.0, tv = (vis.get(i)[1] & 63) * 16.0;
            for (int k = starts[i]; k < starts[i + 1]; k += OceanMesher.FLOATS) {
                int rgba = Float.floatToRawIntBits(d[k + 9]);
                tess.setColorRGBA(rgba >>> 24, rgba >> 16 & 255, rgba >> 8 & 255, rgba & 255);
                if (!own) {
                    int col = Math.min(15, Math.max(0, (int) d[k + 7]))
                        + 16 * Math.min(15, Math.max(0, (int) d[k + 8]));
                    tess.setBrightness(light[col]);
                }
                tess.setNormal(d[k + 3], d[k + 4], d[k + 5]);
                tess.addVertexWithUV(d[k], d[k + 1], d[k + 2], tu + d[k + 7], tv + d[k + 8]);
            }
        }
        sink += tess.raw[tess.idx / 2];
    }

    /**
     * O de agora: o que o Tessellator guarda de um vértice para o outro só é mandado quando muda; sem normal com own.
     */
    private static void emitNew(FakeTess tess, float[] d, int[] starts, List<int[]> vis, boolean own, int[] light) {
        tess.start();
        boolean haveColor = false;
        int lastColor = 0, lastLight = -1;
        for (int i = 0; i + 1 < starts.length; i++) {
            tess.xo = vis.get(i)[0] * 16.0 - 8;
            tess.yo = -7;
            tess.zo = vis.get(i)[1] * 16.0 - 8;
            double tu = (vis.get(i)[0] & 63) * 16.0, tv = (vis.get(i)[1] & 63) * 16.0;
            for (int k = starts[i], end = starts[i + 1]; k < end; k += OceanMesher.FLOATS) {
                int rgba = Float.floatToRawIntBits(d[k + 9]);
                if (!haveColor || rgba != lastColor) {
                    tess.setColorRGBA(rgba >>> 24, rgba >> 16 & 255, rgba >> 8 & 255, rgba & 255);
                    lastColor = rgba;
                    haveColor = true;
                }
                if (!own) {
                    int col = Math.min(15, Math.max(0, (int) d[k + 7]))
                        + 16 * Math.min(15, Math.max(0, (int) d[k + 8]));
                    int b = light[col];
                    if (b != lastLight) {
                        tess.setBrightness(b);
                        lastLight = b;
                    }
                    tess.setNormal(d[k + 3], d[k + 4], d[k + 5]);
                }
                tess.addVertexWithUV(d[k], d[k + 1], d[k + 2], tu + d[k + 7], tv + d[k + 8]);
            }
        }
        sink += tess.raw[tess.idx / 2];
    }

    // ---------------------------------------------------------------- E

    private static void e(WaveSet waves) {
        int radius = 8;
        OceanMesher mesher = new OceanMesher(waves);
        double camX = 8, camY = 38, camZ = 8, rest = 30 + 8.0 / 9;
        boolean[] open = new boolean[256];
        java.util.Arrays.fill(open, true);
        double[] nb = new double[4];
        PackShade shade = new PackShade();
        FloatBuffer buf = new FloatBuffer();
        // 1: padrão sem shaderpack; 2: padrão com shaderpack (espaçamentoComShader); sombra: células >= 4, sem recorte
        List<int[]> fan = fan(radius), all = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radius * radius + 1) {
                    all.add(new int[] { dx, dz });
                }
            }
        }
        Object[][] cases = { { "sem shaderpack (espaço 1)", fan, 1.0 }, { "com shaderpack (espaço 2)", fan, 2.0 },
            { "sombra, antes (espaço 4, sem recorte)", all, 4.0 } };
        for (Object[] cs : cases) {
            @SuppressWarnings("unchecked")
            List<int[]> list = (List<int[]>) cs[1];
            double min = (Double) cs[2];
            double best = Double.MAX_VALUE;
            int quads = 0;
            for (int rep = 0; rep < 60; rep++) {
                SpectralLod lod = new SpectralLod(camX, camY, camZ, rest, min, 0.02, 2.5, 5.0);
                mesher.grid()
                    .beginFrame(1000 + rep / 60.0, -radius - 1, -radius - 1, 2 * radius + 3, 2 * radius + 3, 16);
                buf.clear();
                quads = 0;
                long t0 = System.nanoTime();
                for (int i = 0; i < list.size(); i++) {
                    int cx = list.get(i)[0], cz = list.get(i)[1];
                    double s = OceanMesher.spacingFor(lod, cx, cz, min);
                    nb[0] = OceanMesher.spacingFor(lod, cx - 1, cz, min);
                    nb[1] = OceanMesher.spacingFor(lod, cx + 1, cz, min);
                    nb[2] = OceanMesher.spacingFor(lod, cx, cz - 1, min);
                    nb[3] = OceanMesher.spacingFor(lod, cx, cz + 1, min);
                    quads += mesher.mesh(cx, cz, s, nb, open, 1000 + rep / 60.0, lod, Attenuation.OPEN_SEA, shade, buf);
                }
                if (rep >= 20) {
                    best = Math.min(best, (System.nanoTime() - t0) / 1e6);
                }
            }
            System.out.printf(
                Locale.ROOT,
                "E malha %-38s %3d chunks, %6d quads: %.2f ms (melhor de 40)%n",
                cs[0],
                list.size(),
                quads,
                best);
        }
    }
}
