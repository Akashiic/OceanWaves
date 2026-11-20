package com.akashic.oceanwaves.client;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.akashic.oceanwaves.core.Attenuation;
import com.akashic.oceanwaves.core.WaveSet;
import com.akashic.oceanwaves.world.OceanColumns;

/**
 * O que o renderizador precisa saber de cada chunk, lido do mundo uma vez e guardado: quais colunas são mar aberto, a
 * profundidade, a cor da água do bioma, a luz, e a atenuação da costa (que precisa dos vizinhos). Fica sabendo das
 * mudanças como um {@link IWorldAccess} do mundo do cliente (bloco mudou, chunk chegou ou saiu).
 *
 * <p>
 * Os chunks ficam num {@link ColumnRing}; a costa é refeita aos poucos pelo {@link ShoreScheduler} (com orçamento por
 * quadro e só trocando quando as bordas concordam). A releitura em rodízio (mudanças cujo aviso se perdeu, luz) roda
 * no tick do cliente ({@link #maintain}), não no quadro.
 */
final class OceanChunks implements IWorldAccess {

    static final class Column {

        final int cx;
        final int cz;
        final boolean[] open = new boolean[256];
        final float[] depth = new float[256];
        final int[] color = new int[256];
        final int[] light = new int[256];
        boolean any;
        boolean loaded;
        boolean dirty = true;
        /** Sobe a cada vez que algo que a costa deste chunk lê mudou (ele ou um vizinho a até 2 chunks). */
        int shoreGen = 1;
        /** A geração para a qual {@link #next} foi calculada (0: nunca). */
        int builtGen;
        /** A costa calculada, esperando concordar com os vizinhos desenhados para passar a valer. */
        Attenuation next;
        /** A costa que o desenho usa (null: ainda não tem, o chunk não é desenhado). Nunca null depois de pronta. */
        Attenuation shore;
        // rascunho do ShoreScheduler
        boolean ready;
        boolean publishOk;
        int waited;

