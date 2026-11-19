package com.akashic.oceanwaves.core;

/**
 * A atenuação das ondas perto da costa, calculada das colunas de água de uma região retangular do mundo.
 *
 * <p>Os valores ficam nos cantos dos blocos (uma rede de (w+1) × (h+1)), e entre eles a interpolação é bilinear.
 * Um canto encostado em qualquer coluna que não é mar aberto (terra, gelo, água corrente) é bloqueado; a distância
 * euclidiana exata de cada canto ao canto bloqueado mais próximo (transformada de Felzenszwalb e Huttenlocher, 2012)
 * dá o fator de costa smoothstep(0, largura, d). Ao longo de toda aresta de uma coluna de terra os dois cantos são
 * bloqueados, então a superfície encontra a costa exatamente no nível da água parada, sem fresta e sem atravessar
 * o bloco.
 *
 * <p>O fator de fundo limita a altura pela profundidade: em água rasa a onda quebra quando H passa de γ·h
 * (critério de McCowan, γ ≈ 0,78). Com d a profundidade e Hs a altura significativa do mar, o fator é
 * f(γ·d/Hs), f(u) = 1 − (1 − u)² até 1 (suave, sem quina).
 *
 * <p>Servidor e cliente rodam a mesma conta sobre os mesmos blocos: a física e o desenho concordam.
 */
public final class ShoreField implements Attenuation {

    private static final float INF = 1e20f;

    public final int originX;
    public final int originZ;
    public final int width;
    public final int height;
    /** (w+1) × (h+1) valores nos cantos, linha a linha. */
    private final float[] corner;

    private ShoreField(int originX, int originZ, int width, int height, float[] corner) {
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.height = height;
        this.corner = corner;
    }

    /**
     * @param open  por coluna (índice z·w + x): é mar aberto (água parada no nível do mar, com céu)
     * @param depth por coluna: profundidade da água em blocos (ignorada onde não é mar)
     * @param shoreWidth largura da faixa em que as ondas morrem, em blocos
     * @param breakerIndex γ do critério de quebra (0,78)
     * @param significantHeight Hs do mar, m
     */
    public static ShoreField build(int originX, int originZ, int w, int h, boolean[] open, float[] depth,
        double shoreWidth, double breakerIndex, double significantHeight) {
        int cw = w + 1, ch = h + 1;
        float[] f = new float[cw * ch];
        float[] d = new float[cw * ch];
        for (int z = 0; z < ch; z++) {
            for (int x = 0; x < cw; x++) {
                boolean blocked = false;
                float sum = 0;
                int cnt = 0;
                for (int dz = -1; dz <= 0; dz++) {
                    for (int dx = -1; dx <= 0; dx++) {
                        int cx = x + dx, cz = z + dz;
                        if (cx < 0 || cz < 0 || cx >= w || cz >= h) {
                            continue; // fora da região: desconhecido, não bloqueia
                        }
                        int i = cz * w + cx;
                        if (!open[i]) {
                            blocked = true;
                        } else {
                            sum += depth[i];
                            cnt++;
                        }
                    }
                }
                f[z * cw + x] = blocked ? 0 : INF;
                d[z * cw + x] = cnt > 0 ? sum / cnt : 0;
            }
        }
        edt(f, cw, ch);
        float[] out = new float[cw * ch];
        double hs = Math.max(0.01, significantHeight);
        for (int i = 0; i < out.length; i++) {
            if (f[i] == 0) {
                out[i] = 0;
                continue;
            }
            double dist = Math.sqrt(f[i]);
            double u = Math.min(1, dist / shoreWidth);
            double shore = u * u * (3 - 2 * u);
            double v = Math.min(1, breakerIndex * d[i] / hs);
            double bottom = 1 - (1 - v) * (1 - v);
            out[i] = (float) (shore * bottom);
        }
        return new ShoreField(originX, originZ, w, h, out);
    }

    /** Transformada de distância euclidiana ao quadrado, separável (linhas e depois colunas). */
    static void edt(float[] f, int w, int h) {
        int n = Math.max(w, h);
        double[] line = new double[n], res = new double[n], zz = new double[n + 1];
        int[] v = new int[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                line[x] = f[y * w + x];
            }
            edt1(line, w, res, v, zz);
            for (int x = 0; x < w; x++) {
                f[y * w + x] = (float) Math.min(INF, res[x]);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                line[y] = f[y * w + x];
            }
            edt1(line, h, res, v, zz);
            for (int y = 0; y < h; y++) {
                f[y * w + x] = (float) Math.min(INF, res[y]);
            }
        }
    }

    /** Felzenszwalb e Huttenlocher, "Distance Transforms of Sampled Functions" (2012), algoritmo 1. */
    private static void edt1(double[] f, int n, double[] d, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            double dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }

    /** O valor num canto de bloco (coordenadas de mundo inteiras). Fora da região: 0. */
    public float cornerAt(int x, int z) {
        int cx = x - originX, cz = z - originZ;
        if (cx < 0 || cz < 0 || cx > width || cz > height) {
            return 0;
        }
        return corner[cz * (width + 1) + cx];
    }

    @Override
    public double at(double x, double z) {
        double fx = x - originX, fz = z - originZ;
        if (fx < 0 || fz < 0 || fx > width || fz > height) {
            return 0;
        }
        int ix = Math.min((int) fx, width - 1), iz = Math.min((int) fz, height - 1);
        double u = fx - ix, v = fz - iz;
        int cw = width + 1, i = iz * cw + ix;
        double a = corner[i], b = corner[i + 1], c = corner[i + cw], d = corner[i + cw + 1];
        return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
    }

    @Override
    public void gradient(double x, double z, double[] out) {
        double fx = x - originX, fz = z - originZ;
        if (fx < 0 || fz < 0 || fx > width || fz > height) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        int ix = Math.min((int) fx, width - 1), iz = Math.min((int) fz, height - 1);
        double u = fx - ix, v = fz - iz;
        int cw = width + 1, i = iz * cw + ix;
        double a = corner[i], b = corner[i + 1], c = corner[i + cw], d = corner[i + cw + 1];
        out[0] = (b - a) * (1 - v) + (d - c) * v;
        out[1] = (c - a) * (1 - u) + (d - b) * u;
    }
}
