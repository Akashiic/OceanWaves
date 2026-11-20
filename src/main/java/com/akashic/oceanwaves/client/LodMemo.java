package com.akashic.oceanwaves.client;

import com.akashic.oceanwaves.core.SpectralLod;

/**
 * Guarda o último {@link SpectralLod} de um passe e o devolve enquanto os parâmetros forem os mesmos. A classe é
 * imutável (e do núcleo, que o desenho não muda), então com a câmera andando um objeto novo por passe não tem como ser
 * evitado (80 bytes); parado, o que é comum (pescar, construir, esperar), nada é alocado. Sem Minecraft.
 */
final class LodMemo {

    private SpectralLod last;

    SpectralLod get(double camX, double camY, double camZ, double seaY, double minSpacing, double factor,
        double fadeStart, double fadeEnd) {
        SpectralLod l = last;
        if (l == null || l.camX != camX
            || l.camY != camY
            || l.camZ != camZ
            || l.seaY != seaY
            || l.minSpacing != minSpacing
            || l.factor != factor
            || l.fadeStart != fadeStart
            || l.fadeEnd != fadeEnd) {
            l = new SpectralLod(camX, camY, camZ, seaY, minSpacing, factor, fadeStart, fadeEnd);
            last = l;
        }
        return l;
    }
}
