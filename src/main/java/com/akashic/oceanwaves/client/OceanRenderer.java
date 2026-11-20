package com.akashic.oceanwaves.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import com.akashic.oceanwaves.OceanConfig;
import com.akashic.oceanwaves.OceanWavesMod;
import com.akashic.oceanwaves.core.Attenuation;
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
 *
 * <p>
 * O que é do quadro, e não do passe, é feito uma vez só ({@link #prepare}): com shaderpack o passe 1 roda também na
 * passagem de sombra, e ela reaproveita a releitura de chunks, as costas, o tempo e os fasores que o passe principal
 * (ou ela mesma, se vier primeiro) já fez. A releitura em rodízio dos chunks e da luz roda no tick do cliente
 * ({@link #tick}); no quadro ficam só a chegada de chunk novo (que não pode esperar) e a costa, com orçamento.
 */
public final class OceanRenderer {

    public static final OceanRenderer INSTANCE = new OceanRenderer();

    private final OceanChunks chunks = new OceanChunks();
    private final WaterTexture texture = new WaterTexture();
    private final FloatBuffer buffer = new FloatBuffer();
    private final List<OceanChunks.Column> visible = new ArrayList<>();
    private final double[] neighbours = new double[4];
    private final SurfaceShader shader = new SurfaceShader();
    private final ViewCone cone = new ViewCone();
    private final EdgeFade edge = new EdgeFade();
    private final StateMemo[] stateMemo = { new StateMemo(), new StateMemo() };
    private Frustrum frustum;
    private OceanMesher mesher;
    private int meshedVersion = -1;
    /**
     * Onde começa a malha de cada chunk no buffer do passe (o vetor só cresce: use {@code count()}, não o comprimento).
     */
    private final ChunkSpans spans = new ChunkSpans();
    /** O {@code SpectralLod} do passe principal e o da sombra, reaproveitados enquanto a câmera não anda. */
    private final LodMemo[] lodMemo = { new LodMemo(), new LodMemo() };
    private final TessellatorSink sink = new TessellatorSink();

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
    /** Depois disto o "quadro" preparado é considerado velho (o contador de quadros pode não andar). */
    private static final long PREPARED_STALE_NANOS = 100_000_000L;
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
        calls = 0;
        avgMesh = lastMeshNanos;
        avgDraw = lastDrawNanos;
        avgGl = lastGlNanos;
        // quem pede as médias é o harness: ele também quer o programa GL e a fase do Iris de cada desenho
        diagnostics = true;
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

    // diagnóstico (harness): chamadas, programa GL e fase do Iris no último desenho. Só lidos quando o harness pede
    // (resetAverages): um glGet e uma reflexão por quadro não são de graça.
    int calls;
    boolean diagnostics;
    int lastProgram = -1;
    String lastPhase = "?";
    double lastCamX, lastCamZ;

    /** Chamado no fim do passe 1 do terreno. */
    public void render(float partialTicks) {
        if (ClientSea.rendererFailed() || !ClientSea.active()) {
            return;
        }
        boolean pack = ShaderPacks.inUse();
        boolean shadow = pack && ShaderPacks.renderingShadowPass();
        if (shadow && !OceanConfig.waterShadows) {
            return;
        }
        if (!shadow) {
            calls++;
        }
        try {
            draw(partialTicks, shadow, pack);
        } catch (RuntimeException | LinkageError e) {
            // nunca derrubar o jogo por causa do mar (nem por um método que o Angelica mudou: NoSuchMethodError e
            // companhia são LinkageError): desliga, e a face de cima da água vanilla volta (ClientSea, no tick)
            fail(e);
        }
    }

    private void fail(Throwable e) {
        ClientSea.reportRendererFailure(e);
        OceanWavesMod.LOG.error("O desenho do mar falhou e foi desligado nesta sessão; a água do terreno volta", e);
    }

    /**
     * A cada tick do cliente: a releitura em rodízio dos chunks de mar (mudanças cujo aviso se perdeu, que o Celeritas
     * não manda) e da luz. Antes rodava a cada quadro; a cor do bioma e a profundidade não mudam entre dois quadros, e
     * a luz muda devagar.
     */
    void tick(Minecraft mc) {
        World world = mc.theWorld;
        if (world == null || ClientSea.rendererFailed() || !ClientSea.active()) {
            return;
        }
        try {
            chunks.attach(world);
            chunks.ensureRadius(mc.gameSettings.renderDistanceChunks);
            chunks.maintain(OceanConfig.rereadPerTick, OceanConfig.lightPerTick);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /*
     * Com shaderpack o passe translúcido do terreno roda também na passagem de sombra. Ali o mar vai com uma malha
     * grossa própria (células de 4 blocos ou mais: o mapa de sombra tem pouca resolução, e com shaderpack cada vértice
     * custa caro no Tessellator do Angelica), sem recorte da câmera (a luz vê o que a câmera não vê).
     */
    private long frame;
    private final FloatBuffer shadowBuffer = new FloatBuffer();

    /** Um quadro novo (RenderTickEvent). */
    public void newFrame() {
        frame++;
    }

    // o que é do quadro (prepare), lido pelos dois passes
    private long preparedFrame = -1;
    private long preparedAt;
    private World preparedWorld;
    private WaveSet waves;
    private double camX, camY, camZ, rest, seaTime;
    private int radius, ccx, ccz;

    /**
     * O trabalho do quadro, feito uma vez (a passagem de sombra e o passe principal dividem): conferir se a versão do
     * mar mudou, a chegada e a releitura de chunks novos, as costas, o tempo do mar, os fasores e o céu.
     */
    private void prepare(Minecraft mc, World world, EntityLivingBase view, float partial, boolean pack) {
        long now = System.nanoTime();
        if (frame == preparedFrame && world == preparedWorld && now - preparedAt < PREPARED_STALE_NANOS) {
            return;
        }
        preparedFrame = frame;
        preparedAt = now;
        preparedWorld = world;
        radius = mc.gameSettings.renderDistanceChunks;
        chunks.attach(world);
        chunks.ensureRadius(radius);
        waves = ClientSea.waves();
        if (ClientSea.version() != meshedVersion || mesher == null) {
            mesher = new OceanMesher(waves);
            meshedVersion = ClientSea.version();
            chunks.invalidateAll();
        }
        camX = RenderManager.renderPosX;
        camY = RenderManager.renderPosY;
        camZ = RenderManager.renderPosZ;
        lastCamX = camX;
        lastCamZ = camZ;
        rest = ClientSea.restLevel();
        seaTime = ClientSea.time(partial);
        ccx = (int) Math.floor(camX) >> 4;
        ccz = (int) Math.floor(camZ) >> 4;
        chunks.prune(ccx, ccz, radius);
        // Do mais perto para o mais longe, com um orçamento de tempo (não de contagem): a face de cima da água
        // vanilla já some em todo chunk de mar, então cada chunk ainda não lido é um buraco; num quadro lento (PC
        // fraco, shaderpack, logo depois de um teleporte) um limite fixo por quadro deixava buracos por dezenas de
        // segundos.
        int rebuilds = 0;
        int[] order = spiral(radius);
        long budget = now + REBUILD_BUDGET_NANOS;
        for (int o = 0; o < order.length; o += 2) {
            int dx = order[o], dz = order[o + 1];
            OceanChunks.Column c = chunks.get(ccx + dx, ccz + dz);
            if (!c.dirty && chunks.arrived(c)) {
                c.dirty = true;
            }
            if (c.dirty) {
                if (rebuilds >= OceanConfig.rebuildsPerFrame && dx * dx + dz * dz > 4 && System.nanoTime() > budget) {
                    continue;
                }
                chunks.rebuild(c);
                rebuilds++;
            }
        }
        // as costas dos que mudaram: com orçamento, do mais perto, trocando só o que concorda na borda
        chunks.updateShores(waves, ccx, ccz, OceanConfig.shoresPerFrame);
        int forced = chunks.shores().forced;
        if (forced != reportedForced) {
            OceanWavesMod.LOG.warn(
                "{} chunk(s) trocaram a costa sem as bordas concordarem (esperaram demais pelos vizinhos)",
                forced - reportedForced);
            reportedForced = forced;
        }
        mesher.grid()
            .beginFrame(seaTime, ccx - radius - 1, ccz - radius - 1, 2 * radius + 3, 2 * radius + 3, 16);
        shader.frame(world, view, partial, pack, texture.tint(), waves.significantHeight);
        edge.frame(camX, camZ, radius * 16.0, waves);
    }

    private int reportedForced;

    /** Os chunks a desenhar neste passe, do mais perto para o mais longe. */
    private void collect(Minecraft mc, EntityLivingBase view, float partial, boolean shadow, boolean pack) {
        visible.clear();
        double reach = waves.maxAmplitude + waves.meanLevel + 1;
        double coneRadius = Math.sqrt(12 * 12 * 2 + reach * reach);
        boolean useFrustum = !shadow && !pack;
        if (useFrustum) {
            if (frustum == null) {
                frustum = new Frustrum();
            }
            // o construtor do Frustrum relê as matrizes do GL na instância única do vanilla; é isto que o faz
            ClippingHelperImpl.getInstance();
            frustum.setPosition(camX, camY, camZ);
        } else if (!shadow) {
            cone.set(mc, view, partial, camX, camY, camZ);
        }
        int[] order = spiral(radius);
        for (int o = 0; o < order.length; o += 2) {
            OceanChunks.Column c = chunks.peek(ccx + order[o], ccz + order[o + 1]);
            // sem costa pronta o chunk não é desenhado (nunca com uma costa que discorda da dos vizinhos)
            if (c == null || !c.any || c.shore == null) {
                continue;
            }
            double x0 = c.cx * 16.0, z0 = c.cz * 16.0;
            // com shaderpack o Iris não atualiza o frustum vanilla (ClippingHelperImpl) para a câmera: visto no BSL,
            // ele descartava quase todos os chunks. Ali o recorte fica com o pipeline do pack.
            if (shadow) {
                // a luz vê o que a câmera não vê: tudo no alcance
            } else if (pack ? !cone.contains(x0 + 8, rest, z0 + 8, coneRadius)
                : !frustum.isBoxInFrustum(x0 - 4, rest - reach, z0 - 4, x0 + 20, rest + reach, z0 + 20)) {
                    continue;
                }
            visible.add(c);
        }
        // do mais perto para o mais longe (a espiral já vem quase assim: inserção, sem alocar)
        NearestFirst.sort(visible, camX, camZ);
    }

    /**
     * Para ver em jogo o que acontece quando o desenho falha (a água do terreno volta, o F3 avisa):
     * {@code -Doceanwaves.failRenderAt=N} lança uma exceção no N-ésimo desenho, com o Tessellator já aberto (o pior
     * lugar). Sem a chave, nada.
     */
    private static final int FAIL_AT = Integer.getInteger("oceanwaves.failRenderAt", 0);
    private int drawCount;

    private void draw(float partial, boolean shadow, boolean pack) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        EntityLivingBase view = mc.renderViewEntity;
        if (world == null || view == null) {
            return;
        }
        long t0 = System.nanoTime();
        prepare(mc, world, view, partial, pack);
        collect(mc, view, partial, shadow, pack);
        double base = pack ? Math.max(OceanConfig.minSpacing, OceanConfig.shaderMinSpacing) : OceanConfig.minSpacing;
        double minSpacing = shadow ? Math.max(4, base) : base;
        FloatBuffer buffer = shadow ? shadowBuffer : this.buffer;
        SpectralLod lod = lodMemo[shadow ? 1 : 0]
            .get(camX, camY, camZ, rest, minSpacing, OceanConfig.lodFactor, 2.5, 5.0);
        buffer.clear();
        spans.reset(visible.size());
        int quads = 0;
        for (int i = 0; i < visible.size(); i++) {
            OceanChunks.Column c = visible.get(i);
            spans.begin(i, buffer.size);
            double s = OceanMesher.spacingFor(lod, c.cx, c.cz, minSpacing);
            neighbours[0] = OceanMesher.spacingFor(lod, c.cx - 1, c.cz, minSpacing);
            neighbours[1] = OceanMesher.spacingFor(lod, c.cx + 1, c.cz, minSpacing);
            neighbours[2] = OceanMesher.spacingFor(lod, c.cx, c.cz - 1, minSpacing);
            neighbours[3] = OceanMesher.spacingFor(lod, c.cx, c.cz + 1, minSpacing);
            shader.chunk(c, camX, camY, camZ, rest);
            Attenuation att = OceanConfig.edgeFade ? edge.around(c.shore, c.cx * 16.0, c.cz * 16.0) : c.shore;
            quads += mesher.mesh(c.cx, c.cz, s, neighbours, c.open, seaTime, lod, att, shader, buffer);
        }
        spans.end(buffer.size);
        long t1 = System.nanoTime();

        submit(mc, partial, shadow, pack, buffer);
        long t2 = System.nanoTime();
        if (shadow) {
            // as medidas do F3 e do harness são as da passagem principal
            return;
        }

        lastMeshNanos = t1 - t0;
        lastDrawNanos = t2 - t1;
        lastChunks = visible.size();
        lastQuads = quads;
        lastComponents = waves.count;
        avgMesh += (lastMeshNanos - avgMesh) * 0.05;
        avgDraw += (lastDrawNanos - avgDraw) * 0.05;
        avgGl += (lastGlNanos - avgGl) * 0.05;
    }

    private void submit(Minecraft mc, float partial, boolean shadow, boolean pack, FloatBuffer buffer) {
        boolean own = shader.own;
        // o estado que o vanilla deixa ao fim do passe translúcido não muda de um quadro para o outro: lido de vez em
        // quando e quando a situação muda, não todo quadro
        StateMemo memo = stateMemo[shadow ? 1 : 0];
        long situation = (shadow ? 1 : 0) | (pack ? 2 : 0)
            | (mc.gameSettings.fancyGraphics ? 4 : 0)
            | (mc.gameSettings.anaglyph ? 8 : 0);
        if (memo.due(situation)) {
            memo.store(
                situation,
                GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                GL11.glIsEnabled(GL11.GL_CULL_FACE),
                GL11.glIsEnabled(GL11.GL_BLEND));
        }
        boolean depthMask = memo.depthMask;
        boolean cull = memo.cull;
        boolean blend = memo.blend;
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
        if (!own && pack) {
            IrisBridge.begin();
        }
        boolean started = false, drawn = false;
        try {
            tess.startDrawingQuads();
            started = true;
            if (FAIL_AT > 0 && ++drawCount == FAIL_AT) {
                throw new IllegalStateException("falha de teste (-Doceanwaves.failRenderAt=" + FAIL_AT + ")");
            }
            // o laço de envio (cor, luz e normal só quando mudam) é o VertexEmitter, que roda também fora do jogo
            sink.use(tess);
            VertexEmitter.emit(sink, visible, spans, buffer.data, own, camX, camY, camZ, rest);
            long d0 = System.nanoTime();
            if (diagnostics) {
                lastProgram = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
                lastPhase = IrisBridge.phase();
            }
            tess.draw();
            drawn = true;
            lastGlNanos = System.nanoTime() - d0;
        } finally {
            if (started && !drawn) {
                // uma exceção no meio: o Tessellator não pode ficar aberto (o vanilla travaria no próximo uso)
                try {
                    tess.draw();
                } catch (RuntimeException ignored) {
                    // já fechado
                }
            }
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
        stateMemo[0].invalidate();
        stateMemo[1].invalidate();
        preparedFrame = -1;
    }
}
