package com.akashic.oceanwaves.core;

/**
 * Transforma um chunk de mar em quads: avalia a grade da superfície ({@link GridEvaluator#evaluateFast}) no
 * espaçamento que a distância pede e emite uma célula onde as colunas por baixo são mar aberto.
 *
 * <p>Com células de até 1 bloco, cada célula fica dentro de uma coluna e sai ou não sai conforme a coluna. Com
 * células maiores (longe), uma célula inteira de mar sai como um quad; uma célula que cobre costa é dividida nas
 * colunas de mar, e os cantos dessas colunas saem da interpolação bilinear dos quatro vértices da célula. Assim a
 * borda do mar é exata (a face de cima da água vanilla some só nas colunas que o mod cobre) em qualquer distância.
 *
 * <p>Cada vértice tem {@link #FLOATS} floats: posição relativa à origem do chunk (x, y acima do nível de repouso,
 * z), normal (x, y, z), espuma, e a posição de repouso relativa à origem (u, v), para a textura acompanhar as
 * partículas da superfície.
 */
public final class OceanMesher {

    public static final int FLOATS = 9;
    public static final int CHUNK = 16;

    private final GridEvaluator grid;
    private final GridOutput out = new GridOutput();

    public OceanMesher(WaveSet waves) {
        this.grid = new GridEvaluator(waves);
    }

    public GridEvaluator grid() {
        return grid;
    }

    /** O espaçamento de grade (potência de 2 entre {@code min} e 16) para um chunk, pelo filtro de distância. */
    public static double spacingFor(SpectralLod lod, int chunkX, int chunkZ, double min) {
        double want = lod.spacingForBox(chunkX * CHUNK, chunkZ * CHUNK, chunkX * CHUNK + CHUNK, chunkZ * CHUNK + CHUNK);
        double s = min;
        while (s * 2 <= want && s < CHUNK) {
            s *= 2;
        }
        return s;
    }

    /**
     * @param open as 256 colunas do chunk (índice z·16 + x): verdadeiro onde é mar aberto
     * @return quantos quads foram escritos em {@code sink}
     */
    public int mesh(int chunkX, int chunkZ, double spacing, boolean[] open, double t, SpectralLod lod,
        Attenuation att, FloatBuffer sink) {
        int count = 0;
        for (boolean b : open) {
            if (b) {
                count++;
            }
        }
        if (count == 0) {
            return 0;
        }
        int cells = (int) Math.round(CHUNK / spacing);
        int n = cells + 1;
        grid.evaluateFast(chunkX * (double) CHUNK, chunkZ * (double) CHUNK, spacing, n, t, lod, att, out);
        int quads = 0;
        float s = (float) spacing;
        if (spacing <= 1) {
            int per = (int) Math.round(1 / spacing);
            for (int j = 0; j < cells; j++) {
                for (int i = 0; i < cells; i++) {
                    if (open[(j / per) * CHUNK + i / per]) {
                        sink.reserve(4 * FLOATS);
                        int q = j * n + i;
                        vertex(sink, q, i * s, j * s);
                        vertex(sink, q + n, i * s, (j + 1) * s);
                        vertex(sink, q + n + 1, (i + 1) * s, (j + 1) * s);
                        vertex(sink, q + 1, (i + 1) * s, j * s);
                        quads++;
                    }
                }
            }
            return quads;
        }
        int per = (int) Math.round(spacing);
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int full = 0;
                for (int b = 0; b < per; b++) {
                    for (int a = 0; a < per; a++) {
                        if (open[(j * per + b) * CHUNK + i * per + a]) {
                            full++;
                        }
                    }
                }
                if (full == 0) {
                    continue;
                }
                int q = j * n + i;
                if (full == per * per) {
                    sink.reserve(4 * FLOATS);
                    vertex(sink, q, i * s, j * s);
                    vertex(sink, q + n, i * s, (j + 1) * s);
                    vertex(sink, q + n + 1, (i + 1) * s, (j + 1) * s);
                    vertex(sink, q + 1, (i + 1) * s, j * s);
                    quads++;
                    continue;
                }
                for (int b = 0; b < per; b++) {
                    for (int a = 0; a < per; a++) {
                        if (!open[(j * per + b) * CHUNK + i * per + a]) {
                            continue;
                        }
                        float u0 = (float) a / per, u1 = (float) (a + 1) / per;
                        float v0 = (float) b / per, v1 = (float) (b + 1) / per;
                        sink.reserve(4 * FLOATS);
                        lerp(sink, q, n, u0, v0, i * s, j * s, s);
                        lerp(sink, q, n, u0, v1, i * s, j * s, s);
                        lerp(sink, q, n, u1, v1, i * s, j * s, s);
                        lerp(sink, q, n, u1, v0, i * s, j * s, s);
                        quads++;
                    }
                }
            }
        }
        return quads;
    }

    private void vertex(FloatBuffer b, int q, float u, float v) {
        float[] d = b.data;
        int k = b.size;
        d[k] = out.x[q];
        d[k + 1] = out.y[q];
        d[k + 2] = out.z[q];
        d[k + 3] = out.nx[q];
        d[k + 4] = out.ny[q];
        d[k + 5] = out.nz[q];
        d[k + 6] = out.foam[q];
        d[k + 7] = u;
        d[k + 8] = v;
        b.size = k + FLOATS;
    }

    /** Um ponto dentro da célula q (fração fu, fv), por interpolação bilinear dos quatro vértices. */
    private void lerp(FloatBuffer b, int q, int n, float fu, float fv, float cu, float cv, float s) {
        float w00 = (1 - fu) * (1 - fv), w10 = fu * (1 - fv), w01 = (1 - fu) * fv, w11 = fu * fv;
        int q10 = q + 1, q01 = q + n, q11 = q + n + 1;
        float[] d = b.data;
        int k = b.size;
        d[k] = w00 * out.x[q] + w10 * out.x[q10] + w01 * out.x[q01] + w11 * out.x[q11];
        d[k + 1] = w00 * out.y[q] + w10 * out.y[q10] + w01 * out.y[q01] + w11 * out.y[q11];
        d[k + 2] = w00 * out.z[q] + w10 * out.z[q10] + w01 * out.z[q01] + w11 * out.z[q11];
        float nx = w00 * out.nx[q] + w10 * out.nx[q10] + w01 * out.nx[q01] + w11 * out.nx[q11];
        float ny = w00 * out.ny[q] + w10 * out.ny[q10] + w01 * out.ny[q01] + w11 * out.ny[q11];
        float nz = w00 * out.nz[q] + w10 * out.nz[q10] + w01 * out.nz[q01] + w11 * out.nz[q11];
        float il = (float) (1 / Math.sqrt(nx * nx + ny * ny + nz * nz));
        d[k + 3] = nx * il;
        d[k + 4] = ny * il;
        d[k + 5] = nz * il;
        d[k + 6] = w00 * out.foam[q] + w10 * out.foam[q10] + w01 * out.foam[q01] + w11 * out.foam[q11];
        d[k + 7] = cu + fu * s;
        d[k + 8] = cv + fv * s;
        b.size = k + FLOATS;
    }
}
