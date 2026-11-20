package com.akashic.oceanwaves.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.akashic.oceanwaves.OceanConfig;
import com.akashic.oceanwaves.OceanWavesMod;
import com.akashic.oceanwaves.core.OceanClock;
import com.akashic.oceanwaves.core.SeaRegion;
import com.akashic.oceanwaves.core.SeaState;
import com.akashic.oceanwaves.core.Synthesis;
import com.akashic.oceanwaves.core.WaveSet;
import com.akashic.oceanwaves.net.MessageSeaState;
import com.akashic.oceanwaves.world.OceanColumns;

/**
 * O mar do lado do cliente: o estado em vigor (o do servidor, ou o da própria configuração quando o servidor não
 * tem o mod), as componentes e o relógio. Os campos lidos pela montagem de chunks (que no Angelica roda em outras
 * threads) são voláteis e imutáveis.
 */
public final class ClientSea {

    public static final OceanClock CLOCK = new OceanClock();

    private static volatile boolean active;
    private static volatile int seaLevel = 63;
    private static volatile SeaState state;
    private static volatile WaveSet waves = WaveSet.empty();
    private static volatile int version;
    private static volatile SeaRegion region = SeaRegion.EVERYWHERE;
    private static double shoreWidth = 10;
    private static double breakerIndex = 0.78;
    private static boolean fromServer;
    private static volatile MessageSeaState pending;
    private static final RendererHealth HEALTH = new RendererHealth();
    private static final RemountRequest REMOUNT = new RemountRequest();
    private static World world;
    private static int dimension = Integer.MIN_VALUE;

    private ClientSea() {}

    public static boolean active() {
        return active;
    }

    public static int seaLevel() {
        return seaLevel;
    }

    public static double restLevel() {
        return seaLevel - 1 + OceanColumns.SOURCE_TOP;
    }

    public static SeaState state() {
        return state;
    }

    public static WaveSet waves() {
        return waves;
    }

    /** Muda toda vez que o mar muda (o renderizador refaz o que depende dele). */
    public static int version() {
        return version;
    }

    public static double shoreWidth() {
        return shoreWidth;
    }

    public static double breakerIndex() {
        return breakerIndex;
    }

    public static boolean fromServer() {
        return fromServer;
    }

    /** Chamado da thread da rede. */
    public static void receive(MessageSeaState m) {
        pending = m;
    }

    /** O tempo total do mundo, do pacote vanilla de tempo (thread da rede ou principal). */
    public static void observeTime(long totalWorldTime) {
        CLOCK.observe(totalWorldTime, System.nanoTime());
    }

    /** O tempo do mar agora, em segundos. */
    public static double time(float partialTicks) {
        if (CLOCK.synced()) {
            return CLOCK.secondsAt(System.nanoTime());
        }
        World w = world;
        return w == null ? 0 : (w.getTotalWorldTime() + partialTicks) / 20.0;
    }

    /**
     * A face de cima da água vanilla some onde o mod desenha a superfície, e só enquanto ele a desenha: se o
     * renderizador falhou, a face volta (sem isto o oceano some por inteiro). Chamado na montagem de chunks.
     */
    public static boolean hideTopFace(IBlockAccess w, int x, int y, int z) {
        return HEALTH.hidesTopFace(active) && y == seaLevel - 1
            && region.containsColumn(x, z)
            && OceanColumns.isOpenSea(w, x, y, z);
    }

    /** O renderizador falhou e se desligou nesta sessão (até a troca de mundo). */
    public static boolean rendererFailed() {
        return HEALTH.failed();
    }

    /** O motivo da falha, para o F3. */
    static String rendererFailure() {
        return HEALTH.reason();
    }

    /**
     * O renderizador falhou. Aqui só se publica o estado (volátil): o terreno é remontado no próximo tick do cliente,
     * não dentro do {@code catch} do desenho.
     */
    static void reportRendererFailure(Throwable cause) {
        HEALTH.fail(cause);
    }

    /** Onde há ondas (fora dela a água é a vanilla). */
    public static SeaRegion region() {
        return region;
    }

    /** A cada tick do cliente. */
    static void tick(Minecraft mc) {
        World w = mc.theWorld;
        if (w != world) {
            world = w;
            CLOCK.reset();
            HEALTH.reset();
            dimension = Integer.MIN_VALUE;
            // Com o mod no servidor, o mar de cada mundo vem dele na troca de mundo; a mensagem vem pela thread da rede
            // e pode chegar antes do pacote de troca de mundo (já aplicada aqui). Só sem conexão volta a configuração.
            if (w == null) {
                fromServer = false;
            }
        }
        MessageSeaState m = pending;
        if (m != null) {
            pending = null;
            fromServer = true;
            dimension = m.dimension;
            setSeaLevel(m.seaLevel);
            shoreWidth = m.shoreWidth;
            breakerIndex = m.breakerIndex;
            setState(m.state);
            setRegion(m.region);
            OceanWavesMod.LOG.info("Mar do servidor (dimensão {}): {}; ondas em {}", m.dimension, m.state, m.region);
        } else if (w != null && !fromServer && w.provider.dimensionId != dimension) {
            // servidor sem o mod: o mar da configuração do cliente
            dimension = w.provider.dimensionId;
            setSeaLevel(OceanConfig.seaLevel);
            shoreWidth = OceanConfig.shoreWidth;
            breakerIndex = OceanConfig.breakerIndex;
            setState(OceanConfig.dimensions.contains(dimension) ? OceanConfig.seaState() : null);
            net.minecraft.util.ChunkCoordinates spawn = w.getSpawnPoint();
            setRegion(OceanConfig.region(spawn.posX, spawn.posZ));
        }
        boolean want = w != null && OceanConfig.render && state != null && waves.count > 0;
        if (want != active) {
            active = want;
            // a face de cima da água vanilla depende disto: remontar os chunks
            REMOUNT.activation();
        }
        if (HEALTH.takeRemount()) {
            // o renderizador falhou: a face de cima volta
            REMOUNT.failure();
        }
        // um pedido só por tick, com o estado já atualizado
        if (REMOUNT.take(active) && mc.renderGlobal != null && w != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    /**
     * A região mudou: a face de cima da água vanilla muda junto, então os chunks são remontados (e o renderizador
     * refaz o cache dele, que lê a região).
     */
    private static void setRegion(SeaRegion r) {
        if (r.equals(region)) {
            return;
        }
        region = r;
        version++;
        REMOUNT.region();
    }

    /**
     * O nível do mar mudou (o servidor mede depois de o cliente já ter montado chunks com outro): a regra da face
     * vanilla ({@code y == nível − 1}) e tudo o que o cache leu valem para o nível antigo. Como na mudança de região, o
     * cache inteiro é invalidado (a versão sobe) e o terreno é remontado, se o mar está ativo.
     */
    private static void setSeaLevel(int level) {
        if (level == seaLevel) {
            return;
        }
        seaLevel = level;
        version++;
        REMOUNT.level();
    }

    private static void setState(SeaState s) {
        if (s == null ? state == null : s.equals(state)) {
            return;
        }
        state = s;
        waves = s == null ? WaveSet.empty() : Synthesis.build(s);
        version++;
    }

    /** Liga ou desliga o desenho (a configuração já foi mudada). */
    static void refresh() {
        dimension = Integer.MIN_VALUE;
    }
}
