package com.akashic.oceanwaves.core;

/**
 * Gerador pseudoaleatório SplitMix64 (Steele, Lea e Flood, 2014), escrito aqui para que a sequência seja a mesma em
 * qualquer JVM: o cliente roda em Java 8 e o servidor em Java 21, e o mar dos dois tem de nascer das mesmas ondas.
 * Não depende de {@code java.util.Random} nem de {@code SplittableRandom}, cujo algoritmo a especificação não fixa.
 */
public final class SplitMix64 {

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        long z = state += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniforme em [0, 1), com 53 bits. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
