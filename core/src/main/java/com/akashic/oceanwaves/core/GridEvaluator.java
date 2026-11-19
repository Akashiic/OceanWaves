package com.akashic.oceanwaves.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Avalia a superfície numa grade regular (um pedaço da malha do cliente), muito mais barato que n² consultas.
 *
 * <p>A fase de uma componente no vértice (i, j) é θ = θ₀ + kₓ·i·s + k_z·j·s, com θ₀ a fase na origem. Logo
 * e^{iθ} = e^{iθ₀} · Tx[i] · Tz[j], com Tx[i] = e^{i·kₓ·i·s} e Tz[j] = e^{i·k_z·j·s} tabelados uma vez por
 * espaçamento (não mudam com o tempo nem com o lugar). Por pedaço e componente: um seno e um cosseno (θ₀, reduzido
 * módulo 2π em double, o que deixa o resto em float sem perder precisão em coordenadas grandes); por linha, um produto
 * complexo; por vértice, dois produtos e as somas. O laço interno não tem dependência entre vértices e a JIT pode
 * vetorizá-lo.
 *
 * <p>Uma instância por thread (guarda tabelas e vetores de trabalho).
 */
public final class GridEvaluator {

    private static final double TWO_PI = 2 * Math.PI;

    private final WaveSet waves;
    private final Map<Long, float[][]> tables = new HashMap<>();
    private float[] eta = new float[0], ddx = new float[0], ddz = new float[0], dhx = new float[0],
        dhz = new float[0], dxx = new float[0], dxz = new float[0], dzz = new float[0];
    private final double[] grad = new double[2];
    private final double[] cw = new double[4];

    /*
     * Fasores do quadro: e^{i·θ} na origem de um chunk é e^{i(−ωt+φ)} · e^{i·kₓ·16·cx} · e^{i·k_z·16·cz}. Com as três
     * tabelas prontas no começo do quadro, cada chunk sai com dois produtos complexos por componente em vez de um
     * seno e um cosseno (que no Java 8 dos clientes custam ~150 ns o par).
     */
    private int frameCount = -1;
    private double frameT;
    private int frameCx0, frameCz0, frameW, frameH, frameSize;
    private double[] ftr = new double[0], fti = new double[0], fxr = new double[0], fxi = new double[0],
        fzr = new double[0], fzi = new double[0];

    /**
     * Prepara os fasores para os chunks (cx0..cx0+w−1, cz0..cz0+h−1) de lado {@code size} blocos no instante t. As
     * chamadas de {@link #evaluateFast} com o mesmo t e origem num desses cantos passam a não usar trigonometria.
     */
    public void beginFrame(double t, int cx0, int cz0, int w, int h, int size) {
        int m = waves.count;
        frameCount = m;
        frameT = t;
        frameCx0 = cx0;
        frameCz0 = cz0;
        frameW = w;
        frameH = h;
        frameSize = size;
        if (ftr.length < m) {
            ftr = new double[m];
            fti = new double[m];
        }
        if (fxr.length < m * w) {
            fxr = new double[m * w];
            fxi = new double[m * w];
        }
        if (fzr.length < m * h) {
            fzr = new double[m * h];
            fzi = new double[m * h];
        }
        for (int c = 0; c < m; c++) {
            double th = -waves.omega[c] * t + waves.phase[c];
            th -= TWO_PI * Math.floor(th / TWO_PI);
            ftr[c] = Math.cos(th);
            fti[c] = Math.sin(th);
            axis(waves.kx[c] * size, cx0, w, fxr, fxi, c * w);
            axis(waves.kz[c] * size, cz0, h, fzr, fzi, c * h);
        }
    }

    /** e^{i·step·(c0 + i)} para i em [0, n): o primeiro por trigonometria, os demais por recorrência re-ancorada. */
    private static void axis(double step, int c0, int n, double[] re, double[] im, int at) {
        double sr = Math.cos(step), si = Math.sin(step);
        double r = 0, q = 0;
        for (int i = 0; i < n; i++) {
            if ((i & 15) == 0) {
                double th = step * (c0 + i);
                th -= TWO_PI * Math.floor(th / TWO_PI);
                r = Math.cos(th);
                q = Math.sin(th);
            } else {
                double nr = r * sr - q * si;
                q = r * si + q * sr;
                r = nr;
            }
            re[at + i] = r;
            im[at + i] = q;
        }
    }

