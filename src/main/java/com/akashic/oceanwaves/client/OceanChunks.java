package com.akashic.oceanwaves.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.akashic.oceanwaves.core.ShoreField;
import com.akashic.oceanwaves.core.WaveSet;
import com.akashic.oceanwaves.world.OceanColumns;

/**
 * O que o renderizador precisa saber de cada chunk, lido do mundo uma vez e guardado: quais colunas são mar aberto, a
 * profundidade, a cor da água do bioma, a luz, e a atenuação da costa (que precisa dos vizinhos). Fica sabendo das
 * mudanças como um {@link IWorldAccess} do mundo do cliente (bloco mudou, chunk chegou ou saiu).
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
        boolean shoreDirty = true;
        ShoreField shore;

        Column(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private final Map<Long, Column> map = new HashMap<>();
    private World world;
    private int lightCursor;

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    void attach(World w) {
        if (w == world) {
            return;
        }
        map.clear();
        world = w;
        if (w != null) {
            w.addWorldAccess(this);
        }
    }

    int size() {
        return map.size();
    }

    /** {lidos, sujos, com mar} (diagnóstico). */
    int[] census() {
        int loaded = 0, dirty = 0, any = 0;
        for (Column c : map.values()) {
            loaded += c.loaded ? 1 : 0;
            dirty += c.dirty ? 1 : 0;
            any += c.any ? 1 : 0;
        }
        return new int[] { loaded, dirty, any };
    }

    Column get(int cx, int cz) {
        return map.computeIfAbsent(key(cx, cz), k -> new Column(cx, cz));
    }

    /** O mar mudou (a costa depende da altura das ondas): refazer as atenuações. */
    void invalidateShores() {
        for (Column c : map.values()) {
            c.shoreDirty = true;
        }
    }

    void invalidateAll() {
        for (Column c : map.values()) {
            c.dirty = true;
            c.shoreDirty = true;
        }
    }

    /** Esquece o que ficou longe (a renderização só pede o que está dentro do alcance). */
    void prune(int ccx, int ccz, int radius) {
        Iterator<Column> it = map.values()
            .iterator();
        while (it.hasNext()) {
            Column c = it.next();
            if (Math.abs(c.cx - ccx) > radius + 2 || Math.abs(c.cz - ccz) > radius + 2) {
                it.remove();
            }
        }
    }

    /**
     * Um chunk que ainda não tinha chegado chegou? Não dá para contar só com o aviso do mundo
     * ({@code markBlockRangeForRenderUpdate}): com o Celeritas do Angelica ele não chega aos {@code IWorldAccess}
     * registrados (visto no pack real: 213 chunks no cache, 15 lidos). Conferir é barato (uma busca no mapa de chunks).
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
     * Relê um chunk já lido (rodízio, para mudanças cujo aviso se perdeu) e só refaz a costa dos vizinhos se as colunas
     * mudaram de verdade.
     */
    void refresh(Column c) {
        if (!c.loaded) {
            return;
        }
        System.arraycopy(c.open, 0, oldOpen, 0, 256);
        System.arraycopy(c.depth, 0, oldDepth, 0, 256);
        boolean wasAny = c.any;
        read(c, false);
        if (c.any != wasAny || !java.util.Arrays.equals(oldOpen, c.open)
            || !java.util.Arrays.equals(oldDepth, c.depth)) {
            markShores(c);
        }
    }

    /** Relê as colunas de um chunk. Verdadeiro se leu (o chunk estava carregado). */
    boolean rebuild(Column c) {
        return read(c, true);
    }

    private boolean read(Column c, boolean neighbours) {
        World w = world;
        c.dirty = false;
        if (w == null) {
            return false;
        }
        Chunk chunk = w.getChunkFromChunkCoords(c.cx, c.cz);
        c.loaded = !chunk.isEmpty();
        c.any = false;
        if (!c.loaded) {
            java.util.Arrays.fill(c.open, false);
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
                    c.color[i] = Blocks.water.colorMultiplier(w, wx, y, wz);
                    c.light[i] = w.getLightBrightnessForSkyBlocks(wx, y + 1, wz, 0);
                } else {
                    c.depth[i] = 0;
                }
            }
        }
        fillClosed(c, w, y, true);
        if (neighbours) {
            markShores(c);
        }
        return true;
    }

    /** A costa deste chunk e dos vizinhos (até a folga máxima, 2 chunks) depende das colunas dele. */
    private void markShores(Column c) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                Column n = map.get(key(c.cx + dx, c.cz + dz));
                if (n != null) {
                    n.shoreDirty = true;
                }
            }
        }
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

    int nextLightCursor() {
        return lightCursor++;
    }

    /** A atenuação da costa de um chunk, das colunas dele e dos vizinhos (vizinho desconhecido conta como mar). */
    void rebuildShore(Column c, WaveSet waves) {
        c.shoreDirty = false;
        // a folga tem de cobrir o alcance inteiro da atenuação (a costa e o limite de gradiente): com menos, dois
        // chunks vizinhos calculam valores diferentes na borda comum e a superfície abre uma fresta ali
        int m = (int) Math
            .ceil(ShoreField.influenceRadius(ClientSea.shoreWidth(), waves.lipschitz, waves.maxDisplacement)) + 1;
        m = Math.min(m, 32);
        int size = 16 + 2 * m;
        boolean[] open = new boolean[size * size];
        float[] depth = new float[size * size];
        int ox = (c.cx << 4) - m, oz = (c.cz << 4) - m;
        com.akashic.oceanwaves.core.SeaRegion region = ClientSea.region();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int wx = ox + x, wz = oz + z;
                Column n = map.get(key(wx >> 4, wz >> 4));
                int i = z * size + x;
                if (n == null || n.dirty || !n.loaded) {
                    // desconhecido conta como mar, menos fora da região (a borda dela é costa)
                    open[i] = region.containsColumn(wx, wz);
                    depth[i] = 64;
                } else {
                    int j = (wz & 15) * 16 + (wx & 15);
                    open[i] = n.open[j];
                    depth[i] = n.depth[j];
                }
            }
        }
        c.shore = ShoreField.build(
            ox,
            oz,
            size,
            size,
            open,
            depth,
            ClientSea.shoreWidth(),
            ClientSea.breakerIndex(),
            waves.significantHeight,
            waves.lipschitz,
            waves.maxDisplacement);
    }

    private void markChunk(int cx, int cz) {
        Column c = map.get(key(cx, cz));
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
