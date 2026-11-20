package com.akashic.oceanwaves.client;

import com.akashic.oceanwaves.core.Attenuation;

/**
 * Refaz a atenuação da costa dos chunks que mudaram, com orçamento por quadro e sem abrir fresta. Sem Minecraft.
 *
 * <p>
 * O que muda de um quadro para o outro é pouco (um chunk chegou, um bloco foi posto), mas uma mudança marca os
 * 5 × 5 chunks em volta (a janela da costa vai até 2 chunks), e refazer todos de uma vez custa de 6 a 12 ms por chunk
 * que chega junto à costa. Aqui o trabalho é gasto aos poucos e a decisão de trocar é separada da de calcular:
 * <ol>
 * <li><b>Calcular</b>: do mais perto da câmera para o mais longe, até {@code budget} costas de verdade por quadro. As
 * janelas de mar aberto fundo saem como {@link Attenuation#OPEN_SEA} sem conta e não gastam orçamento (com um teto
 * por quadro). O resultado fica em {@code next}, sem mexer em {@code shore}, que é o que o desenho usa.</li>
 * <li><b>Trocar</b>: um chunk só passa a usar o {@code next} se, em toda aresta e canto que divide com um vizinho
 * desenhado, ele dá o mesmo valor que o vizinho dá (o {@code next} dele, se vai trocar junto, ou o {@code shore} que
 * ele usa). É a condição de não abrir fresta, conferida nos valores (não inferida): dois chunks que concordam na
 * borda comum têm os mesmos vértices ali. Os que discordam esperam os vizinhos serem calculados, e trocam juntos
 * quando concordam entre si (o maior conjunto consistente, por ponto fixo).</li>
 * </ol>
 * Um chunk que nunca teve costa ({@code shore == null}) não é desenhado até trocar. Se um chunk espera
 * {@link #FORCE_AFTER} quadros (os valores nunca concordam: erro numérico além da tolerância), troca assim mesmo, e
 * {@link #forced} conta.
 */
final class ShoreScheduler {

    /** Calcula a costa de um chunk (nunca null; {@link Attenuation#OPEN_SEA} quando não há conta a fazer). */
    interface Builder {

        Attenuation build(OceanChunks.Column c);
    }

    /** O quanto dois pedaços podem diferir na borda comum e ainda valer como a mesma superfície (blocos de onda). */
    static final double TOLERANCE = 1e-4;
    /** Teto de janelas de mar aberto (sem conta) por quadro: elas custam uma cópia de janela, não a costa. */
    static final int CHEAP_CAP = 256;
    /** Quadros que um chunk espera a concordância dos vizinhos antes de trocar assim mesmo. */
    static final int FORCE_AFTER = 240;

    /** Quantos chunks foram trocados à força (diagnóstico). */
    int forced;
    /** Costas de verdade calculadas no último {@link #update}. */
    int lastBuilt;
    /** Janelas de mar aberto resolvidas sem conta no último {@link #update}. */
    int lastOpenSea;
    /** Chunks com costa calculada esperando os vizinhos, no fim do último {@link #update}. */
    int lastWaiting;

    private OceanChunks.Column[] work = new OceanChunks.Column[0];
    private double[] key = new double[0];

    /** Precisa de costa nova? (tem mar, e a costa pronta não é a da geração atual). */
    private static boolean needsBuild(OceanChunks.Column c) {
        return c.any && c.builtGen != c.shoreGen;
    }

    /** Calculada para a geração atual e ainda não em uso. */
    private static boolean pending(OceanChunks.Column c) {
        return c.any && c.next != null && c.builtGen == c.shoreGen && c.shore != c.next;
    }