    /** O fasor da componente c na origem (x, z), das tabelas do quadro se der; {re, im} em {@code out}. */
    private void phasor(int c, double x, double z, double t, double[] out) {
        if (frameCount == waves.count && t == frameT) {
            int size = frameSize;
            int cx = (int) Math.floor(x / size), cz = (int) Math.floor(z / size);
            double rx = x - (double) cx * size, rz = z - (double) cz * size;
            int ix = cx - frameCx0, iz = cz - frameCz0;
            if (ix >= 0 && iz >= 0 && ix < frameW && iz < frameH && Math.abs(rx) < 64 && Math.abs(rz) < 64) {
                double ar = ftr[c], ai = fti[c];
                double xr = fxr[c * frameW + ix], xi = fxi[c * frameW + ix];
                double zr = fzr[c * frameH + iz], zi = fzi[c * frameH + iz];
                double pr = ar * xr - ai * xi, pi = ar * xi + ai * xr;
                double qr = pr * zr - pi * zi, qi = pr * zi + pi * zr;
                // o resto (borda de −s, por exemplo) é pequeno: um seno de argumento pequeno é barato
                double th = waves.kx[c] * rx + waves.kz[c] * rz;
                if (th != 0) {
                    double er = Math.cos(th), ei = Math.sin(th);
                    double nr = qr * er - qi * ei;
                    qi = qr * ei + qi * er;
                    qr = nr;
                }
                out[0] = qr;
                out[1] = qi;
                return;
            }
        }
        double th = waves.kx[c] * x + waves.kz[c] * z - waves.omega[c] * t + waves.phase[c];
        th -= TWO_PI * Math.floor(th / TWO_PI);
        out[0] = Math.cos(th);
        out[1] = Math.sin(th);
    }

    private final double[] ph = new double[2];

    /** Jacobiano abaixo do qual começa a espuma, e onde ela é total. */
    public float foamStart = 0.55f;
    public float foamFull = 0.2f;

    public GridEvaluator(WaveSet waves) {
        this.waves = waves;
    }

    public WaveSet waves() {
        return waves;
    }

    /** Tabelas {Txr, Txi, Tzr, Tzi} de cada componente, achatadas: componente c ocupa [c·n, (c+1)·n). */
    private float[][] table(double spacing, int n) {
        long key = Double.doubleToLongBits(spacing) * 1021 + n;
        float[][] t = tables.get(key);
        if (t == null) {
            int m = waves.count;
            t = new float[4][m * n];
            for (int c = 0; c < m; c++) {
                for (int i = 0; i < n; i++) {
                    double ax = waves.kx[c] * i * spacing, az = waves.kz[c] * i * spacing;
                    t[0][c * n + i] = (float) Math.cos(ax);
                    t[1][c * n + i] = (float) Math.sin(ax);
                    t[2][c * n + i] = (float) Math.cos(az);
                    t[3][c * n + i] = (float) Math.sin(az);
                }
            }
            if (tables.size() > 64) {
                tables.clear();
            }
            tables.put(key, t);
        }
        return t;
    }

    private void scratch3(int len) {
        if (eta.length < len) {
            scratch(len);
            return;
        }
        java.util.Arrays.fill(eta, 0, len, 0);
        java.util.Arrays.fill(ddx, 0, len, 0);
        java.util.Arrays.fill(ddz, 0, len, 0);
    }

    private void scratch(int len) {
        if (eta.length < len) {
            eta = new float[len];
            ddx = new float[len];
            ddz = new float[len];
            dhx = new float[len];
            dhz = new float[len];
            dxx = new float[len];
            dxz = new float[len];
            dzz = new float[len];
        }
        java.util.Arrays.fill(eta, 0, len, 0);
        java.util.Arrays.fill(ddx, 0, len, 0);
        java.util.Arrays.fill(ddz, 0, len, 0);
        java.util.Arrays.fill(dhx, 0, len, 0);
        java.util.Arrays.fill(dhz, 0, len, 0);
        java.util.Arrays.fill(dxx, 0, len, 0);
        java.util.Arrays.fill(dxz, 0, len, 0);
        java.util.Arrays.fill(dzz, 0, len, 0);
    }

