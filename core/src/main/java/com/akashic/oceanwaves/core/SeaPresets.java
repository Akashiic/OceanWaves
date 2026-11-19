package com.akashic.oceanwaves.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Estados de mar prontos, pelo vento (escala de Beaufort) e uma pista oceânica de 200 km, em meia escala de Froude
 * ({@link SeaState#lengthScale}). A altura significativa sai
 * do espectro: o comentário de cada um traz o Hs que {@code SynthesisTest} confere.
 */
public final class SeaPresets {

    private static final double FETCH = 200_000;

    private SeaPresets() {}

    /** Meia escala de Froude: o pico do "moderado" fica em ~34 blocos, como as ondas que se veem do convés. */
    public static final double SCALE = 0.5;

    public static Map<String, SeaState> all() {
        Map<String, SeaState> m = new LinkedHashMap<>();
        m.put("calmo", SeaState.wind(3, 20, FETCH));                         // Beaufort 2
        m.put("brisa", SeaState.wind(6, 20, FETCH));                         // Beaufort 4
        m.put("moderado", SeaState.wind(9, 20, FETCH).withSwell(0.6, 11, 50, 8)); // Beaufort 5
        m.put("fresco", SeaState.wind(12, 20, FETCH).withSwell(0.8, 12, 45, 8));  // Beaufort 6
        m.put("forte", SeaState.wind(15, 20, FETCH).withSwell(1.0, 13, 40, 8));   // Beaufort 7
        m.put("tempestade", SeaState.wind(20, 20, FETCH).withSwell(1.5, 14, 35, 10).withComponents(80));
        m.put("marulho", SeaState.wind(2, 20, 20_000).withSwell(1.6, 13, 10, 16));
        m.replaceAll((k, v) -> v.withLengthScale(SCALE));
        return m;
    }

    public static SeaState get(String name) {
        SeaState s = all().get(name);
        if (s == null) {
            throw new IllegalArgumentException("estado de mar desconhecido: " + name + " (" + all().keySet() + ")");
        }
        return s;
    }
}
