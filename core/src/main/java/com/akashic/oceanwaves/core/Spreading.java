package com.akashic.oceanwaves.core;

/**
 * Espalhamento direcional de Donelan, Hamilton e Hui (1985), com a extensão de Banner (1990) para frequências altas:
 * D(θ) = β / (2·tanh(βπ)) · sech²(βθ), θ medido a partir da direção do vento, em [−π, π]. Integra a 1 em θ.
 *
 * <p>A vantagem sobre o cos^2s de Mitsuyasu: a distribuição acumulada tem inversa fechada, então dá para sortear a
 * direção de cada componente exatamente (amostragem por importância) em vez de pesar componentes de direções fixas.
 */
public final class Spreading {

    private Spreading() {}

    /** β em função de ω/ω_p. */
    public static double beta(double omega, double peakOmega) {
        double r = omega / peakOmega;
        if (r < 0.95) {
            // 2,61·r^1,3 vale de 0,56 a 0,95; abaixo de 0,56 mantém-se o valor de 0,56 (ondas longas bem alinhadas)
            return 2.61 * StrictMath.pow(Math.max(r, 0.56), 1.3);
        }
        if (r < 1.6) {
            return 2.28 * StrictMath.pow(r, -1.3);
        }
        double e = -0.4 + 0.8393 * StrictMath.exp(-0.567 * StrictMath.log(r * r));
        return StrictMath.pow(10, e);
    }

    public static double density(double theta, double beta) {
        double c = StrictMath.cosh(beta * theta);
        return beta / (2 * StrictMath.tanh(beta * Math.PI)) / (c * c);
    }

    /**
     * O ângulo θ cuja probabilidade acumulada é {@code u} ∈ [0, 1]: θ = atanh((2u − 1)·tanh(βπ)) / β.
     */
    public static double inverse(double u, double beta) {
        double y = (2 * u - 1) * StrictMath.tanh(beta * Math.PI);
        // atanh(y) = ½·ln((1+y)/(1−y))
        return 0.5 * StrictMath.log((1 + y) / (1 - y)) / beta;
    }
}
