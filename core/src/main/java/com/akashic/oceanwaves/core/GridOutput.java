package com.akashic.oceanwaves.core;

/**
 * Uma grade de vértices da superfície: n × n, linha a linha (índice = j·n + i, i ao longo de x). Posições relativas à
 * origem da grade e ao nível do mar em repouso, em float (a origem fica perto, a precisão sobra).
 */
public final class GridOutput {

    public int n;
    public float[] x = new float[0];
    public float[] y = new float[0];
    public float[] z = new float[0];
    public float[] nx = new float[0];
    public float[] ny = new float[0];
    public float[] nz = new float[0];
    /** 0 a 1: espuma pela compressão da superfície nas cristas (jacobiano). */
    public float[] foam = new float[0];
    /** Quantas componentes entraram nesta grade. */
    public int components;

    public void ensure(int size) {
        n = size;
        int len = size * size;
        if (x.length < len) {
            x = new float[len];
            y = new float[len];
            z = new float[len];
            nx = new float[len];
            ny = new float[len];
            nz = new float[len];
            foam = new float[len];
        }
    }
}
