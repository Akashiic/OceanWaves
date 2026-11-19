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
 * z), normal (x, y, z), espuma, a posição de repouso relativa à origem (u, v), para a textura acompanhar as
 * partículas da superfície, e a cor ({@link Shade}) como os bits de um float (−1, branco opaco, sem sombreador).
 */
public final class OceanMesher {

    public static final int FLOATS = 10;

    /**
     * A cor de um vértice (RGBA empacotado, 0xRRGGBBAA), calculada uma vez por vértice da grade em vez de uma vez por
     * canto de quad (3,5 vezes menos contas com células de 1 bloco). Posição relativa à origem do chunk.
     */
    public interface Shade {

        int shade(int column, float px, float py, float pz, float nx, float ny, float nz, float foam);
    }

    private int[] colors = new int[0];
    private Shade shader;
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
        return mesh(chunkX, chunkZ, spacing, null, open, t, lod, att, sink);
    }

    /**
     * @param neighbours o espaçamento dos quatro vizinhos (oeste −x, leste +x, norte −z, sul +z), ou null. Numa
     *                   aresta com vizinho mais grosso, os vértices que o vizinho não tem são postos na reta entre os
     *                   que ele tem: a junção em T fica exata, sem fresta.
     */
    public int mesh(int chunkX, int chunkZ, double spacing, double[] neighbours, boolean[] open, double t,
        SpectralLod lod, Attenuation att, FloatBuffer sink) {
        return mesh(chunkX, chunkZ, spacing, neighbours, open, t, lod, att, null, sink);
    }

    public int mesh(int chunkX, int chunkZ, double spacing, double[] neighbours, boolean[] open, double t,
        SpectralLod lod, Attenuation att, Shade shade, FloatBuffer sink) {
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
        if (neighbours != null) {
            stitch(n, spacing, neighbours[0], 0, n);
            stitch(n, spacing, neighbours[1], n - 1, n);
            stitch(n, spacing, neighbours[2], 0, 1);
            stitch(n, spacing, neighbours[3], (n - 1) * n, 1);
        }
        shader = shade;
        if (shade != null) {
            if (colors.length < n * n) {
                colors = new int[n * n];
            }
            for (int j = 0; j < n; j++) {
                int cz = Math.min(CHUNK - 1, (int) (j * spacing));
                for (int i = 0; i < n; i++) {
                    int q = j * n + i, cx = Math.min(CHUNK - 1, (int) (i * spacing));
                    colors[q] = shade
                        .shade(cz * CHUNK + cx, out.x[q], out.y[q], out.z[q], out.nx[q], out.ny[q], out.nz[q], out.foam[q]);
                }
            }
        }
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

    /** Os vértices da aresta (início {@code first}, passo {@code step}) que um vizinho de espaçamento maior não tem. */
    private void stitch(int n, double spacing, double neighbour, int first, int step) {
        int r = (int) Math.round(neighbour / spacing);
        if (r <= 1) {
            return;
        }
        for (int k = 0; k < n; k++) {
            int m = k % r;
            if (m == 0) {
                continue;
            }
            int a = first + (k - m) * step, b = first + (k - m + r) * step, q = first + k * step;
            float w = (float) m / r, u = 1 - w;
            out.x[q] = u * out.x[a] + w * out.x[b];
            out.y[q] = u * out.y[a] + w * out.y[b];
            out.z[q] = u * out.z[a] + w * out.z[b];
            float nx = u * out.nx[a] + w * out.nx[b], ny = u * out.ny[a] + w * out.ny[b],
                nz = u * out.nz[a] + w * out.nz[b];
            float il = (float) (1 / Math.sqrt(nx * nx + ny * ny + nz * nz));
            out.nx[q] = nx * il;
            out.ny[q] = ny * il;
            out.nz[q] = nz * il;
            out.foam[q] = u * out.foam[a] + w * out.foam[b];
        }
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
        d[k + 9] = Float.intBitsToFloat(shader == null ? -1 : colors[q]);
        b.size = k + FLOATS;
    }

    private static int mix(int c00, int c10, int c01, int c11, float w00, float w10, float w01, float w11) {
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            float v = w00 * (c00 >>> shift & 255) + w10 * (c10 >>> shift & 255) + w01 * (c01 >>> shift & 255)
                + w11 * (c11 >>> shift & 255);
            out |= Math.min(255, Math.round(v)) << shift;
        }
        return out;
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
        d[k + 9] = Float.intBitsToFloat(shader == null ? -1
            : mix(colors[q], colors[q10], colors[q01], colors[q11], w00, w10, w01, w11));
        b.size = k + FLOATS;
    }
}
