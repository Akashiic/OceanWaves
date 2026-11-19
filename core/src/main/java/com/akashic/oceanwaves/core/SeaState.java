package com.akashic.oceanwaves.core;

import java.util.Locale;

/**
 * O estado do mar: os poucos números de que as ondas nascem. Servidor e cliente trocam só isto (algumas dezenas de
 * bytes) e cada um sintetiza as mesmas componentes com {@link Synthesis}, que é determinística.
 *
 * <p>Direções em graus no plano do mundo, para onde as ondas andam: 0 = +x (leste), 90 = +z (sul), como o
 * {@code /exp vento} do Expeditions. Distâncias em metros (1 bloco = 1 m).
 */
public final class SeaState {

    /** Vento a 10 m da superfície, m/s. */
    public final double windSpeed;
    /** Para onde o vento sopra (e o mar de vento anda), graus. */
    public final double windDirection;
    /** Pista: por quantos metros o vento soprou sobre a água. */
    public final double fetch;
    /** Fator de pico do JONSWAP do mar de vento. */
    public final double gamma;
    /** Altura significativa do marulho, m (0: sem marulho). */
    public final double swellHeight;
    /** Período de pico do marulho, s. */
    public final double swellPeriod;
    /** Para onde o marulho anda, graus. */
    public final double swellDirection;
    /** Profundidade efetiva para o espectro e a dispersão (infinito: água funda). */
    public final double depth;
    /** Multiplica todas as amplitudes (o "intensidade" da configuração). */
    public final double heightScale;
    /** 0 a 1: quanto as cristas se aguçam (deslocamento horizontal de Gerstner), limitado por {@link #maxSteepness}. */
    public final double choppiness;
    /** Limite de Σ Qᵢ·kᵢ·aᵢ: abaixo de 1 a superfície nunca dobra sobre si (ver docs/MATEMATICA.md). */
    public final double maxSteepness;
    /** Quantas componentes ao todo. */
    public final int components;
    /** Quantas delas são do marulho (quando há marulho). */
    public final int swellComponents;
    /** Ondas mais curtas que isto não entram (m). */
    public final double minWavelength;
    /** Ondas mais longas que isto não entram (m). */
    public final double maxWavelength;
    public final long seed;
    /**
     * Escala de Froude do mar: comprimentos (e amplitudes) multiplicados por s, tempos por √s. Um mar com s = 0,5 é
     * a maquete exata, em meia escala, do mar que aquele vento faz: continua obedecendo a ω² = g·k (k/s e ω/√s), só
     * que cabe no Minecraft. O vento continua o real (para as velas).
     */
    public final double lengthScale;

    public SeaState(double windSpeed, double windDirection, double fetch, double gamma, double swellHeight,
        double swellPeriod, double swellDirection, double depth, double heightScale, double choppiness,
        double maxSteepness, int components, int swellComponents, double minWavelength, double maxWavelength,
        long seed, double lengthScale) {
        if (components < 1 || components > 512) {
            throw new IllegalArgumentException("componentes: de 1 a 512");
        }
        if (!(maxSteepness > 0 && maxSteepness < 1)) {
            throw new IllegalArgumentException("maxSteepness tem de ficar entre 0 e 1 (exclusive)");
        }
        if (!(minWavelength > 0 && maxWavelength > minWavelength)) {
            throw new IllegalArgumentException("comprimentos de onda inválidos");
        }
        this.windSpeed = Math.max(0, windSpeed);
        this.windDirection = windDirection;
        this.fetch = Math.max(1, fetch);
        this.gamma = Math.max(1, gamma);
        this.swellHeight = Math.max(0, swellHeight);
        this.swellPeriod = Math.max(1, swellPeriod);
        this.swellDirection = swellDirection;
        this.depth = depth > 0 ? depth : Double.POSITIVE_INFINITY;
        this.heightScale = Math.max(0, heightScale);
        this.choppiness = Math.min(1, Math.max(0, choppiness));
        this.maxSteepness = maxSteepness;
        this.components = components;
        this.swellComponents = Math.max(0, Math.min(swellComponents, components - 1));
        this.minWavelength = minWavelength;
        this.maxWavelength = maxWavelength;
        this.seed = seed;
        if (!(lengthScale > 0.01 && lengthScale <= 4)) {
            throw new IllegalArgumentException("escala de comprimento: de 0,01 a 4");
        }
        this.lengthScale = lengthScale;
    }

