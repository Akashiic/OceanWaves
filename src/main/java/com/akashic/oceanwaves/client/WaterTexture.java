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
                TextureUtil.uploadTexture(id, data[0], w, h);
            }
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
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
