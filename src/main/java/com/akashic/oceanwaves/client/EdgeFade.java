package com.akashic.oceanwaves.client;

import com.akashic.oceanwaves.core.Attenuation;
import com.akashic.oceanwaves.core.WaveSet;

/**
 * As ondas morrem suavemente na borda da distância de renderização. Além dela o Mirai (Distant Horizons) desenha a
 * água distante plana; sem isto a superfície ondulada terminaria num degrau de até a altura da crista contra ela (a
 * "costura" conhecida do Physics Mod com o Distant Horizons). Só no desenho: a física do servidor não muda, e a
 * diferença fica a mais de (alcance − faixa) blocos da câmera.
 *
 * <p>
 * A faixa é larga o bastante para não dobrar a superfície: o gradiente do smoothstep é no máximo 1,5/largura, e
 * com M = Σ Qa o termo M·|∇A| fica abaixo de metade da folga (1 − L) do mar.
 */
final class EdgeFade implements Attenuation {

    private Attenuation base;
    private double cx, cz, start, end;

    /** Prepara a faixa do quadro: a borda em {@code radius} blocos da câmera. */
    void frame(double camX, double camZ, double radius, WaveSet waves) {
        cx = camX;
        cz = camZ;
        double width = 32;
        if (waves.maxDisplacement > 0) {
            width = Math.max(width, 1.5 * waves.maxDisplacement / (0.5 * Math.max(0.05, 1 - waves.lipschitz)));
        }
        width = Math.min(width, radius * 0.5);
        end = radius - 8;
        start = end - width;
    }

    /** A atenuação deste chunk, com a faixa da borda se ele a toca (null: mar aberto). */
    Attenuation around(Attenuation chunk, double x0, double z0) {
        // o canto mais longe do chunk ainda dentro da parte cheia: sem faixa
        double far = Math.sqrt(sq(Math.abs(x0 + 8 - cx) + 8) + sq(Math.abs(z0 + 8 - cz) + 8));
        if (far <= start) {
            return chunk;
        }
        base = chunk;
        return this;
    }

    private static double sq(double v) {
        return v * v;
    }

    private double fade(double x, double z) {
        double d = Math.sqrt(sq(x - cx) + sq(z - cz));
        double u = (end - d) / (end - start);
        if (u <= 0) {
            return 0;
        }
        if (u >= 1) {
            return 1;
        }
        return u * u * (3 - 2 * u);
    }

    @Override
    public double at(double x, double z) {
        double f = fade(x, z);
        return f == 0 ? 0 : f * (base == null ? 1 : base.at(x, z));
    }

    @Override
    public boolean uniform() {
        return false;
    }
}
