package com.akashic.oceanwaves.client;

import com.akashic.oceanwaves.core.Attenuation;
import com.akashic.oceanwaves.core.SeaRegion;
import com.akashic.oceanwaves.core.ShoreField;
import com.akashic.oceanwaves.core.WaveSet;

/**
 * A atenuação da costa de um chunk, das colunas dele e das dos vizinhos (a folga em volta). Sem Minecraft, para testar
 * com chunks sintéticos. Uma instância só (guarda os vetores da janela, reaproveitados).
 *
 * <p>
 * A janela é lida chunk a chunk (cópia de trechos de linha), não coluna a coluna por uma busca no mapa. Um vizinho que
 * não chegou conta como mar (menos fora da região, onde a borda é costa). Quando a janela inteira é mar aberto e fundo
 * o bastante para a onda não quebrar, a conta toda dá 1 em toda parte e nem é feita: devolve
 * {@link Attenuation#OPEN_SEA}
 * (o caminho uniforme do desenho). Nunca devolve null.
 */
final class ShoreWindow {

    /** Até onde a folga vai: 2 chunks (é também a distância a que uma mudança marca os vizinhos). */
    static final int MAX_MARGIN = 32;

    private static final int MAX_SIZE = 16 + 2 * MAX_MARGIN;
    private static final int TABLE = MAX_MARGIN / 16 * 2 + 1;

    private final boolean[] open = new boolean[MAX_SIZE * MAX_SIZE];
    private final float[] depth = new float[MAX_SIZE * MAX_SIZE];
    private final OceanChunks.Column[] table = new OceanChunks.Column[TABLE * TABLE];

    /** A folga em volta do chunk: tem de cobrir o alcance inteiro da atenuação (a costa e o limite de gradiente). */
    static int margin(double shoreWidth, WaveSet waves) {
        int m = (int) Math.ceil(ShoreField.influenceRadius(shoreWidth, waves.lipschitz, waves.maxDisplacement)) + 1;
        return Math.min(m, MAX_MARGIN);
    }

    Attenuation build(ColumnRing ring, OceanChunks.Column c, WaveSet waves, double shoreWidth, double breakerIndex,
        SeaRegion region) {
        int m = margin(shoreWidth, waves);
        int size = 16 + 2 * m;
        int ox = (c.cx << 4) - m, oz = (c.cz << 4) - m;
        int cx0 = ox >> 4, cz0 = oz >> 4;
        int nx = ((ox + size - 1) >> 4) - cx0 + 1, nz = ((oz + size - 1) >> 4) - cz0 + 1;
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                OceanChunks.Column n = ring.peek(cx0 + i, cz0 + j);
                table[j * TABLE + i] = n != null && n.loaded ? n : null;
            }
        }
        double hs = Math.max(0.01, waves.significantHeight);
        // fundo mínimo para a onda não quebrar em lugar nenhum da janela: γ·d/Hs ≥ 1
        float deepEnough = (float) (hs / breakerIndex);
        boolean allOpen = true;
        float minDepth = Float.MAX_VALUE;
        for (int z = 0; z < size; z++) {
            int wz = oz + z;
            int rowBase = (((wz >> 4) - cz0) * TABLE), lz = wz & 15;
            int x = 0;
            while (x < size) {
                int wx = ox + x;
                int lx = wx & 15;
                int run = Math.min(size - x, 16 - lx);
                OceanChunks.Column n = table[rowBase + ((wx >> 4) - cx0)];
                int dst = z * size + x;
                if (n == null) {
                    // desconhecido conta como mar, menos fora da região (a borda dela é costa)
                    for (int k = 0; k < run; k++) {
                        boolean o = region.containsColumn(wx + k, wz);
                        open[dst + k] = o;
                        depth[dst + k] = 64;
                        allOpen &= o;
                    }
                    minDepth = Math.min(minDepth, 64);
                } else {
                    System.arraycopy(n.open, lz * 16 + lx, open, dst, run);
                    System.arraycopy(n.depth, lz * 16 + lx, depth, dst, run);
                    for (int k = 0; k < run; k++) {
                        allOpen &= open[dst + k];
                        minDepth = Math.min(minDepth, depth[dst + k]);
                    }
                }
                x += run;
            }
        }
        if (allOpen && minDepth >= deepEnough) {
            return Attenuation.OPEN_SEA;
        }
        ShoreField f = ShoreField.build(
            ox,
            oz,
            size,
            size,
            open,
            depth,
            shoreWidth,
            breakerIndex,
            waves.significantHeight,
            waves.lipschitz,
            waves.maxDisplacement);
        return f.uniform() ? Attenuation.OPEN_SEA : f;
    }
}
