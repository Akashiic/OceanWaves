package com.akashic.oceanwaves.core;

/**
 * O quanto do mar aberto chega a um ponto: 1 em mar aberto e fundo, 0 encostado na costa ou em água que não é mar
 * (rio, cascata, gelo). Multiplica amplitudes e deslocamentos de todas as componentes, então a superfície encontra
 * a costa exatamente no nível da água parada. Ver {@link ShoreField}.
 */
public interface Attenuation {

    Attenuation OPEN_SEA = (x, z) -> 1.0;

    double at(double x, double z);

    /** Gradiente por diferença central (h = 0,25 m); implementações com forma fechada podem sobrescrever. */
    default void gradient(double x, double z, double[] out) {
        double h = 0.25;
        out[0] = (at(x + h, z) - at(x - h, z)) / (2 * h);
        out[1] = (at(x, z + h) - at(x, z - h)) / (2 * h);
    }

    default boolean uniform() {
        return this == OPEN_SEA;
    }
}
