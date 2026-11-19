package com.akashic.oceanwaves.client;

/** As medidas do renderizador, para o F3 e para o harness. */
public final class OceanStats {

    private OceanStats() {}

    public static double meshMs() {
        return OceanRenderer.INSTANCE.averageMeshMs();
    }

    public static double drawMs() {
        return OceanRenderer.INSTANCE.averageDrawMs();
    }

    public static double glMs() {
        return OceanRenderer.INSTANCE.averageGlMs();
    }

    public static int chunks() {
        return OceanRenderer.INSTANCE.lastChunks;
    }

    public static int quads() {
        return OceanRenderer.INSTANCE.lastQuads;
    }

    /** Se o mod detecta um shaderpack ligado (e deixa a luz com o pack). */
    public static boolean shaderPack() {
        return ShaderPacks.inUse();
    }

    /** Uma linha de diagnóstico; zera o contador de chamadas. */
    public static String diagnostics(int frames) {
        OceanRenderer r = OceanRenderer.INSTANCE;
        int[] c = r.chunks()
            .census();
        String line = String.format(
            java.util.Locale.ROOT,
            "chamadas/quadro %.2f, programa %d, fase %s, shaderpack %s, cache %d (lidos %d, sujos %d, com mar %d), câmera (%.1f, %.1f)",
            frames > 0 ? r.calls / (double) frames : 0,
            r.lastProgram,
            r.lastPhase,
            ShaderPacks.inUse(),
            r.chunks()
                .size(),
            c[0],
            c[1],
            c[2],
            r.lastCamX,
            r.lastCamZ);
        r.calls = 0;
        return line;
    }

    public static void reset() {
        OceanRenderer.INSTANCE.resetAverages();
    }
}
