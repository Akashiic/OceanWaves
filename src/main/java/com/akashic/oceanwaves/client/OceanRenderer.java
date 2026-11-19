package com.akashic.oceanwaves.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import com.akashic.oceanwaves.OceanConfig;
import com.akashic.oceanwaves.OceanWavesMod;
import com.akashic.oceanwaves.core.FloatBuffer;
import com.akashic.oceanwaves.core.OceanMesher;
import com.akashic.oceanwaves.core.SpectralLod;
import com.akashic.oceanwaves.core.WaveSet;

/**
 * Desenha a superfície do mar, logo depois da água translúcida do terreno (o passe 1 de
 * {@code RenderGlobal.sortAndRender}).
 *
 * <p>
 * Por quadro: os chunks de mar dentro do alcance e do campo de visão, do mais perto para o mais longe; para cada
 * um, o espaçamento que a distância pede, a malha ({@link OceanMesher}) com a atenuação da costa do chunk, e os
 * vértices no {@link Tessellator} (que o Angelica intercepta e desenha no pipeline dele, com ou sem shader). A cor de
 * cada vértice leva a cor da água do bioma, a luz do mundo (mapa de luz), e, sem shader, um sombreamento: difusa do
 * sol, reflexo do céu pelo termo de Fresnel (Schlick), brilho especular do sol (Blinn-Phong) e a espuma das cristas.
 * A profundidade é escrita (com os chunks do mais perto para o mais longe, a crista da frente esconde o vale de trás).
 */
public final class OceanRenderer {

    public static final OceanRenderer INSTANCE = new OceanRenderer();

    private final OceanChunks chunks = new OceanChunks();
    private final WaterTexture texture = new WaterTexture();
    private final FloatBuffer buffer = new FloatBuffer();
    private final List<OceanChunks.Column> visible = new ArrayList<>();
    private final double[] neighbours = new double[4];
    private final SurfaceShader shader = new SurfaceShader();
    private OceanMesher mesher;
    private int meshedVersion = -1;
    private boolean failed;

    // medidas, para o F3
    long lastMeshNanos;
    long lastDrawNanos;
    /** Só o {@code Tessellator.draw()} (o que o GL faz); o resto do envio é montar os vértices na CPU. */
    long lastGlNanos;
    private double avgGl;
    int lastChunks;
    int lastQuads;
    int lastComponents;
    private double avgMesh;
    private double avgDraw;

    private static final long REBUILD_BUDGET_NANOS = 3_000_000L;
    private int[] spiral = new int[0];
    private int spiralRadius = -1;

    private OceanRenderer() {}

