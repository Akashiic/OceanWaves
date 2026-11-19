package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SynthesisTest {

    @Test
    void sameStateGivesTheSameWavesBitForBit() {
        SeaState s = SeaPresets.get("fresco");
        assertEquals(Synthesis.build(s).fingerprint(), Synthesis.build(SeaState.fromArray(s.toArray())).fingerprint());
        assertNotEquals(Synthesis.build(s).fingerprint(), Synthesis.build(s.withSeed(7)).fingerprint());
    }

    /**
     * O resumo fixado: só StrictMath e SplitMix64 entram na síntese, então ele é o mesmo no Java 8 do cliente e no
     * Java 21 do servidor. Se mudar sem querer, cliente e servidor de versões diferentes desenhariam mares
     * diferentes; se a mudança for de propósito, atualize o valor (e a versão do protocolo).
     */
    @Test
    void fingerprintIsPinned() {
        long f = Synthesis.build(SeaPresets.get("moderado")).fingerprint();
        assertEquals(PINNED, f, "resumo novo: " + f + "L");
    }

    static final long PINNED = 3473730547258373915L;

    @Test
    void significantHeightIsPreserved() {
        for (Map.Entry<String, SeaState> e : SeaPresets.all().entrySet()) {
            SeaState s = e.getValue().withLengthScale(1);
            WaveSet w = Synthesis.build(s);
            double m0 = Spectrum.Jonswap.fromWind(Math.max(0.5, s.windSpeed), s.fetch, s.gamma).moment0()
                + (s.swellHeight > 0 ? s.swellHeight * s.swellHeight / 16 : 0);
            double target = 4 * Math.sqrt(m0);
            // a faixa amostrada (0,6 a 8 ω_p) e o corte de comprimento perdem um pouco das pontas
            assertEquals(target, w.significantHeight, 0.06 * target, e.getKey());
        }
    }

    @Test
    void componentsAreSortedAndNeverFold() {
        for (SeaState s : SeaPresets.all().values()) {
            WaveSet w = Synthesis.build(s.withChoppiness(1).withScale(3));
            for (int i = 1; i < w.count; i++) {
                assertTrue(w.k[i] >= w.k[i - 1]);
            }
            assertTrue(w.lipschitz <= s.maxSteepness + 1e-12, "L = " + w.lipschitz);
            for (int i = 0; i < w.count; i++) {
                assertTrue(w.wavelength(i) >= s.minWavelength * 0.999 && w.wavelength(i) <= s.maxWavelength * 1.001);
                assertEquals(1, w.dirX[i] * w.dirX[i] + w.dirZ[i] * w.dirZ[i], 1e-12);
            }
        }
    }

    @Test
    void froudeScaleIsAnExactScaleModel() {
        SeaState s = SeaState.wind(10, 0, 100_000).withWavelengths(0.01, 10_000);
        WaveSet full = Synthesis.build(s), half = Synthesis.build(s.withLengthScale(0.25));
        assertEquals(full.count, half.count);
        for (int i = 0; i < full.count; i++) {
            assertEquals(full.amplitude[i] * 0.25, half.amplitude[i], 1e-12);
            assertEquals(full.k[i] / 0.25, half.k[i], 1e-9 * half.k[i]);
            assertEquals(full.omega[i] / 0.5, half.omega[i], 1e-9 * half.omega[i]);
            // continua obedecendo a dispersão de água funda
            assertEquals(half.omega[i] * half.omega[i], Dispersion.G * half.k[i], 1e-9 * half.k[i]);
        }
    }

    @Test
    void prefixCutsByWavelength() {
        WaveSet w = Synthesis.build(SeaPresets.get("forte"));
        for (double lambda : new double[] { 0.5, 2, 5, 20, 1000 }) {
            int p = w.prefix(lambda);
            for (int i = 0; i < w.count; i++) {
                assertEquals(i < p, w.wavelength(i) >= lambda);
            }
        }
    }
}
