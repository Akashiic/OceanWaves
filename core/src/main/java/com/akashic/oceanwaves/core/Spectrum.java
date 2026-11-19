package com.akashic.oceanwaves.core;

/**
 * Espectros de frequência do mar, S(ω) em m²·s/rad: a energia por faixa de frequência. A variância da superfície é
 * m₀ = ∫S(ω)dω e a altura significativa é Hs = 4√m₀.
 *
 * <p>Fórmulas como em Hasselmann et al. (1973, JONSWAP), na forma usada por Horvath, "Empirical directional wave
 * spectra for computer graphics" (DigiPro 2015), e o fator de profundidade TMA de Bouws et al. (1985) com a
 * aproximação de Thompson e Vincent (1983).
 */
public final class Spectrum {

    private Spectrum() {}

    /**
     * Parâmetros de um mar de vento JONSWAP.
     */
    public static final class Jonswap {

        /** Constante de Phillips. */
        public final double alpha;
        /** Frequência de pico, rad/s. */
        public final double peakOmega;
        /** Fator de pico (3,3 no mar de vento médio do JONSWAP; maior para marulho). */
        public final double gamma;

        public Jonswap(double alpha, double peakOmega, double gamma) {
            this.alpha = alpha;
            this.peakOmega = peakOmega;
            this.gamma = gamma;
        }

        /**
         * O mar que um vento {@code windSpeed} (m/s, a 10 m) levanta depois de soprar por {@code fetchMeters} metros
         * de pista: α = 0,076·(U²/(F·g))^0,22 e ω_p = 22·(g²/(U·F))^(1/3). Pista longa demais dá um mar mais
         * desenvolvido que o totalmente desenvolvido (Pierson-Moskowitz): o pico não desce abaixo de 0,855·g/U e o
         * α não abaixo de 0,0081.
         */
        public static Jonswap fromWind(double windSpeed, double fetchMeters, double gamma) {
            double u = Math.max(0.1, windSpeed);
            double f = Math.max(1.0, fetchMeters);
            double g = Dispersion.G;
            double alpha = 0.076 * StrictMath.pow(u * u / (f * g), 0.22);
            double wp = 22 * StrictMath.cbrt(g * g / (u * f));
            double fullyDeveloped = 0.855 * g / u;
            if (wp < fullyDeveloped) {
                wp = fullyDeveloped;
            }
            if (alpha < 0.0081) {
                alpha = 0.0081;
            }
            return new Jonswap(alpha, wp, gamma);
        }

        /**
         * Um marulho: um JONSWAP estreito cujo α é escolhido para dar a altura significativa pedida. {@code gamma}
         * alto (7 a 20) deixa o espectro estreito, como o de ondas que viajaram longe da tempestade que as fez.
         */
        public static Jonswap swell(double significantHeight, double peakPeriod, double gamma) {
            Jonswap unit = new Jonswap(1, 2 * Math.PI / peakPeriod, gamma);
            double m0 = unit.moment0();
            double target = significantHeight * significantHeight / 16;
            return new Jonswap(target / m0, unit.peakOmega, gamma);
        }

        public double density(double omega) {
            if (omega <= 0) {
                return 0;
            }
            double g = Dispersion.G;
            double wp = peakOmega;
            double sigma = omega <= wp ? 0.07 : 0.09;
            double d = (omega - wp) / (sigma * wp);
            double r = StrictMath.exp(-0.5 * d * d);
            double q = wp / omega;
            double q2 = q * q;
            return alpha * g * g / StrictMath.pow(omega, 5) * StrictMath.exp(-1.25 * q2 * q2) * StrictMath.pow(gamma, r);
        }

        /** m₀ = ∫S(ω)dω, por Simpson em escala logarítmica de 0,2·ω_p a 12·ω_p. */
        public double moment0() {
            return integrate(0.2 * peakOmega, 12 * peakOmega, 4000, Double.POSITIVE_INFINITY);
        }

        /** ∫S(ω)·Φ(ω,h)dω entre {@code from} e {@code to}, Simpson sobre ln ω. */
        public double integrate(double from, double to, int intervals, double depth) {
            int n = intervals % 2 == 0 ? intervals : intervals + 1;
            double a = StrictMath.log(from), b = StrictMath.log(to), h = (b - a) / n;
            double sum = 0;
            for (int i = 0; i <= n; i++) {
                double w = StrictMath.exp(a + i * h);
                double f = density(w) * tma(w, depth) * w; // dω = ω·d(ln ω)
                sum += f * (i == 0 || i == n ? 1 : i % 2 == 1 ? 4 : 2);
            }
            return sum * h / 3;
        }
    }

    /**
     * O fator TMA (Kitaigorodskii): quanto da energia de água funda sobra em água de profundidade {@code depth}. Com
     * ω_h = ω·√(h/g): ½ω_h² até 1, 1 − ½(2 − ω_h)² até 2, e 1 depois.
     */
    public static double tma(double omega, double depth) {
        if (Double.isInfinite(depth)) {
            return 1;
        }
        double wh = omega * StrictMath.sqrt(Math.max(0, depth) / Dispersion.G);
        if (wh <= 1) {
            return 0.5 * wh * wh;
        }
        if (wh < 2) {
            double u = 2 - wh;
            return 1 - 0.5 * u * u;
        }
        return 1;
    }
}