    /** Mar de vento puro, com os demais valores padrão. */
    public static SeaState wind(double windSpeed, double windDirection, double fetch) {
        return new SeaState(windSpeed, windDirection, fetch, 3.3, 0, 10, windDirection, Double.POSITIVE_INFINITY, 1,
            0.8, 0.85, 64, 0, 1.25, 600, 0x0CEA_17L, 1.0);
    }

    public SeaState withWind(double speed, double direction) {
        return new SeaState(speed, direction, fetch, gamma, swellHeight, swellPeriod, swellDirection, depth,
            heightScale, choppiness, maxSteepness, components, swellComponents, minWavelength, maxWavelength, seed, lengthScale);
    }

    public SeaState withFetch(double meters) {
        return new SeaState(windSpeed, windDirection, meters, gamma, swellHeight, swellPeriod, swellDirection, depth,
            heightScale, choppiness, maxSteepness, components, swellComponents, minWavelength, maxWavelength, seed, lengthScale);
    }

    public SeaState withSwell(double height, double period, double direction, int count) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, height, period, direction, depth, heightScale,
            choppiness, maxSteepness, components, count, minWavelength, maxWavelength, seed, lengthScale);
    }

    public SeaState withScale(double scale) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, swellHeight, swellPeriod, swellDirection, depth,
            scale, choppiness, maxSteepness, components, swellComponents, minWavelength, maxWavelength, seed, lengthScale);
    }

    public SeaState withChoppiness(double chop) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, swellHeight, swellPeriod, swellDirection, depth,
            heightScale, chop, maxSteepness, components, swellComponents, minWavelength, maxWavelength, seed, lengthScale);
    }

    public SeaState withComponents(int count) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, swellHeight, swellPeriod, swellDirection, depth,
            heightScale, choppiness, maxSteepness, count, swellComponents, minWavelength, maxWavelength, seed, lengthScale);
    }

    public SeaState withDepth(double meters) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, swellHeight, swellPeriod, swellDirection, meters,
            heightScale, choppiness, maxSteepness, components, swellComponents, minWavelength, maxWavelength, seed, lengthScale);
    }

    public SeaState withWavelengths(double min, double max) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, swellHeight, swellPeriod, swellDirection, depth,
            heightScale, choppiness, maxSteepness, components, swellComponents, min, max, seed, lengthScale);
    }

    public SeaState withSeed(long newSeed) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, swellHeight, swellPeriod, swellDirection, depth,
            heightScale, choppiness, maxSteepness, components, swellComponents, minWavelength, maxWavelength, newSeed, lengthScale);
    }

    public SeaState withLengthScale(double scale) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, swellHeight, swellPeriod, swellDirection, depth,
            heightScale, choppiness, maxSteepness, components, swellComponents, minWavelength, maxWavelength, seed,
            scale);
    }

    /** Os campos em ordem, para a rede e para o hash de conferência. */
    public double[] toArray() {
        return new double[] { windSpeed, windDirection, fetch, gamma, swellHeight, swellPeriod, swellDirection,
            Double.isInfinite(depth) ? -1 : depth, heightScale, choppiness, maxSteepness, components, swellComponents,
            minWavelength, maxWavelength, Double.longBitsToDouble(seed), lengthScale };
    }

    public static SeaState fromArray(double[] v) {
        if (v.length != 17) {
            throw new IllegalArgumentException("estado do mar: esperava 17 números, veio " + v.length);
        }
        return new SeaState(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7] < 0 ? Double.POSITIVE_INFINITY : v[7], v[8],
            v[9], v[10], (int) v[11], (int) v[12], v[13], v[14], Double.doubleToRawLongBits(v[15]), v[16]);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SeaState && java.util.Arrays.equals(toArray(), ((SeaState) o).toArray());
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(toArray());
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
            "vento %.1f m/s para %.0f°, pista %.0f km, marulho %.1f m/%.0f s para %.0f°, altura ×%.2f, comprimento ×%.2f, crista %.2f, %d componentes",
            windSpeed, windDirection, fetch / 1000, swellHeight, swellPeriod, swellDirection, heightScale, lengthScale,
            choppiness, components);
    }
}
