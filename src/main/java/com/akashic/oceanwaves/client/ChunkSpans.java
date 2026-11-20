package com.akashic.oceanwaves.client;

/**
 * Onde, no buffer de vértices de um passe, começa e termina a malha de cada chunk desenhado. O vetor é reaproveitado
 * de um quadro para o outro e só cresce, então o seu comprimento NÃO é o número de chunks do passe: quem percorre os
 * chunks usa {@link #count()}. (Foi o que quebrou uma vez: o laço de envio ia até o fim do vetor, e com a contagem de
 * chunks visíveis caindo, ou o vetor dobrando, lia chunks que não existem e desligava o mar.) Sem Minecraft.
 */
final class ChunkSpans {

    private int[] starts = new int[2];
    private int count;

    /** Um passe novo com {@code chunks} chunks: garante a capacidade (dobrando, sem alocar enquanto couber). */
    void reset(int chunks) {
        if (starts.length < chunks + 1) {
            starts = new int[Math.max(chunks + 1, 2 * starts.length)];
        }
        count = chunks;
    }

    /** O chunk {@code i} começa em {@code offset} (o {@code size} do buffer antes de a malha dele ser escrita). */
    void begin(int i, int offset) {
        starts[i] = offset;
    }

    /** O fim do último chunk (o {@code size} do buffer depois de a última malha). */
    void end(int offset) {
        starts[count] = offset;
    }

    /** Quantos chunks tem este passe. */
    int count() {
        return count;
    }

    /** Onde a malha do chunk {@code i} começa, em floats. */
    int start(int i) {
        return starts[i];
    }

    /** Onde a malha do chunk {@code i} termina (exclusive), em floats: onde a do seguinte começa. */
    int stop(int i) {
        return starts[i + 1];
    }

    /** A capacidade do vetor (para os testes: ela pode passar da contagem). */
    int capacity() {
        return starts.length - 1;
    }
}
