package com.akashic.oceanwaves.core;

/**
 * A relação de dispersão das ondas de gravidade: ω² = g·k·tanh(k·h). Em água funda (k·h grande) vira ω² = g·k.
 * Unidades do mundo: 1 bloco = 1 m, tempo em segundos.
 */
public final class Dispersion {

    public static final double G = 9.81;

    private Dispersion() {}

    /** Frequência angular (rad/s) de uma onda de número {@code k} (rad/m) em água de profundidade {@code depth} (m). */
    public static double omega(double k, double depth) {
        if (Double.isInfinite(depth) || k * depth > 20) {
            return StrictMath.sqrt(G * k);
        }
        return StrictMath.sqrt(G * k * StrictMath.tanh(k * depth));
    }

    /**
     * O número de onda que tem a frequência {@code omega} na profundidade {@code depth}: resolve ω² = g·k·tanh(k·h) por
     * Newton a partir da solução de água funda (a função é crescente e côncava em k, Newton converge sem oscilar).
     */
    public static double k(double omega, double depth) {
        double deep = omega * omega / G;
        if (Double.isInfinite(depth) || deep * depth > 20) {
            return deep;
        }
        // chute de Fenton e McKee (1990), já com erro menor que 2%
        double k = deep / StrictMath.pow(StrictMath.tanh(StrictMath.pow(deep * depth, 0.75)), 2.0 / 3.0);
        for (int i = 0; i < 30; i++) {
            double th = StrictMath.tanh(k * depth);
            double f = G * k * th - omega * omega;
            double df = G * th + G * k * depth * (1 - th * th);
            double step = f / df;
            k -= step;
            if (Math.abs(step) < 1e-14 * k) {
                break;
            }
        }
        return k;
    }
}
