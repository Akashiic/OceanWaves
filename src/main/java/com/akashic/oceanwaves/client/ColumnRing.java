package com.akashic.oceanwaves.client;

/**
 * Os chunks que o cliente guarda, num anel indexado pela coordenada (módulo o lado): achar um chunk é uma conta de
 * índice, sem chave encaixotada, sem objeto novo por consulta. O lado cobre o diâmetro do que se guarda (o alcance de
 * renderização mais a folga do {@link #prune}), então dois chunks vivos nunca caem no mesmo lugar; um chunk longe
 * demais para isso, se houver, é só esquecido. Sem Minecraft, para testar. Só a thread do cliente.
 */
final class ColumnRing {

    /** Chunks além de {@code raio + FOLGA} do centro são esquecidos. */
    static final int SLACK = 2;

    private OceanChunks.Column[] slots = new OceanChunks.Column[0];
    private int side;
    private int count;

    /** O anel de um alcance (em chunks): cresce se preciso (nunca encolhe) e refaz a colocação do que já tem. */
    void ensure(int radius) {
        int wanted = 2 * (radius + SLACK) + 3;
        if (wanted <= side) {
            return;
        }
        OceanChunks.Column[] old = slots;
        slots = new OceanChunks.Column[wanted * wanted];
        side = wanted;
        count = 0;
        for (OceanChunks.Column c : old) {
            if (c != null) {
                put(c);
            }
        }
    }

    private int index(int cx, int cz) {
        return Math.floorMod(cz, side) * side + Math.floorMod(cx, side);
    }

    private void put(OceanChunks.Column c) {
        int i = index(c.cx, c.cz);
        if (slots[i] == null) {
            count++;
        }
        slots[i] = c;
    }

    /** O chunk, ou null se não está guardado. */
    OceanChunks.Column peek(int cx, int cz) {
        if (side == 0) {
            return null;
        }
        OceanChunks.Column c = slots[index(cx, cz)];
        return c != null && c.cx == cx && c.cz == cz ? c : null;
    }

    /** O chunk, criado se não estava (e {@link #ensure} já foi chamado). */
    OceanChunks.Column get(int cx, int cz) {
        OceanChunks.Column c = peek(cx, cz);
        if (c == null) {
            if (side == 0) {
                ensure(1);
            }
            c = new OceanChunks.Column(cx, cz);
            put(c);
        }
        return c;
    }

    /** Esquece o que ficou longe do centro. */
    void prune(int ccx, int ccz, int radius) {
        int max = radius + SLACK;
        for (int i = 0; i < slots.length; i++) {
            OceanChunks.Column c = slots[i];
            if (c != null && (Math.abs(c.cx - ccx) > max || Math.abs(c.cz - ccz) > max)) {
                slots[i] = null;
                count--;
            }
        }
    }

    /**
     * A costa do chunk (cx, cz) e dos vizinhos a até {@code reach} chunks (a janela da costa vai até 2) depende do que
     * mudou nele: sobe a geração da costa de cada um que está guardado.
     */
    void touchShores(int cx, int cz, int reach) {
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                OceanChunks.Column n = peek(cx + dx, cz + dz);
                if (n != null) {
                    n.shoreGen++;
                }
            }
        }
    }

    void clear() {
        java.util.Arrays.fill(slots, null);
        count = 0;
    }

    int size() {
        return count;
    }

    /** O número de lugares do anel (para percorrer com {@link #at}). */
    int capacity() {
        return slots.length;
    }

    /** O chunk no lugar i, ou null. */
    OceanChunks.Column at(int slot) {
        return slots[slot];
    }
}