    /** Os deslocamentos (dx, dz) dentro do raio, do mais perto para o mais longe. */
    private int[] spiral(int radius) {
        if (radius != spiralRadius) {
            java.util.List<int[]> list = new ArrayList<>();
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (dx * dx + dz * dz <= radius * radius + 1) {
                        list.add(new int[] { dx, dz });
                    }
                }
            }
            list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
            spiral = new int[list.size() * 2];
            for (int i = 0; i < list.size(); i++) {
                spiral[2 * i] = list.get(i)[0];
                spiral[2 * i + 1] = list.get(i)[1];
            }
            spiralRadius = radius;
        }
        return spiral;
    }

    OceanChunks chunks() {
        return chunks;
    }

    void resetAverages() {
        avgMesh = lastMeshNanos;
        avgDraw = lastDrawNanos;
        avgGl = lastGlNanos;
    }

    double averageMeshMs() {
        return avgMesh / 1e6;
    }

    double averageGlMs() {
        return avgGl / 1e6;
    }

    double averageDrawMs() {
        return avgDraw / 1e6;
    }

    /** Chamado no fim do passe 1 do terreno. */
    public void render(float partialTicks) {
        if (failed || !ClientSea.active()) {
            return;
        }
        if (ShaderPacks.inUse() && ShaderPacks.renderingShadowPass()) {
            return;
        }
        try {
            draw(partialTicks);
        } catch (RuntimeException e) {
            // nunca derrubar o jogo por causa do mar: desliga e explica
            failed = true;
            OceanWavesMod.LOG.error("O desenho do mar falhou e foi desligado nesta sessão", e);
        }
    }

    private void draw(float partial) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        EntityLivingBase view = mc.renderViewEntity;
        if (world == null || view == null) {
            return;
        }
        chunks.attach(world);
        WaveSet waves = ClientSea.waves();
        if (ClientSea.version() != meshedVersion || mesher == null) {
            mesher = new OceanMesher(waves);
            meshedVersion = ClientSea.version();
            chunks.invalidateAll();
        }
        double camX = RenderManager.renderPosX, camY = RenderManager.renderPosY, camZ = RenderManager.renderPosZ;
        double rest = ClientSea.restLevel();
        double t = ClientSea.time(partial);
        long t0 = System.nanoTime();

        int radius = mc.gameSettings.renderDistanceChunks;
        int ccx = (int) Math.floor(camX) >> 4, ccz = (int) Math.floor(camZ) >> 4;
        chunks.prune(ccx, ccz, radius);
        Frustrum frustum = new Frustrum();
        frustum.setPosition(camX, camY, camZ);
        double reach = waves.maxAmplitude + waves.meanLevel + 1;
        visible.clear();
        boolean shaders = ShaderPacks.inUse();
        int rebuilds = 0;
        // Do mais perto para o mais longe, com um orçamento de tempo (não de contagem): a face de cima da água
        // vanilla já some em todo chunk de mar, então cada chunk ainda não lido é um buraco; num quadro lento (PC
        // fraco, shaderpack, logo depois de um teleporte) um limite fixo por quadro deixava buracos por dezenas de
        // segundos.
        int[] order = spiral(radius);
        long budget = t0 + REBUILD_BUDGET_NANOS;
        for (int o = 0; o < order.length; o += 2) {
            int dx = order[o], dz = order[o + 1];
            {
                OceanChunks.Column c = chunks.get(ccx + dx, ccz + dz);
                if (c.dirty) {
                    if (rebuilds >= OceanConfig.rebuildsPerFrame && dx * dx + dz * dz > 4
                        && System.nanoTime() > budget) {
                        continue;
                    }
                    chunks.rebuild(c);
                    rebuilds++;
                }
                if (!c.any) {
                    continue;
                }
                double x0 = c.cx * 16.0, z0 = c.cz * 16.0;
                AxisAlignedBB box = AxisAlignedBB
                    .getBoundingBox(x0 - 4, rest - reach, z0 - 4, x0 + 20, rest + reach, z0 + 20);
                // com shaderpack o Iris não atualiza o frustum vanilla (ClippingHelperImpl) para a câmera: visto no
                // BSL,
                // ele descartava quase todos os chunks. Ali o recorte fica com o pipeline do pack.
                if (!shaders && !frustum.isBoundingBoxInFrustum(box)) {
                    continue;
                }
                visible.add(c);
            }
        }
        // do mais perto para o mais longe
        visible.sort((a, b) -> Double.compare(dist2(a, camX, camZ), dist2(b, camX, camZ)));
        for (OceanChunks.Column c : visible) {
            if (c.shoreDirty || c.shore == null) {
                chunks.rebuildShore(c, waves);
            }
        }
        // a luz muda devagar: alguns chunks por quadro
        if (!visible.isEmpty()) {
            for (int i = 0; i < 3; i++) {
                chunks.refreshLight(visible.get(Math.floorMod(chunks.nextLightCursor(), visible.size())));
            }
        }

        SpectralLod lod = new SpectralLod(
            camX,
            camY,
            camZ,
            rest,
            OceanConfig.minSpacing,
            OceanConfig.lodFactor,
            2.5,
            5.0);
        mesher.grid()
            .beginFrame(t, ccx - radius - 1, ccz - radius - 1, 2 * radius + 3, 2 * radius + 3, 16);
        shader.frame(world, view, partial, ShaderPacks.inUse(), texture.tint(), waves.significantHeight);
        buffer.clear();
        int[] starts = new int[visible.size() + 1];
        int quads = 0;
        for (int i = 0; i < visible.size(); i++) {
            OceanChunks.Column c = visible.get(i);
            starts[i] = buffer.size;
            double s = OceanMesher.spacingFor(lod, c.cx, c.cz, OceanConfig.minSpacing);
            neighbours[0] = OceanMesher.spacingFor(lod, c.cx - 1, c.cz, OceanConfig.minSpacing);
            neighbours[1] = OceanMesher.spacingFor(lod, c.cx + 1, c.cz, OceanConfig.minSpacing);
            neighbours[2] = OceanMesher.spacingFor(lod, c.cx, c.cz - 1, OceanConfig.minSpacing);
            neighbours[3] = OceanMesher.spacingFor(lod, c.cx, c.cz + 1, OceanConfig.minSpacing);
            shader.chunk(c, camX, camY, camZ, rest);
            quads += mesher.mesh(c.cx, c.cz, s, neighbours, c.open, t, lod, c.shore, shader, buffer);
        }
        starts[visible.size()] = buffer.size;
        long t1 = System.nanoTime();

        submit(mc, world, partial, camX, camY, camZ, rest, starts);
        long t2 = System.nanoTime();

        lastMeshNanos = t1 - t0;
        lastDrawNanos = t2 - t1;
        lastChunks = visible.size();
        lastQuads = quads;
        lastComponents = waves.count;
        avgMesh += (lastMeshNanos - avgMesh) * 0.05;
        avgDraw += (lastDrawNanos - avgDraw) * 0.05;
        avgGl += (lastGlNanos - avgGl) * 0.05;
    }

    private static double dist2(OceanChunks.Column c, double x, double z) {
        double dx = c.cx * 16 + 8 - x, dz = c.cz * 16 + 8 - z;
        return dx * dx + dz * dz;
    }

    private void submit(Minecraft mc, World world, float partial, double camX, double camY, double camZ, double rest,
        int[] starts) {
        boolean own = shader.own;
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        if (!own) {
            mc.entityRenderer.enableLightmap(partial);
        }
        texture.bind();
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glColor4f(1, 1, 1, 1);
        Tessellator tess = Tessellator.instance;
        if (!own && ShaderPacks.inUse()) {
            IrisBridge.begin();
        }
        try {
            tess.startDrawingQuads();
            float[] d = buffer.data;
            for (int i = 0; i + 1 < starts.length; i++) {
                OceanChunks.Column c = visible.get(i);
                double ox = c.cx * 16.0, oz = c.cz * 16.0;
                // a textura se repete a cada bloco: a coordenada vai pequena (precisão do float longe da origem)
                double tu = (c.cx & 63) * 16.0, tv = (c.cz & 63) * 16.0;
                tess.setTranslation(ox - camX, rest - camY, oz - camZ);
                for (int k = starts[i]; k < starts[i + 1]; k += OceanMesher.FLOATS) {
                    int rgba = Float.floatToRawIntBits(d[k + 9]);
                    tess.setColorRGBA(rgba >>> 24, rgba >> 16 & 255, rgba >> 8 & 255, rgba & 255);
                    if (!own) {
                        int col = Math.min(15, Math.max(0, (int) d[k + 7]))
                            + 16 * Math.min(15, Math.max(0, (int) d[k + 8]));
                        tess.setBrightness(c.light[col]);
                    }
                    tess.setNormal(d[k + 3], d[k + 4], d[k + 5]);
                    // a textura acompanha a posição de repouso das partículas da superfície
                    tess.addVertexWithUV(d[k], d[k + 1], d[k + 2], tu + d[k + 7], tv + d[k + 8]);
                }
            }
            long d0 = System.nanoTime();
            tess.draw();
            lastGlNanos = System.nanoTime() - d0;
        } finally {
            IrisBridge.end();
            tess.setTranslation(0, 0, 0);
            GL11.glDepthMask(depthMask);
            if (cull) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            }
            if (!blend) {
                GL11.glDisable(GL11.GL_BLEND);
            }
            if (!own) {
                mc.entityRenderer.disableLightmap(partial);
            }
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
        }
    }

    void onWorldChange() {
        texture.delete();
        failed = false;
    }
}
