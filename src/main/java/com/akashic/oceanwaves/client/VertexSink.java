package com.akashic.oceanwaves.client;

/**
 * Para onde o laço de emissão ({@link VertexEmitter}) manda os vértices: o {@code Tessellator} no jogo
 * ({@link TessellatorSink}), um gravador nos testes e na medida fora do jogo. Os mesmos cinco métodos do
 * {@code Tessellator} que o desenho usa, com a mesma semântica (cor, luz e normal valem até serem trocadas). No jogo
 * só há uma implementação carregada, então a chamada fica monomórfica e a JVM a inline.
 */
interface VertexSink {

    /** O deslocamento somado à posição dos vértices seguintes. */
    void setTranslation(double x, double y, double z);

    void setColorRGBA(int r, int g, int b, int a);

    /** A luz (o mapa de luz do vanilla, {@code sky << 20 | block << 4}). */
    void setBrightness(int brightness);

    void setNormal(float x, float y, float z);

    void addVertexWithUV(double x, double y, double z, double u, double v);
}