    /**
     * @param camCx  o chunk da câmera (a ordem do mais perto)
     * @param budget costas de verdade por quadro (pelo menos 1)
     */
    void update(ColumnRing ring, Builder builder, int camCx, int camCz, int budget) {
        lastBuilt = 0;
        lastOpenSea = 0;
        int cap = ring.capacity();
        if (work.length < cap) {
            work = new OceanChunks.Column[cap];
            key = new double[cap];
        }
        // 1. calcular
        int n = 0;
        for (int i = 0; i < cap; i++) {
            OceanChunks.Column c = ring.at(i);
            if (c != null && needsBuild(c)) {
                double dx = c.cx - camCx, dz = c.cz - camCz;
                insert(n++, c, dx * dx + dz * dz);
            }
        }
        int limit = Math.max(1, budget);
        for (int i = 0; i < n && lastBuilt < limit; i++) {
            OceanChunks.Column c = work[i];
            Attenuation a = builder.build(c);
            c.next = a;
            c.builtGen = c.shoreGen;
            c.waited = 0;
            if (a == Attenuation.OPEN_SEA) {
                if (++lastOpenSea >= CHEAP_CAP) {
                    break;
                }
            } else {
                lastBuilt++;
            }
        }
        // 2. trocar
        int p = 0;
        for (int i = 0; i < cap; i++) {
            OceanChunks.Column c = ring.at(i);
            if (c != null && pending(c)) {
                c.ready = true;
                c.publishOk = true;
                work[p++] = c;
            }
        }
        if (p == 0) {
            lastWaiting = 0;
            return;
        }
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < p; i++) {
                OceanChunks.Column c = work[i];
                if (c.publishOk && !agreesWithNeighbours(ring, c)) {
                    c.publishOk = false;
                    changed = true;
                }
            }
        } while (changed);
        int waiting = 0;
        for (int i = 0; i < p; i++) {
            OceanChunks.Column c = work[i];
            if (c.publishOk) {
                c.shore = c.next;
                c.waited = 0;
            } else if (++c.waited >= FORCE_AFTER) {
                c.shore = c.next;
                c.waited = 0;
                forced++;
            } else {
                waiting++;
            }
        }
        for (int i = 0; i < p; i++) {
            work[i].ready = false;
            work[i].publishOk = false;
        }
        lastWaiting = waiting;
    }

    /** Insere por inserção ordenada (poucos elementos, quase sempre nenhum). */
    private void insert(int at, OceanChunks.Column c, double d) {
        int i = at;
        while (i > 0 && key[i - 1] > d) {
            work[i] = work[i - 1];
            key[i] = key[i - 1];
            i--;
        }
        work[i] = c;
        key[i] = d;
    }

    /** A atenuação de {@code y} que o desenho verá depois desta troca, ou null se ele não é desenhado. */
    private static Attenuation seenBy(OceanChunks.Column y) {
        return y.ready && y.publishOk ? y.next : y.shore;
    }

    private static boolean agreesWithNeighbours(ColumnRing ring, OceanChunks.Column c) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                OceanChunks.Column y = ring.peek(c.cx + dx, c.cz + dz);
                if (y == null || !y.any) {
                    continue;
                }
                Attenuation other = seenBy(y);
                if (other != null && !agree(c.next, other, c.cx << 4, c.cz << 4, dx, dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Os dois dão o mesmo valor em todo canto da aresta (ou do canto) que o chunk (x0, z0) divide no sentido (dx, dz)?
     */
    static boolean agree(Attenuation a, Attenuation b, int x0, int z0, int dx, int dz) {
        if (a == b) {
            return true;
        }
        if (dx != 0 && dz != 0) {
            int x = x0 + (dx > 0 ? 16 : 0), z = z0 + (dz > 0 ? 16 : 0);
            return Math.abs(a.at(x, z) - b.at(x, z)) <= TOLERANCE;
        }
        if (dx != 0) {
            int x = x0 + (dx > 0 ? 16 : 0);
            for (int k = 0; k <= 16; k++) {
                if (Math.abs(a.at(x, z0 + k) - b.at(x, z0 + k)) > TOLERANCE) {
                    return false;
                }
            }
            return true;
        }
        int z = z0 + (dz > 0 ? 16 : 0);
        for (int k = 0; k <= 16; k++) {
            if (Math.abs(a.at(x0 + k, z) - b.at(x0 + k, z)) > TOLERANCE) {
                return false;
            }
        }
        return true;
    }
}