        Column(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private final ColumnRing ring = new ColumnRing();
    private final ShoreScheduler scheduler = new ShoreScheduler();
    private final ShoreWindow window = new ShoreWindow();
    private final ShoreScheduler.Builder shoreBuilder = this::buildShore;
    private WaveSet shoreWaves;
    private World world;
    private int refreshCursor;
    private int lightCursor;

    void attach(World w) {
        if (w == world) {
            return;
        }
        ring.clear();
        world = w;
        if (w != null) {
            w.addWorldAccess(this);
        }
    }

    /** O anel cobre o alcance de renderização (em chunks). */
    void ensureRadius(int radius) {
        ring.ensure(radius);
    }

    int size() {
        return ring.size();
    }

    ShoreScheduler shores() {
        return scheduler;
    }

    /** {lidos, sujos, com mar} (diagnóstico). */
    int[] census() {
        int loaded = 0, dirty = 0, any = 0;
        for (int i = 0; i < ring.capacity(); i++) {
            Column c = ring.at(i);
            if (c != null) {
                loaded += c.loaded ? 1 : 0;
                dirty += c.dirty ? 1 : 0;
                any += c.any ? 1 : 0;
            }
        }
        return new int[] { loaded, dirty, any };
    }

    Column get(int cx, int cz) {
        return ring.get(cx, cz);
    }

    /** O chunk se está guardado, senão null (não cria). */
    Column peek(int cx, int cz) {
        return ring.peek(cx, cz);
    }

    /**
     * O mar mudou (as ondas, o nível, a região): tudo é lido de novo, e as costas calculadas para o mar de antes não
     * valem para o de agora (a atenuação que mantém a superfície sem dobrar depende das ondas): o chunk só volta a ser
     * desenhado quando a costa nova ficar pronta.
     */
    void invalidateAll() {
        for (int i = 0; i < ring.capacity(); i++) {
            Column c = ring.at(i);
            if (c != null) {
                c.dirty = true;
                c.shoreGen++;
                c.shore = null;
                c.next = null;
                c.builtGen = 0;
            }
        }
    }

    /** Esquece o que ficou longe (a renderização só pede o que está dentro do alcance). */
    void prune(int ccx, int ccz, int radius) {
        ring.prune(ccx, ccz, radius);
    }

    /**
     * Um chunk que ainda não tinha chegado chegou? Não dá para contar só com o aviso do mundo
     * ({@code markBlockRangeForRenderUpdate}): com o Celeritas do Angelica ele não chega aos {@code IWorldAccess}
     * registrados (visto no pack real: 213 chunks no cache, 15 lidos). Conferir é barato (uma busca no mapa de chunks).
     * Roda por quadro, só para os que ainda não foram lidos.
     */
    boolean arrived(Column c) {
        World w = world;
        return w != null && !c.loaded
            && !w.getChunkFromChunkCoords(c.cx, c.cz)
                .isEmpty();
    }

    private final boolean[] oldOpen = new boolean[256];
    private final float[] oldDepth = new float[256];

    /**
     * Relê um chunk já lido (rodízio, para mudanças cujo aviso se perdeu). Não refaz a cor da água (é a do bioma, não
     * muda de um quadro para o outro: cada coluna custa um evento de cor); a costa dos vizinhos só é remarcada se as
     * colunas mudaram de verdade.
     */
    void refresh(Column c) {
        if (c.loaded) {
            read(c, false);
        }
    }

    /** Relê as colunas de um chunk. Verdadeiro se leu (o chunk estava carregado). */
    boolean rebuild(Column c) {
        return read(c, true);
    }

    /**
     * O rodízio que antes rodava a cada quadro, agora a cada tick do cliente: {@code full} chunks relidos por inteiro e
     * a luz de {@code light}, percorrendo o anel (inclusive os chunks sem mar: um bloco pode tê-los aberto).
     */
    void maintain(int full, int light) {
        int cap = ring.capacity();
        if (cap == 0) {
            return;
        }
        for (int n = 0, steps = 0; n < full && steps < cap; steps++) {
            refreshCursor = (refreshCursor + 1) % cap;
            Column c = ring.at(refreshCursor);
            if (c != null && c.loaded && !c.dirty) {
                refresh(c);
                n++;
            }
        }
        for (int n = 0, steps = 0; n < light && steps < cap; steps++) {
            lightCursor = (lightCursor + 1) % cap;
            Column c = ring.at(lightCursor);
            if (c != null && c.any && !c.dirty) {
                refreshLight(c);
                n++;
            }
        }
    }

    private boolean read(Column c, boolean colors) {
        World w = world;
        c.dirty = false;
        if (w == null) {
            return false;
        }
        boolean wasLoaded = c.loaded, wasAny = c.any;
        System.arraycopy(c.open, 0, oldOpen, 0, 256);
        System.arraycopy(c.depth, 0, oldDepth, 0, 256);
        Chunk chunk = w.getChunkFromChunkCoords(c.cx, c.cz);
        c.loaded = !chunk.isEmpty();
        c.any = false;
        if (!c.loaded) {
            java.util.Arrays.fill(c.open, false);
            java.util.Arrays.fill(c.depth, 0f);
            if (wasLoaded) {
                // os vizinhos passam a ver "desconhecido" aqui
                markShores(c);
            }
            return false;
        }
        int y = ClientSea.seaLevel() - 1;
        int bx = c.cx << 4, bz = c.cz << 4;
        com.akashic.oceanwaves.core.SeaRegion region = ClientSea.region();
        boolean inRegion = region.touches(bx, bz, bx + 15, bz + 15);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int wx = bx + x, wz = bz + z;
                boolean o = inRegion && region.containsColumn(wx, wz) && OceanColumns.isOpenSea(w, wx, y, wz);
                c.open[i] = o;
                if (o) {
                    c.any = true;
                    c.depth[i] = OceanColumns.depth(w, wx, y, wz, 64);
                    if (colors) {
                        c.color[i] = Blocks.water.colorMultiplier(w, wx, y, wz);
                    }
                    c.light[i] = w.getLightBrightnessForSkyBlocks(wx, y + 1, wz, 0);
                } else {
                    c.depth[i] = 0;
                }
            }
        }
        fillClosed(c, w, y, colors);
        // só o que muda a costa dos vizinhos a remarca: reler o que não mudou não custa 25 costas
        if (!wasLoaded || c.any != wasAny
            || !java.util.Arrays.equals(oldOpen, c.open)
            || !java.util.Arrays.equals(oldDepth, c.depth)) {
            markShores(c);
        }
        return true;
    }

    /** A costa deste chunk e dos vizinhos (até a folga máxima, 2 chunks) depende das colunas dele. */
    private void markShores(Column c) {
        ring.touchShores(c.cx, c.cz, ShoreWindow.MAX_MARGIN / 16);
    }

