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
    /** Todos os cantos valem exatamente 1: mar aberto e fundo, sem costa nenhuma na região. */
    private final boolean allOne;

    /** Valores a partir daqui contam como 1: o par log/exp do limite de gradiente devolve 0,99999994 em vez de 1. */
    private static final float ROUND_UP = 1 - 1e-6f;

    private ShoreField(int originX, int originZ, int width, int height, float[] corner) {
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.height = height;
        this.corner = corner;
        boolean one = true;
        for (float v : corner) {
            if (v != 1f) {
                one = false;
                break;
            }
        }
        this.allOne = one;
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
        return build(originX, originZ, w, h, open, depth, shoreWidth, breakerIndex, significantHeight, 0, 0);
    }

    /**
     * Com o mar ({@code lipschitz} L = Σ Qkа, {@code maxDisplacement} M = Σ Qa), a atenuação é suavizada só o
     * necessário para a superfície nunca dobrar na costa: ‖∇(A·D)‖ ≤ A·L + M·|∇A|, e basta |∇A| &lt; (1 − L·A)/M.
     * A condição é folgada onde A é pequeno (rente à costa) e aperta em mar aberto. Com B = −ln(1 − L·A)/L ela vira
     * |∇B| ≤ 1/M, uniforme, que um envelope de cone impõe exatamente (o maior campo abaixo de B com essa constante de
     * Lipschitz); aqui com folga de 1,5. Degraus de profundidade, quinas e faixas estreitas são suavizados, o resto
     * fica como estava. L = M = 0: sem esse passo.
     */
    public static ShoreField build(int originX, int originZ, int w, int h, boolean[] open, float[] depth,
        double shoreWidth, double breakerIndex, double significantHeight, double lipschitz, double maxDisplacement) {
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
        if (maxDisplacement > 0) {
            double l = Math.max(1e-6, Math.min(0.999, lipschitz));
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) (-Math.log(1 - l * out[i]) / l);
            }
            lipschitzClamp(out, cw, ch, 1 / (1.5 * maxDisplacement * Math.sqrt(2)));
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) Math.min(1, (1 - Math.exp(-l * out[i])) / l);
            }
        }
        // o que ficou a menos de 1e-6 do mar aberto vale 1 de verdade: a região só é "uniforme" (caminho barato do
        // GridEvaluator) se for exatamente 1, e dois pedaços vizinhos têm de concordar sobre isso na borda comum
        for (int i = 0; i < out.length; i++) {
            if (out[i] >= ROUND_UP) {
                out[i] = 1f;
            }
        }
        return new ShoreField(originX, originZ, w, h, out);
    }

    /**
     * A(p) ← min_q A(q) + g·|p − q| por duas passadas de chanfro (vizinhos ortogonais com g, diagonais com g√2): cada
     * aresta e cada diagonal da rede fica com diferença ≤ g e ≤ g√2. Na interpolação bilinear cada derivada parcial é
     * uma média de diferenças de aresta, então |∇A| ≤ g√2; quem chama passa g/√2 para o limite valer em norma
     * euclidiana. Só abaixa valores (nunca cria onda onde não havia).
     */
    static void lipschitzClamp(float[] a, int w, int h, double g) {
        float go = (float) g, gd = (float) (g * Math.sqrt(2));
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                float v = a[i];
                if (x > 0) {
                    v = Math.min(v, a[i - 1] + go);
                }
                if (z > 0) {
                    v = Math.min(v, a[i - w] + go);
                    if (x > 0) {
                        v = Math.min(v, a[i - w - 1] + gd);
                    }
                    if (x < w - 1) {
                        v = Math.min(v, a[i - w + 1] + gd);
                    }
                }
                a[i] = v;
            }
        }
        for (int z = h - 1; z >= 0; z--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = z * w + x;
                float v = a[i];
                if (x < w - 1) {
                    v = Math.min(v, a[i + 1] + go);
                }
                if (z < h - 1) {
                    v = Math.min(v, a[i + w] + go);
                    if (x < w - 1) {
                        v = Math.min(v, a[i + w + 1] + gd);
                    }
                    if (x > 0) {
                        v = Math.min(v, a[i + w - 1] + gd);
                    }
                }
                a[i] = v;
            }
        }
    }

    /**
     * Até onde um bloco influi na atenuação de outro, em blocos: a faixa de costa e o alcance do envelope de cone (o
     * maior valor de B, −ln(1 − L)/L, dividido pelo limite de gradiente por aresta). Quem calcula a atenuação por
     * pedaços (um chunk e uma folga em volta) precisa de uma folga pelo menos deste tamanho para que dois pedaços
     * vizinhos calculem os mesmos valores na borda comum; com folga menor a superfície abre uma fresta ali (visto no
     * jogo numa tempestade sobre o terreno real, na borda de um chunk).
     */
    public static double influenceRadius(double shoreWidth, double lipschitz, double maxDisplacement) {
        double r = shoreWidth;
        if (maxDisplacement > 0) {
            double l = Math.max(1e-6, Math.min(0.999, lipschitz));
            double bMax = -Math.log(1 - l) / l;
            double g = 1 / (1.5 * maxDisplacement * Math.sqrt(2));
            r = Math.max(r, bMax / g);
        }
        return r;
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

    /**
     * Sem costa na região (todos os cantos valem 1): quem avalia a superfície pode pular a atenuação, ponto a ponto,
     * e usar o caminho de mar aberto. Vale dentro da região; o que o desenho pede fica sempre dentro dela (o chunk e a
     * folga em volta). Fora, {@link #at} dá 0 e este caminho dá 1.
     */
    @Override
    public boolean uniform() {
        return allOne;
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
