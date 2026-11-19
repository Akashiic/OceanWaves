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

    public static void reset() {
        OceanRenderer.INSTANCE.resetAverages();
    }
}
