package com.akashic.oceanwaves.core;

/**
 * A superfície euleriana (altura num ponto do mundo) sobre um retângulo, para a física de um casco num tick.
 *
 * <p>Consultar {@link OceanField#eulerian} coluna a coluna custa, por coluna, algumas iterações de Newton vezes
 * todas as componentes. Aqui a grade lagrangiana (onde cada partícula de superfície foi parar) é avaliada uma vez,
 * com o laço de tabelas do {@link GridEvaluator}, sobre o retângulo alargado pelo maior deslocamento possível; cada
 * consulta inverte x₀ + D(x₀) = x por Newton sobre a interpolação bilinear de D (o jacobiano sai da própria célula),
 * sem trigonometria. Erro de interpolação ~ a·(k·s)²/8: com s = 0,5 m e ondas de 4 m ou mais, uns poucos centímetros
 * nas ondas mais curtas, e o casco, que integra muitas colunas, nem sente.
 *
 * <p>Uma instância por casco (ou por thread). Reavaliar a cada tick; os subpassos do tick usam a mesma grade (o mar
 * anda ~5 cm num tick de 50 ms) ou duas grades, em t e t + 1 tick, com interpolação no tempo ({@link #blend}).
 */
public final class HeightPatch {

    private static final int TILE = 16;

    private final GridEvaluator evaluator;
    private final GridOutput tile = new GridOutput();
    private double x0;
    private double z0;
    private double s;
    private int nx;
    private int nz;
    private float[] dx = new float[0], dz = new float[0], eta = new float[0];
    private double time = Double.NaN;

    public HeightPatch(WaveSet waves) {
        this.evaluator = new GridEvaluator(waves);
    }

    /** O instante da última avaliação. */
    public double time() {
        return time;
    }

    /**
     * Avalia a grade que cobre [minX, maxX] × [minZ, maxZ] (mais a margem do deslocamento) no instante t.
     */
    public void update(double minX, double minZ, double maxX, double maxZ, double spacing, double t, Attenuation att) {
        double margin = evaluator.waves().maxDisplacement + 2 * spacing;
        s = spacing;
        x0 = Math.floor((minX - margin) / spacing) * spacing;
        z0 = Math.floor((minZ - margin) / spacing) * spacing;
        int tx = (int) Math.ceil((maxX + margin - x0) / (spacing * TILE));
        int tz = (int) Math.ceil((maxZ + margin - z0) / (spacing * TILE));
        nx = tx * TILE + 1;
        nz = tz * TILE + 1;
        int len = nx * nz;
        if (eta.length < len) {
            dx = new float[len];
            dz = new float[len];
            eta = new float[len];
        }
        int n = TILE + 1;
        for (int j = 0; j < tz; j++) {
            for (int i = 0; i < tx; i++) {
                double ox = x0 + i * TILE * spacing, oz = z0 + j * TILE * spacing;
                evaluator.evaluateFast(ox, oz, spacing, n, t, null, att, tile);
                for (int b = 0; b < n; b++) {
                    for (int a = 0; a < n; a++) {
                        int src = b * n + a, dst = (j * TILE + b) * nx + i * TILE + a;
                        dx[dst] = (float) (tile.x[src] - a * spacing);
                        dz[dst] = (float) (tile.z[src] - b * spacing);
                        eta[dst] = tile.y[src];
                    }
                }
            }
        }
        time = t;
    }

    /**
     * A altura da superfície em (x, z), acima do nível do mar em repouso. Fora da grade: NaN.
     *
     * @param guess {x₀, z₀} de partida e, na volta, o ponto lagrangiano achado (pode ser null)
     */
    public double height(double x, double z, double[] guess) {
        double u = guess != null && !Double.isNaN(guess[0]) ? guess[0] : x;
        double v = guess != null && !Double.isNaN(guess[1]) ? guess[1] : z;
        double h = Double.NaN;
        for (int it = 0; it < 8; it++) {
            double fx = (u - x0) / s, fz = (v - z0) / s;
            if (fx < 0 || fz < 0 || fx >= nx - 1 || fz >= nz - 1) {
                return Double.NaN;
            }
            int i = (int) fx, j = (int) fz;
            double a = fx - i, b = fz - j;
            int q = j * nx + i;
            double d00x = dx[q], d10x = dx[q + 1], d01x = dx[q + nx], d11x = dx[q + nx + 1];
            double d00z = dz[q], d10z = dz[q + 1], d01z = dz[q + nx], d11z = dz[q + nx + 1];
            double ddx = (d00x * (1 - a) + d10x * a) * (1 - b) + (d01x * (1 - a) + d11x * a) * b;
            double ddz = (d00z * (1 - a) + d10z * a) * (1 - b) + (d01z * (1 - a) + d11z * a) * b;
            h = (eta[q] * (1 - a) + eta[q + 1] * a) * (1 - b) + (eta[q + nx] * (1 - a) + eta[q + nx + 1] * a) * b;
            double rx = u + ddx - x, rz = v + ddz - z;
            if (rx * rx + rz * rz < 1e-12) {
                break;
            }
            // jacobiano da interpolação bilinear na célula
            double jxx = 1 + ((d10x - d00x) * (1 - b) + (d11x - d01x) * b) / s;
            double jxz = ((d01x - d00x) * (1 - a) + (d11x - d10x) * a) / s;
            double jzx = ((d10z - d00z) * (1 - b) + (d11z - d01z) * b) / s;
            double jzz = 1 + ((d01z - d00z) * (1 - a) + (d11z - d10z) * a) / s;
            double det = jxx * jzz - jxz * jzx;
            if (det < 0.05) {
                u -= rx;
                v -= rz;
            } else {
                u -= (jzz * rx - jxz * rz) / det;
                v -= (jxx * rz - jzx * rx) / det;
            }
        }
        if (guess != null) {
            guess[0] = u;
            guess[1] = v;
        }
        return h;
    }

    /** Interpolação linear no tempo entre duas grades avaliadas em instantes diferentes, no mesmo retângulo. */
    public static double blend(HeightPatch a, HeightPatch b, double x, double z, double t) {
        double ha = a.height(x, z, null), hb = b.height(x, z, null);
        double span = b.time - a.time;
        double w = span == 0 ? 0 : Math.max(0, Math.min(1, (t - a.time) / span));
        return ha + (hb - ha) * w;
    }
}
