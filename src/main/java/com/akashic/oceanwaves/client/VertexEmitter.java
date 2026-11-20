package com.akashic.oceanwaves.client;

import java.util.List;

import com.akashic.oceanwaves.core.OceanMesher;

/**
 * O laço de envio dos vértices de um passe, do buffer da malha ({@link OceanMesher#FLOATS} floats por vértice) para um
 * {@link VertexSink}. Fica fora do {@link OceanRenderer} para rodar sem Minecraft: os testes e o {@code clientBench}
 * exercitam este mesmo código, e não uma réplica. Nada alocado.
 *
 * <p>
 * O que o {@code Tessellator} guarda de um vértice para o seguinte (cor, luz, normal) só é mandado quando muda: com
 * shaderpack a cor é a da água em quase todo vértice, e a luz do chunk, uma só. Com a luz já na cor ({@code own}) a
 * normal não é usada (o desenho não liga a iluminação do GL) e nem é mandada, nem a luz do mapa.
 */
final class VertexEmitter {

    private VertexEmitter() {}

    /**
     * Manda os chunks {@code 0 .. spans.count() - 1} de {@code visible}, cada um com a sua translação (a posição do
     * chunk em relação à câmera) e as suas coordenadas de textura.
     *
     * @param d    o buffer da malha
     * @param own  a luz própria (sem shaderpack): a luz vai na cor
     * @param rest o nível de repouso do mar
     */
    static void emit(VertexSink sink, List<OceanChunks.Column> visible, ChunkSpans spans, float[] d, boolean own,
        double camX, double camY, double camZ, double rest) {
        boolean haveColor = false;
        int lastColor = 0;
        int lastLight = -1;
        for (int i = 0, n = spans.count(); i < n; i++) {
            OceanChunks.Column c = visible.get(i);
            double ox = c.cx * 16.0, oz = c.cz * 16.0;
            // a textura se repete a cada bloco: a coordenada vai pequena (precisão do float longe da origem)
            double tu = (c.cx & 63) * 16.0, tv = (c.cz & 63) * 16.0;
            sink.setTranslation(ox - camX, rest - camY, oz - camZ);
            int[] light = c.light;
            for (int k = spans.start(i), end = spans.stop(i); k < end; k += OceanMesher.FLOATS) {
                int rgba = Float.floatToRawIntBits(d[k + 9]);
                if (!haveColor || rgba != lastColor) {
                    sink.setColorRGBA(rgba >>> 24, rgba >> 16 & 255, rgba >> 8 & 255, rgba & 255);
                    lastColor = rgba;
                    haveColor = true;
                }
                if (!own) {
                    int col = Math.min(15, Math.max(0, (int) d[k + 7]))
                        + 16 * Math.min(15, Math.max(0, (int) d[k + 8]));
                    int b = light[col];
                    if (b != lastLight) {
                        sink.setBrightness(b);
                        lastLight = b;
                    }
                    sink.setNormal(d[k + 3], d[k + 4], d[k + 5]);
                }
                // a textura acompanha a posição de repouso das partículas da superfície
                sink.addVertexWithUV(d[k], d[k + 1], d[k + 2], tu + d[k + 7], tv + d[k + 8]);
            }
        }
    }
}
