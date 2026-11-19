package com.akashic.oceanwaves.core;

import java.util.Arrays;

/** Um vetor de floats que cresce, reutilizado de quadro a quadro (sem lixo para o coletor). */
public final class FloatBuffer {

    public float[] data = new float[4096];
    public int size;

    public void clear() {
        size = 0;
    }

    public void reserve(int more) {
        if (size + more > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, size + more));
        }
    }

    public void put(float v) {
        data[size++] = v;
    }
}
