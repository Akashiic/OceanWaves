package com.akashic.oceanwaves.core;

import java.util.Arrays;

/**
 * Do {@link SeaState} às componentes ({@link WaveSet}): amostragem estratificada do espectro direcional.
 *
 * <p>Para cada sistema de ondas (mar de vento e marulho) a faixa de frequências é dividida em N intervalos iguais em
 * ln ω. Em cada um sorteia-se uma frequência ωⱼ (estratificado com tremor), a amplitude vem da energia do intervalo,
 * aⱼ = √(2·S(ωⱼ)·Φ(ωⱼ,h)·Δωⱼ), e a direção é sorteada da distribuição de espalhamento D(θ; ωⱼ) pela inversa
 * fechada de {@link Spreading}, com quantis de baixa discrepância (sequência da razão áurea). Assim cada componente
 * carrega a energia certa e o conjunto cobre as direções na proporção que o espectro manda: Σaⱼ²/2 converge para m₀
 * (Hs = 4√m₀ preservado por construção), e com fases aleatórias a superfície é um processo gaussiano de espectro S.
 *
 * <p>Tudo com {@link StrictMath} e {@link SplitMix64}: o mesmo {@link SeaState} dá as mesmas componentes, bit a bit,
 * em qualquer JVM.
 */
public final class Synthesis {

    private static final double GOLDEN = 0.6180339887498949;

    private Synthesis() {}

    public static WaveSet build(SeaState s) {
        SplitMix64 rnd = new SplitMix64(s.seed);
        int swellCount = s.swellHeight > 0 ? s.swellComponents : 0;
        int windCount = s.components - swellCount;
        double[][] wind = system(rnd, windSpectrum(s), s.windDirection, windCount, 0.6, 8.0, 1.0, s);
        double[][] swell = swellCount > 0
            ? system(rnd, Spectrum.Jonswap.swell(s.swellHeight, s.swellPeriod, 10), s.swellDirection, swellCount, 0.75,
                1.8, 4.0, s)
            : new double[6][0];
        int n = wind[0].length + swell[0].length;
        double[][] all = new double[6][n];
        for (int f = 0; f < 6; f++) {
            System.arraycopy(wind[f], 0, all[f], 0, wind[f].length);
            System.arraycopy(swell[f], 0, all[f], wind[f].length, swell[f].length);
        }
        // ordem: do mais longo (k pequeno) para o mais curto
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        final double[] kk = all[1];
        Arrays.sort(order, (a, b) -> Double.compare(kk[a], kk[b]));
        double[] amp = new double[n], k = new double[n], dx = new double[n], dz = new double[n], om = new double[n],
            ph = new double[n];
        for (int i = 0; i < n; i++) {
            int o = order[i];
            amp[i] = all[0][o] * s.heightScale;
            k[i] = all[1][o];
            dx[i] = all[2][o];
            dz[i] = all[3][o];
            om[i] = all[4][o];
            ph[i] = all[5][o];
        }
        return new WaveSet(amp, k, dx, dz, om, ph, gerstner(amp, k, s));
    }

    private static Spectrum.Jonswap windSpectrum(SeaState s) {
        return Spectrum.Jonswap.fromWind(Math.max(0.5, s.windSpeed), s.fetch, s.gamma);
    }

    /**
     * Um sistema: {amplitude, k, dirX, dirZ, ω, fase}. Faixa de {@code lo}·ω_p a {@code hi}·ω_p, cortada pelos
     * comprimentos de onda permitidos.
     */
    private static double[][] system(SplitMix64 rnd, Spectrum.Jonswap spec, double directionDeg, int n, double lo,
        double hi, double narrowing, SeaState s) {
        double[][] out = new double[6][];
        // o espectro é o do mar real; a maquete de Froude leva comprimentos a ×s e frequências a ×1/√s
        double ls = s.lengthScale;
        double physDepth = s.depth / ls;
        double wMin = Math.max(lo * spec.peakOmega, Dispersion.omega(2 * Math.PI * ls / s.maxWavelength, physDepth));
        double wMax = Math.min(hi * spec.peakOmega, Dispersion.omega(2 * Math.PI * ls / s.minWavelength, physDepth));
        if (n <= 0 || !(wMax > wMin) || s.windSpeed <= 0 && narrowing == 1.0) {
            for (int f = 0; f < 6; f++) {
                out[f] = new double[0];
            }
            return out;
        }
        for (int f = 0; f < 6; f++) {
            out[f] = new double[n];
        }
        double a = StrictMath.log(wMin), b = StrictMath.log(wMax), step = (b - a) / n;
        double base = Math.toRadians(directionDeg);
        double quantile = rnd.nextDouble();
        for (int j = 0; j < n; j++) {
            double l0 = a + j * step;
            double w = StrictMath.exp(l0 + step * (0.15 + 0.7 * rnd.nextDouble()));
            double dw = StrictMath.exp(l0 + step) - StrictMath.exp(l0);
            double energy = spec.density(w) * Spectrum.tma(w, physDepth);
            double amp = StrictMath.sqrt(2 * energy * dw);
            quantile += GOLDEN;
            quantile -= StrictMath.floor(quantile);
            double beta = Spreading.beta(w, spec.peakOmega) * narrowing;
            double theta = base + Spreading.inverse(0.0005 + 0.999 * quantile, beta);
            double kPhys = Dispersion.k(w, physDepth);
            out[0][j] = amp * ls;
            out[1][j] = kPhys / ls;
            out[2][j] = StrictMath.cos(theta);
            out[3][j] = StrictMath.sin(theta);
            out[4][j] = Dispersion.omega(kPhys / ls, s.depth);
            out[5][j] = 2 * Math.PI * rnd.nextDouble();
        }
        return out;
    }

    /**
     * Os fatores de Gerstner. As ondas dominantes (até o dobro do número de onda de maior energia) recebem a
     * {@code choppiness} inteira; as mais curtas, uma fração que cai com k (crista aguda é coisa de onda grande).
     * Depois, se Σ Qᵢkᵢaᵢ passa do limite, todos são reduzidos na mesma proporção: com a constante de Lipschitz L do
     * deslocamento abaixo de 1, o mapa x₀ ↦ x₀ + D(x₀) é inversível (contração) e a malha nunca se dobra.
     */
    static double[] gerstner(double[] amp, double[] k, SeaState s) {
        int n = amp.length;
        double[] q = new double[n];
        if (n == 0 || s.choppiness <= 0) {
            return q;
        }
        int peak = 0;
        for (int i = 1; i < n; i++) {
            if (amp[i] > amp[peak]) {
                peak = i;
            }
        }
        double kRef = 2 * k[peak];
        double lip = 0;
        for (int i = 0; i < n; i++) {
            q[i] = s.choppiness * (k[i] <= kRef ? 1 : kRef / k[i]);
            lip += q[i] * k[i] * amp[i];
        }
        if (lip > s.maxSteepness) {
            double f = s.maxSteepness / lip;
            for (int i = 0; i < n; i++) {
                q[i] *= f;
            }
        }
        return q;
    }
}
