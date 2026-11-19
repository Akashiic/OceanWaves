package com.akashic.oceanwaves.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;

import com.akashic.oceanwaves.OceanConfig;
import com.akashic.oceanwaves.OceanWavesMod;
import com.akashic.oceanwaves.core.SeaRegion;
import com.akashic.oceanwaves.core.SeaState;
import com.akashic.oceanwaves.core.Synthesis;
import com.akashic.oceanwaves.core.WaveSet;
import com.akashic.oceanwaves.net.MessageSeaState;
import com.akashic.oceanwaves.net.OceanNet;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * O mar de cada mundo no servidor: a autoridade. Os mundos com ondas vêm da configuração ({@link OceanConfig#worlds}),
 * pelo nome da pasta (os do Multiverse, como {@code Akashic/Spawn}, ganham o número de dimensão só quando carregam, e
 * podem ganhar outro a cada início) ou pelo número. Manda o estado a quem entra, troca de mundo ou renasce, e a todos
 * do mundo quando muda. O tempo não precisa de pacote: o vanilla já manda o tempo total do mundo a cada segundo
 * (S03PacketTimeUpdate), e é dele que o relógio do cliente vive.
 *
 * <p>
 * Registrado nos dois barramentos: o do FML (jogadores, ticks) e o do Forge (mundos que carregam e descarregam).
 */
public final class ServerSea {

    public static final ServerSea INSTANCE = new ServerSea();

    private final Map<Integer, SeaState> states = new ConcurrentHashMap<>();
    private final Map<Integer, WaveSet> waves = new ConcurrentHashMap<>();
    private final Map<Integer, SeaRegion> regions = new ConcurrentHashMap<>();
    /** O nível do mar de cada mundo com ondas (o bloco de água mais alto é o nível − 1). */
    private final Map<Integer, Integer> levels = new ConcurrentHashMap<>();
    /** Mundos cujo nível ainda não deu para medir (as colunas em volta do spawn não estavam carregadas). */
    private final Map<Integer, Boolean> unmeasured = new ConcurrentHashMap<>();
    /** A dimensão do último mar mandado a cada jogador (o teleporte de plugin nem sempre passa pelos eventos). */
    private final Map<UUID, Integer> sent = new HashMap<>();
    private boolean running;
    /** As ondas estão no mundo principal porque nenhum mundo da configuração existe. */
    private boolean fallback;
    private int ticks;

    private ServerSea() {}

    public void start() {
        stop();
        running = true;
        for (WorldServer w : DimensionManager.getWorlds()) {
            consider(w);
        }
    }

    /** Depois de tudo carregado (o Multiverse carrega os mundos dele ao ligar, antes disto). */
    public void started() {
        if (states.isEmpty() && OceanConfig.mainWorldFallback && DimensionManager.getWorld(0) != null) {
            fallback = true;
            states.put(0, OceanConfig.seaState());
            level(DimensionManager.getWorld(0), null);
            OceanWavesMod.LOG.warn(
                "Nenhum dos mundos {} existe neste servidor: ondas no mundo principal (dimensão 0)",
                OceanConfig.worlds);
            OceanNet.channel.sendToDimension(message(0), 0);
        }
        OceanWavesMod.LOG.info("Ondas em: {}", describe());
    }

    public void stop() {
        running = false;
        fallback = false;
        states.clear();
        waves.clear();
        regions.clear();
        levels.clear();
        unmeasured.clear();
        sent.clear();
    }

    /** O nome de um mundo como a configuração o escreve: a pasta dele (a do Multiverse, a do Bukkit). */
    public static String worldName(World w) {
        String dir = w.getSaveHandler() == null ? null
            : w.getSaveHandler()
                .getWorldDirectoryName();
        return dir != null ? dir
            : w.getWorldInfo()
                .getWorldName();
    }

    /** O mundo é um dos da configuração? Pelo número da dimensão, pela pasta ou pelo nome do level.dat. */
    static boolean configured(World w) {
        return entry(w) != null;
    }

    /** A entrada da configuração que casa com o mundo (sem o @nível), ou null. */
    static String entry(World w) {
        int dim = w.provider.dimensionId;
        String dir = norm(
            w.getSaveHandler() == null ? null
                : w.getSaveHandler()
                    .getWorldDirectoryName());
        String level = norm(
            w.getWorldInfo()
                .getWorldName());
        for (String c : OceanConfig.worlds) {
            String n = norm(c);
            if (n.equals(dir) || n.equals(level) || n.equals(String.valueOf(dim)) || n.equals("dim" + dim)) {
                return c;
            }
        }
        return null;
    }

    /** O nível do mar do mundo com ondas (o da configuração geral para os outros). */
    public int seaLevel(int dimension) {
        Integer l = levels.get(dimension);
        return l != null ? l : OceanConfig.seaLevel;
    }

    /**
     * Mede o nível do mar de um mundo: o bloco mais alto de colunas em volta do spawn (uma grade de 13 × 13 pontos, de
     * 16 em 16 blocos, só em chunks já carregados); se a maioria das que acabam em água acaba no mesmo y, o nível é
     * y + 1. Null se não há colunas de água bastantes (chunks ainda não carregados, ou um mundo sem mar ali).
     */
    static Integer measure(WorldServer w) {
        ChunkCoordinates spawn = w.getSpawnPoint();
        Map<Integer, Integer> votes = new HashMap<>();
        int waterColumns = 0;
        for (int i = -6; i <= 6; i++) {
            for (int k = -6; k <= 6; k++) {
                int x = spawn.posX + i * 16, z = spawn.posZ + k * 16;
                if (!w.getChunkProvider()
                    .chunkExists(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = w.getActualHeight() - 1; y >= 0; y--) {
                    Material mat = w.getBlock(x, y, z)
                        .getMaterial();
                    if (mat == Material.air) {
                        continue;
                    }
                    if (mat == Material.water) {
                        votes.merge(y, 1, Integer::sum);
                        waterColumns++;
                    }
                    break;
                }
            }
        }
        int best = -1, count = 0;
        for (Map.Entry<Integer, Integer> v : votes.entrySet()) {
            if (v.getValue() > count) {
                best = v.getKey();
                count = v.getValue();
            }
        }
        return waterColumns >= 8 && count * 2 > waterColumns ? best + 1 : null;
    }

    /** Acerta o nível do mar do mundo: o da configuração ({@code nome@nível}), o medido, ou o geral. Diz qual. */
    private String level(WorldServer w, String entry) {
        int dim = w.provider.dimensionId;
        Integer given = entry == null ? null : OceanConfig.worldLevels.get(entry);
        if (given != null) {
            levels.put(dim, given);
            unmeasured.remove(dim);
            return "da configuração";
        }
        Integer measured = measure(w);
        if (measured != null) {
            levels.put(dim, measured);
            unmeasured.remove(dim);
            return "medido em volta do spawn";
        }
        levels.put(dim, OceanConfig.seaLevel);
        unmeasured.put(dim, Boolean.TRUE);
        return "o geral, até dar para medir";
    }

    private static String norm(String s) {
        return s == null ? ""
            : s.trim()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
    }

    private void consider(WorldServer w) {
        if (!running || w == null || w.isRemote || !configured(w)) {
            return;
        }
        int dim = w.provider.dimensionId;
        if (fallback && dim != 0) {
            // um mundo da configuração chegou depois: o principal deixa de ter ondas
            fallback = false;
            set(0, null);
        }
        if (!states.containsKey(dim)) {
            states.put(dim, OceanConfig.seaState());
            regions.remove(dim);
            waves.remove(dim);
            String how = level(w, entry(w));
            OceanWavesMod.LOG.info(
                "Ondas no mundo {} (dimensão {}): {}, nível do mar {} ({})",
                worldName(w),
                dim,
                region(dim),
                seaLevel(dim),
                how);
            OceanNet.channel.sendToDimension(message(dim), dim);
        }
    }

    /** As dimensões com ondas, com o nome de cada mundo. */
    public String describe() {
        List<String> out = new ArrayList<>();
        for (int d : states.keySet()) {
            WorldServer w = DimensionManager.getWorld(d);
            out.add((w == null ? "?" : worldName(w)) + " (dimensão " + d + ", nível do mar " + seaLevel(d) + ")");
        }
        return out.isEmpty() ? "nenhum mundo" : String.join(", ", out);
    }

    /** A dimensão do mundo com esse nome (pasta, nome do level.dat ou "DIM&lt;n&gt;"), ou null. */
    public static Integer dimensionOf(String name) {
        String n = norm(name);
        for (WorldServer w : DimensionManager.getWorlds()) {
            int d = w.provider.dimensionId;
            if (n.equals(norm(worldName(w))) || n.equals(
                norm(
                    w.getWorldInfo()
                        .getWorldName()))
                || n.equals("dim" + d)) {
                return d;
            }
        }
        // o mundo principal do Bukkit tem o nome do level-name (a pasta raiz)
        MinecraftServer s = MinecraftServer.getServer();
        if (s != null && n.equals(norm(s.getFolderName()))) {
            return 0;
        }
        return null;
    }

    /** Onde há ondas na dimensão (resolvida na primeira vez: o modo spawn lê o spawn do mundo). */
    public SeaRegion region(int dimension) {
        return regions.computeIfAbsent(dimension, d -> {
            WorldServer w = DimensionManager.getWorld(d);
            double x = 0, z = 0;
            if (w != null) {
                ChunkCoordinates spawn = w.getSpawnPoint();
                x = spawn.posX;
                z = spawn.posZ;
            }
            return OceanConfig.region(x, z);
        });
    }

    /** Troca a região da dimensão e avisa quem está nela. */
    public void setRegion(int dimension, SeaRegion region) {
        regions.put(dimension, region);
        OceanNet.channel.sendToDimension(message(dimension), dimension);
    }

    public SeaState state(int dimension) {
        return states.get(dimension);
    }

    public WaveSet waves(int dimension) {
        SeaState s = states.get(dimension);
        if (s == null) {
            return null;
        }
        return waves.computeIfAbsent(dimension, d -> Synthesis.build(s));
    }

    /** Troca o mar de uma dimensão (null: sem ondas) e avisa quem está nela. */
    public void set(int dimension, SeaState state) {
        if (state == null) {
            states.remove(dimension);
        } else {
            states.put(dimension, state);
        }
        waves.remove(dimension);
        OceanNet.channel.sendToDimension(message(dimension), dimension);
    }

    private MessageSeaState message(int dimension) {
        return new MessageSeaState(
            dimension,
            states.get(dimension),
            seaLevel(dimension),
            OceanConfig.shoreWidth,
            OceanConfig.breakerIndex,
            region(dimension));
    }

    private void send(EntityPlayerMP player) {
        sent.put(player.getUniqueID(), player.dimension);
        OceanNet.channel.sendTo(message(player.dimension), player);
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load e) {
        if (e.world instanceof WorldServer) {
            consider((WorldServer) e.world);
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload e) {
        if (e.world instanceof WorldServer && running) {
            int dim = e.world.provider.dimensionId;
            // o número pode voltar para outro mundo (o Crucible reaproveita os dos mundos de plugin)
            states.remove(dim);
            waves.remove(dim);
            regions.remove(dim);
            levels.remove(dim);
            unmeasured.remove(dim);
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            send((EntityPlayerMP) e.player);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        sent.remove(e.player.getUniqueID());
    }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            send((EntityPlayerMP) e.player);
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            send((EntityPlayerMP) e.player);
        }
    }

    /** Uma vez por segundo: quem trocou de mundo por um caminho que não dispara os eventos recebe o mar do novo. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !running || ++ticks % 20 != 0) {
            return;
        }
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null) {
            return;
        }
        // o nível que não deu para medir quando o mundo carregou: de novo, agora que pode haver chunks carregados
        for (Integer dim : unmeasured.keySet()) {
            WorldServer w = DimensionManager.getWorld(dim);
            Integer measured = w == null ? null : measure(w);
            if (measured != null) {
                unmeasured.remove(dim);
                levels.put(dim, measured);
                OceanWavesMod.LOG.info("Nível do mar de {} medido: {}", worldName(w), measured);
                OceanNet.channel.sendToDimension(message(dim), dim);
            }
        }
        for (Object o : s.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            Integer last = sent.get(p.getUniqueID());
            if (last == null || last != p.dimension) {
                send(p);
            }
        }
    }
}