    /**
     * Avalia n × n vértices com origem (ox, oz) e espaçamento {@code spacing}, no instante t.
     *
     * @param lod filtro de distância (null: todas as componentes, como a física)
     * @param att atenuação da costa (null: mar aberto)
     */
    public void evaluate(double ox, double oz, double spacing, int n, double t, SpectralLod lod, Attenuation att,
        GridOutput out) {
        int len = n * n;
        scratch(len);
        out.ensure(n);
        float[][] tab = table(spacing, n);
        float[] txr = tab[0], txi = tab[1], tzr = tab[2], tzi = tab[3];
        WaveSet w = waves;
        double ext = spacing * (n - 1);
        double s00 = 0, s10 = 0, s01 = 0, s11 = 0;
        if (lod != null) {
            s00 = lod.spacing(ox, oz);
            s10 = lod.spacing(ox + ext, oz);
            s01 = lod.spacing(ox, oz + ext);
            s11 = lod.spacing(ox + ext, oz + ext);
        }
        float inv = n > 1 ? 1f / (n - 1) : 0f;
        int used = 0;
        for (int c = 0; c < w.count; c++) {
            boolean partial = false;
            if (lod != null) {
                double lambda = w.wavelength(c);
                cw[0] = lod.weight(lambda, s00);
                cw[1] = lod.weight(lambda, s10);
                cw[2] = lod.weight(lambda, s01);
                cw[3] = lod.weight(lambda, s11);
                if (cw[0] == 0 && cw[1] == 0 && cw[2] == 0 && cw[3] == 0) {
                    break; // as seguintes são mais curtas: peso zero também
                }
                partial = cw[0] != 1 || cw[1] != 1 || cw[2] != 1 || cw[3] != 1;
            }
            used = c + 1;
            double th = w.kx[c] * ox + w.kz[c] * oz - w.omega[c] * t + w.phase[c];
            th -= TWO_PI * Math.floor(th / TWO_PI);
            float br = (float) Math.cos(th), bi = (float) Math.sin(th);
            double a = w.amplitude[c], qa = w.q[c] * a;
            float ca = (float) a, cdx = (float) (-qa * w.dirX[c]), cdz = (float) (-qa * w.dirZ[c]);
            float chx = (float) (-a * w.kx[c]), chz = (float) (-a * w.kz[c]);
            float cxx = (float) (-qa * w.dirX[c] * w.kx[c]), cxz = (float) (-qa * w.dirX[c] * w.kz[c]);
            float czz = (float) (-qa * w.dirZ[c] * w.kz[c]);
            int base = c * n;
            for (int j = 0; j < n; j++) {
                float rr = br * tzr[base + j] - bi * tzi[base + j];
                float ri = br * tzi[base + j] + bi * tzr[base + j];
                int row = j * n;
                if (!partial) {
                    for (int i = 0; i < n; i++) {
                        float co = rr * txr[base + i] - ri * txi[base + i];
                        float si = rr * txi[base + i] + ri * txr[base + i];
                        int q = row + i;
                        eta[q] += ca * co;
                        ddx[q] += cdx * si;
                        ddz[q] += cdz * si;
                        dhx[q] += chx * si;
                        dhz[q] += chz * si;
                        dxx[q] += cxx * co;
                        dxz[q] += cxz * co;
                        dzz[q] += czz * co;
                    }
                } else {
                    float v = j * inv;
                    float wl = (float) (cw[0] + (cw[2] - cw[0]) * v);
                    float wr = (float) (cw[1] + (cw[3] - cw[1]) * v);
                    float dw = (wr - wl) * inv;
                    for (int i = 0; i < n; i++) {
                        float co = rr * txr[base + i] - ri * txi[base + i];
                        float si = rr * txi[base + i] + ri * txr[base + i];
                        float wt = wl + dw * i;
                        co *= wt;
                        si *= wt;
                        int q = row + i;
                        eta[q] += ca * co;
                        ddx[q] += cdx * si;
                        ddz[q] += cdz * si;
                        dhx[q] += chx * si;
                        dhz[q] += chz * si;
                        dxx[q] += cxx * co;
                        dxz[q] += cxz * co;
                        dzz[q] += czz * co;
                    }
                }
            }
        }
        out.components = used;
        compose(ox, oz, spacing, n, att, out);
    }

