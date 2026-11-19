package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Dispersão, espectro e espalhamento contra respostas conhecidas. */
class PhysicsBasicsTest {

    @Test
    void dispersionRoundTripsAndHasBothLimits() {
        for (double depth : new double[] { 2, 10, 50, Double.POSITIVE_INFINITY }) {
            for (double w = 0.2; w < 8; w += 0.37) {
                double k = Dispersion.k(w, depth);
                assertEquals(w, Dispersion.omega(k, depth), 1e-10 * w, "profundidade " + depth + ", ω " + w);
            }
        }
        // água funda: ω² = g·k
        assertEquals(1.0 * 1.0 / Dispersion.G, Dispersion.k(1.0, Double.POSITIVE_INFINITY), 1e-15);
        // água rasa (k·h ≪ 1): a onda anda a √(g·h), não dispersiva
        double h = 0.5, w = 0.05;
        assertEquals(Math.sqrt(Dispersion.G * h), w / Dispersion.k(w, h), 1e-3 * Math.sqrt(Dispersion.G * h));
    }

    @Test
    void jonswapPeaksAtThePeakAndMatchesTheGrowthLaw() {
        Spectrum.Jonswap j = Spectrum.Jonswap.fromWind(10, 100_000, 3.3);
        double best = 0, bestW = 0;
        for (double w = 0.3; w < 3; w += 0.0005) {
            double d = j.density(w);
            if (d > best) {
                best = d;
                bestW = w;
            }
        }
        assertEquals(j.peakOmega, bestW, 0.002);
        // Lei de crescimento da energia do JONSWAP (Hasselmann et al. 1973): g·Hs/U² = 1,6·10⁻³·(g·F/U²)^½. As leis
        // de α e de ω_p são outros ajustes aos mesmos dados e, com γ = 3,3, dão uns 30% a mais de Hs (conhecido; o
        // Shore Protection Manual usa coeficientes próprios). Aqui só a ordem de grandeza.
        double g = Dispersion.G;
        double expected = 1.6e-3 * Math.sqrt(g * 100_000 / 100) * 100 / g;
        double hs = 4 * Math.sqrt(j.moment0());
        assertEquals(expected, hs, 0.4 * expected, "Hs " + hs + " contra " + expected);
    }

    /** Com γ = 1 o JONSWAP é o Pierson-Moskowitz, de integral fechada: m₀ = α·g²/(5·ω_p⁴). */
    @Test
    void integrationMatchesTheClosedForm() {
        Spectrum.Jonswap pm = new Spectrum.Jonswap(0.0081, 0.9, 1);
        double exact = 0.0081 * Dispersion.G * Dispersion.G / (5 * Math.pow(0.9, 4));
        // moment0 integra até 12·ω_p; a cauda que falta é α·g²/(4·(12ω_p)⁴), ou 1,25/12⁴ de m₀
        assertEquals(exact * (1 - 1.25 / Math.pow(12, 4)), pm.moment0(), 1e-6 * exact);
    }

    @Test
    void fullyDevelopedSeaIsCapped() {
        Spectrum.Jonswap j = Spectrum.Jonswap.fromWind(10, 1e9, 3.3);
        assertEquals(0.855 * Dispersion.G / 10, j.peakOmega, 1e-12);
        assertEquals(0.0081, j.alpha, 1e-12);
    }

    @Test
    void swellHasTheRequestedHeight() {
        Spectrum.Jonswap s = Spectrum.Jonswap.swell(1.7, 12, 10);
        assertEquals(1.7, 4 * Math.sqrt(s.moment0()), 1e-6);
        assertEquals(2 * Math.PI / 12, s.peakOmega, 1e-15);
    }

    @Test
    void tmaLimits() {
        assertEquals(1, Spectrum.tma(1, Double.POSITIVE_INFINITY), 0);
        assertEquals(1, Spectrum.tma(3, 100), 0);
        // contínua em ω_h = 1 e em ω_h = 2
        double h = 9.81; // ω_h = ω
        assertEquals(Spectrum.tma(1 - 1e-9, h), Spectrum.tma(1 + 1e-9, h), 1e-8);
        assertEquals(Spectrum.tma(2 - 1e-9, h), Spectrum.tma(2 + 1e-9, h), 1e-8);
        assertTrue(Spectrum.tma(0.3, h) < 0.05);
    }

    @Test
    void spreadingIsANormalisedDistributionWithAnExactInverse() {
        for (double r : new double[] { 0.6, 1.0, 1.3, 2.5, 6 }) {
            double beta = Spreading.beta(r, 1);
            int n = 20000;
            double sum = 0, h = 2 * Math.PI / n;
            for (int i = 0; i < n; i++) {
                sum += Spreading.density(-Math.PI + (i + 0.5) * h, beta) * h;
            }
            assertEquals(1, sum, 1e-6, "β " + beta);
            // a inversa: a probabilidade acumulada até θ(u) é u
            for (double u : new double[] { 0.05, 0.3, 0.5, 0.77, 0.99 }) {
                double theta = Spreading.inverse(u, beta);
                double cdf = 0;
                int m = 20000;
                double hh = (theta + Math.PI) / m;
                for (int i = 0; i < m; i++) {
                    cdf += Spreading.density(-Math.PI + (i + 0.5) * hh, beta) * hh;
                }
                assertEquals(u, cdf, 1e-6);
            }
        }
        // ondas mais curtas que o pico espalham mais (β menor)
        assertTrue(Spreading.beta(3, 1) < Spreading.beta(1, 1));
    }
}
