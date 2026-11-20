package com.akashic.oceanwaves.client;

import net.minecraft.client.renderer.Tessellator;

/** O {@link VertexSink} do jogo: repassa ao {@code Tessellator} (que o Angelica intercepta). Só a thread do cliente. */
final class TessellatorSink implements VertexSink {

    private Tessellator tess;

    /** O {@code Tessellator} para onde ir; chamado a cada envio (a instância é a única do vanilla). */
    void use(Tessellator tess) {
        this.tess = tess;
    }

    @Override
    public void setTranslation(double x, double y, double z) {
        tess.setTranslation(x, y, z);
    }

    @Override
    public void setColorRGBA(int r, int g, int b, int a) {
        tess.setColorRGBA(r, g, b, a);
    }

    @Override
    public void setBrightness(int brightness) {
        tess.setBrightness(brightness);
    }

    @Override
    public void setNormal(float x, float y, float z) {
        tess.setNormal(x, y, z);
    }

    @Override
    public void addVertexWithUV(double x, double y, double z, double u, double v) {
        tess.addVertexWithUV(x, y, z, u, v);
    }
}