    private float[] px = new float[0], py = new float[0], pz = new float[0];

    /**
     * O caminho rápido do desenho: só η e o deslocamento (3 somas em vez de 8), numa grade com uma borda de um
     * vértice de cada lado; as normais e o jacobiano saem de diferenças centrais das posições já deslocadas. A
     * normal fica a da superfície que de fato se desenha (sem detalhe que a malha não representa, que cintilaria), e
     * o custo cai pela metade. Nas arestas comuns a pedaços do mesmo espaçamento, o vértice e os vizinhos usados na
     * diferença são os mesmos nos dois lados: a sombra também não tem costura.
     */
    public void evaluateFast(double ox, double oz, double spacing, int n, double t, SpectralLod lod, Attenuation att,
        GridOutput out) {
        int m = n + 2, len = m * m;
        double bx = ox - spacing, bz = oz - spacing;
        scratch3(len);
        out.ensure(n);
        final float[] eta = this.eta, ddx = this.ddx, ddz = this.ddz;
        float[][] tab = table(spacing, m);
        float[] txr = tab[0], txi = tab[1], tzr = tab[2], tzi = tab[3];
        WaveSet w = waves;
        double ext = spacing * (n - 1);
        double s00 = 0, s10 = 0, s01 = 0, s11 = 0;
        if (lod != null) {
            s00 = lod.spacing(ox, oz);
            s10 = lod.spacing(ox + ext, oz);
            s01 = lod.spacing(ox, oz + ext);
            s11 = lod.spacing(ox + ext, oz + ext);
        }
        float inv = n > 1 ? 1f / (n - 1) : 0f;
        int used = 0;
        for (int c = 0; c < w.count; c++) {
            boolean partial = false;
            if (lod != null) {
                double lambda = w.wavelength(c);
                cw[0] = lod.weight(lambda, s00);
                cw[1] = lod.weight(lambda, s10);
                cw[2] = lod.weight(lambda, s01);
                cw[3] = lod.weight(lambda, s11);
                if (cw[0] == 0 && cw[1] == 0 && cw[2] == 0 && cw[3] == 0) {
                    break;
                }
                partial = cw[0] != 1 || cw[1] != 1 || cw[2] != 1 || cw[3] != 1;
            }
            used = c + 1;
            phasor(c, ox, oz, t, ph);
            // a borda começa um espaçamento antes: × e^{−i(kₓ+k_z)s} = conj(Tx[1])·conj(Tz[1]), da própria tabela
            int b1 = c * m + 1;
            float shr = txr[b1] * tzr[b1] - txi[b1] * tzi[b1], shi = -(txr[b1] * tzi[b1] + txi[b1] * tzr[b1]);
            float br = (float) (ph[0] * shr - ph[1] * shi), bi = (float) (ph[0] * shi + ph[1] * shr);
            double a = w.amplitude[c], qa = w.q[c] * a;
            float ca = (float) a, cdx = (float) (-qa * w.dirX[c]), cdz = (float) (-qa * w.dirZ[c]);
            int base = c * m;
            for (int j = 0; j < m; j++) {
                float rr = br * tzr[base + j] - bi * tzi[base + j];
                float ri = br * tzi[base + j] + bi * tzr[base + j];
                int row = j * m;
                if (!partial) {
                    for (int i = 0; i < m; i++) {
                        float co = rr * txr[base + i] - ri * txi[base + i];
                        float si = rr * txi[base + i] + ri * txr[base + i];
                        int q = row + i;
                        eta[q] += ca * co;
                        ddx[q] += cdx * si;
                        ddz[q] += cdz * si;
                    }
                } else {
                    // o peso bilinear dos cantos do pedaço, estendido linearmente à borda (lá pode passar um pouco de
                    // [0, 1]: a borda só entra nas diferenças das normais, e a extrapolação é suave)
                    float v = (j - 1) * inv;
                    float wl = (float) (cw[0] + (cw[2] - cw[0]) * v);
                    float wr = (float) (cw[1] + (cw[3] - cw[1]) * v);
                    float dw = (wr - wl) * inv;
                    wl -= dw;
                    for (int i = 0; i < m; i++) {
                        float wt = wl + dw * i;
                        float co = wt * (rr * txr[base + i] - ri * txi[base + i]);
                        float si = wt * (rr * txi[base + i] + ri * txr[base + i]);
                        int q = row + i;
                        eta[q] += ca * co;
                        ddx[q] += cdx * si;
                        ddz[q] += cdz * si;
                    }
                }
            }
        }
        out.components = used;
        if (px.length < len) {
            px = new float[len];
            py = new float[len];
            pz = new float[len];
        }
        boolean uniform = att == null || att.uniform();
        float mean = (float) w.meanLevel, sp = (float) spacing;
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < m; i++) {
                int q = j * m + i;
                float a = 1;
                if (!uniform) {
                    a = (float) att.at(bx + i * spacing, bz + j * spacing);
                    a = a < 0 ? 0 : a > 1 ? 1 : a;
                }
                px[q] = (i - 1) * sp + a * ddx[q];
                pz[q] = (j - 1) * sp + a * ddz[q];
                py[q] = a * eta[q] + a * a * mean;
            }
        }
        float fs = foamStart, ff = foamFull, inv4 = 1f / (4 * sp * sp);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int q = (j + 1) * m + (i + 1), o = j * n + i;
                out.x[o] = px[q];
                out.y[o] = py[q];
                out.z[o] = pz[q];
                float ax = px[q + 1] - px[q - 1], ay = py[q + 1] - py[q - 1], az = pz[q + 1] - pz[q - 1];
                float bxx = px[q + m] - px[q - m], by = py[q + m] - py[q - m], bzz = pz[q + m] - pz[q - m];
                // N = T_z × T_x
                float nx = by * az - bzz * ay, ny = bzz * ax - bxx * az, nz = bxx * ay - by * ax;
                float il = (float) (1 / Math.sqrt(nx * nx + ny * ny + nz * nz));
                out.nx[o] = nx * il;
                out.ny[o] = ny * il;
                out.nz[o] = nz * il;
                float jac = ny * inv4;
                float f = (fs - jac) / (fs - ff);
                out.foam[o] = f <= 0 ? 0 : f >= 1 ? 1 : f;
            }
        }
    }

    private void compose(double ox, double oz, double spacing, int n, Attenuation att, GridOutput out) {
        boolean uniform = att == null || att.uniform();
        float mean = (float) waves.meanLevel;
        float fs = foamStart, ff = foamFull;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int q = j * n + i;
                float a = 1, ax = 0, az = 0;
                if (!uniform) {
                    double wx = ox + i * spacing, wz = oz + j * spacing;
                    a = (float) att.at(wx, wz);
                    if (a > 0 && a < 1) {
                        att.gradient(wx, wz, grad);
                        ax = (float) grad[0];
                        az = (float) grad[1];
                    } else if (a <= 0) {
                        a = 0;
                    }
                }
                float e = eta[q], dx = ddx[q], dz = ddz[q];
                out.x[q] = i * (float) spacing + a * dx;
                out.z[q] = j * (float) spacing + a * dz;
                out.y[q] = a * e + a * a * mean;
                float jxx = a * dxx[q] + ax * dx;
                float jxz = a * dxz[q] + 0.5f * (az * dx + ax * dz);
                float jzz = a * dzz[q] + az * dz;
                float hx = a * dhx[q] + ax * e, hz = a * dhz[q] + az * e;
                // N = T_z × T_x, T_x = (1 + jxx, hx, jxz), T_z = (jxz, hz, 1 + jzz)
                float tx0 = 1 + jxx, tx2 = jxz, tz0 = jxz, tz2 = 1 + jzz;
                float nx = hz * tx2 - tz2 * hx;
                float ny = tz2 * tx0 - tz0 * tx2;
                float nz = tz0 * hx - hz * tx0;
                float inv = (float) (1 / Math.sqrt(nx * nx + ny * ny + nz * nz));
                out.nx[q] = nx * inv;
                out.ny[q] = ny * inv;
                out.nz[q] = nz * inv;
                float jac = tx0 * tz2 - jxz * jxz;
                float f = (fs - jac) / (fs - ff);
                out.foam[q] = f <= 0 ? 0 : f >= 1 ? 1 : f;
            }
        }
    }
}
