package com.akashic.oceanwaves.client;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;

import org.lwjgl.opengl.GL11;

import com.akashic.oceanwaves.mixin.mixins.client.TextureAtlasSpriteAccessor;

/**
 * A textura da água parada numa textura própria, com repetição: no atlas de blocos ela não se repete, e um quad de
 * vários blocos (a malha longe da câmera) esticaria um ladrilho por cima de todos. Copia o quadro atual da animação
 * do próprio sprite do atlas (respeita pacotes de recursos e mods que trocam a água, como o Neo States) quando ele
 * muda.
 */
final class WaterTexture {

    private int id = -1;
    private int width;
    private int height;
    private int frame = -1;
    private TextureAtlasSprite sprite;
    /** O tom médio da textura (RGB 0 a 1), que a cor do vértice leva no lugar dela. */
    private final float[] tint = { 0.25f, 0.35f, 0.85f };

    float[] tint() {
        return tint;
    }

    void bind() {
        IIcon icon = Blocks.water.getIcon(1, 0);
        if (!(icon instanceof TextureAtlasSprite)) {
            return;
        }
        TextureAtlasSprite s = (TextureAtlasSprite) icon;
        int frames = Math.max(1, s.getFrameCount());
        int f = frames > 1 ? Math.floorMod(((TextureAtlasSpriteAccessor) s).getOceanwavesFrameCounter(), frames) : 0;
        if (id < 0) {
            id = TextureUtil.glGenTextures();
        }
        if (s != sprite || f != frame || width != s.getIconWidth() || height != s.getIconHeight()) {
            sprite = s;
            frame = f;
            int[][] data = frames > 0 && s.getFrameCount() > 0 ? s.getFrameTextureData(f) : null;
            int w = s.getIconWidth(), h = s.getIconHeight();
            if (data != null && data.length > 0 && data[0].length >= w * h) {
                if (w != width || h != height) {
                    TextureUtil.allocateTexture(id, w, h);
                    width = w;
                    height = h;
                }
                TextureUtil.uploadTexture(id, luminance(data[0], w * h), w, h);
            }
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    /**
     * A textura em tons de cinza (o detalhe, com o pixel mais claro em branco) e opaca; o tom médio vai para
     * {@link #tint}. No GL fixo a cor do vértice é multiplicada pela textura: com a textura azul, um reflexo
     * laranja do pôr do sol sairia quase preto. Assim a cor fica toda no vértice.
     */
    private int[] luminance(int[] argb, int n) {
        double r = 0, g = 0, b = 0, max = 1e-6;
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) {
            int p = argb[i];
            float pr = (p >> 16 & 255) / 255f, pg = (p >> 8 & 255) / 255f, pb = (p & 255) / 255f;
            r += pr;
            g += pg;
            b += pb;
            lum[i] = 0.2126f * pr + 0.7152f * pg + 0.0722f * pb;
            max = Math.max(max, lum[i]);
        }
        r /= n;
        g /= n;
        b /= n;
        double mean = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        // a textura cinza tem média mean/max: o tom compensa para a cor média sair a da textura original
        double k = max / Math.max(1e-6, mean);
        tint[0] = (float) Math.min(1, r * k);
        tint[1] = (float) Math.min(1, g * k);
        tint[2] = (float) Math.min(1, b * k);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int v = Math.min(255, Math.round(lum[i] / (float) max * 255));
            out[i] = 0xFF000000 | v << 16 | v << 8 | v;
        }
        return out;
    }

    void delete() {
        if (id >= 0) {
            TextureUtil.deleteTexture(id);
            id = -1;
            sprite = null;
            frame = -1;
            width = 0;
        }
    }
}