    /** Relê a luz de um chunk (a luz muda sem aviso barato: dia, tochas). */
    void refreshLight(Column c) {
        World w = world;
        if (w == null || !c.any) {
            return;
        }
        int y = ClientSea.seaLevel() - 1;
        int bx = c.cx << 4, bz = c.cz << 4;
        for (int i = 0; i < 256; i++) {
            if (c.open[i]) {
                c.light[i] = w.getLightBrightnessForSkyBlocks(bx + (i & 15), y + 1, bz + (i >> 4), 0);
            }
        }
        fillClosed(c, w, y, false);
    }

    /**
     * A cor e a luz das colunas que não são de mar. Cada vértice da malha lê a coluna em cujo canto está (a de índice
     * {@code (int) u}): os vértices da borda +x e +z de uma coluna de mar leem a vizinha. Se ela é terra ou um bloco
     * (a laje de um cais), sem isto valia 0: cor preta e luz nenhuma, e o preto se espalhava pelo triângulo (manchas
     * escuras na água junto aos blocos, vistas no spawn do TEOF). Vale a luz mais clara das colunas de mar vizinhas
     * (céu e tochas em separado), ou a de um bloco acima, e a cor da água do bioma, que não depende do bloco.
     */
    private static void fillClosed(Column c, World w, int y, boolean colors) {
        int bx = c.cx << 4, bz = c.cz << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                if (c.open[i]) {
                    continue;
                }
                int sky = -1, block = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, nz = z + dz;
                        if (nx < 0 || nz < 0 || nx > 15 || nz > 15 || !c.open[nz * 16 + nx]) {
                            continue;
                        }
                        int l = c.light[nz * 16 + nx];
                        sky = Math.max(sky, l >> 20 & 15);
                        block = Math.max(block, l >> 4 & 15);
                    }
                }
                int wx = bx + x, wz = bz + z;
                if (sky < 0) {
                    int a = w.getLightBrightnessForSkyBlocks(wx, y + 1, wz, 0);
                    int b = w.getLightBrightnessForSkyBlocks(wx, y + 2, wz, 0);
                    sky = Math.max(a >> 20 & 15, b >> 20 & 15);
                    block = Math.max(a >> 4 & 15, b >> 4 & 15);
                }
                c.light[i] = sky << 20 | block << 4;
                if (colors) {
                    c.color[i] = Blocks.water.colorMultiplier(w, wx, y, wz);
                }
            }
        }
    }

    /**
     * Refaz, com orçamento, a atenuação da costa dos chunks que mudaram (e troca as que concordam com os vizinhos). Ver
     * {@link ShoreScheduler}.
     */
    void updateShores(WaveSet waves, int camCx, int camCz, int budget) {
        shoreWaves = waves;
        scheduler.update(ring, shoreBuilder, camCx, camCz, budget);
    }

    private Attenuation buildShore(Column c) {
        return window.build(ring, c, shoreWaves, ClientSea.shoreWidth(), ClientSea.breakerIndex(), ClientSea.region());
    }

    private void markChunk(int cx, int cz) {
        Column c = ring.peek(cx, cz);
        if (c != null) {
            c.dirty = true;
        }
    }

    @Override
    public void markBlockForUpdate(int x, int y, int z) {
        int sea = ClientSea.seaLevel();
        if (y <= sea + 1 && y >= sea - 66) {
            markChunk(x >> 4, z >> 4);
        }
    }

    @Override
    public void markBlockForRenderUpdate(int x, int y, int z) {
        markBlockForUpdate(x, y, z);
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        int sea = ClientSea.seaLevel();
        if (y2 < sea - 66 || y1 > sea + 1) {
            return;
        }
        for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
            for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
                markChunk(cx, cz);
            }
        }
    }

    @Override
    public void playSound(String soundName, double x, double y, double z, float volume, float pitch) {}

    @Override
    public void playSoundToNearExcept(EntityPlayer p, String s, double x, double y, double z, float v, float pitch) {}

    @Override
    public void spawnParticle(String p, double x, double y, double z, double vx, double vy, double vz) {}

    @Override
    public void onEntityCreate(Entity e) {}

    @Override
    public void onEntityDestroy(Entity e) {}

    @Override
    public void playRecord(String r, int x, int y, int z) {}

    @Override
    public void broadcastSound(int id, int x, int y, int z, int data) {}

    @Override
    public void playAuxSFX(EntityPlayer p, int id, int x, int y, int z, int data) {}

    @Override
    public void destroyBlockPartially(int id, int x, int y, int z, int progress) {}

    @Override
    public void onStaticEntitiesChanged() {}
}
