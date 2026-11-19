package com.akashic.oceanwaves.dev;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import com.akashic.oceanwaves.OceanWavesMod;
import com.akashic.oceanwaves.api.OceanWaves;
import com.akashic.oceanwaves.client.OceanStats;
import com.akashic.oceanwaves.core.SeaPresets;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Harness de desenvolvimento (nunca vai no jar de lançamento: o shadowJar exclui {@code dev/}). Com um arquivo
 * {@code oceanwaves-harness} na pasta do cliente, {@code ./gradlew runClient} abre um mundo plano de oceano (8 blocos
 * de água sobre o fundo, nível do mar 63), ergue uma ilha com praia, passa pelos casos (câmera, estado do mar, hora),
 * mede o tempo de quadro e o custo do mar, tira uma captura de cada caso e sai. O relatório vai para
 * {@code oceanwaves-harness.txt}. A primeira linha do arquivo, se houver, escolhe os casos pelo nome.
 */
public final class OceanHarness {

    private static final String WORLD = "oceanwaves-harness";
    private static final int WARMUP = 120;
    private static final int CASE_TICKS = 140;

    /** nome, estado, x, y, z, guinada, inclinação, hora do mundo */
    private static final Object[][] CASES = { { "moderado-alto", "moderado", 0.5, 74.0, 0.5, 200f, 18f, 6000L },
        { "moderado-rente", "moderado", 0.5, 64.2, 0.5, 200f, 4f, 6000L },
        { "fresco-rente", "fresco", 0.5, 64.6, 0.5, 200f, 3f, 6000L },
        { "tempestade-alto", "tempestade", 0.5, 78.0, 0.5, 200f, 14f, 6000L },
        { "tempestade-rente", "tempestade", 0.5, 65.5, 0.5, 200f, 2f, 6000L },
        { "costa", "fresco", 36.5, 70.0, 0.5, 270f, 18f, 6000L },
        { "por-do-sol", "moderado", 0.5, 66.0, 0.5, 90f, 4f, 12600L },
        { "calmo", "calmo", 0.5, 64.5, 0.5, 200f, 8f, 6000L },
        { "de-cima", "moderado", 0.5, 110.0, 0.5, 200f, 70f, 6000L },
        // a linha de base: a mesma câmera de "moderado-alto" com a água vanilla (sem ondas)
        { "vanilla-alto", null, 0.5, 74.0, 0.5, 200f, 18f, 6000L } };

    private final List<Object[]> cases = new ArrayList<>();
    private final List<String> report = new ArrayList<>();
    private boolean launched;
    private boolean built;
    private int menuTicks;
    private int index = -1;
    private int ticks;
    private final List<Long> frames = new ArrayList<>();
    private long lastFrame;
    private boolean done;

    /** Servidor a que conectar ("host:porta"), ou null: mundo integrado. */
    private String remote;
    /** Deslocamento dos casos no multijogador (o oceano do mundo do servidor). */
    private static double originX, originZ;
    private int joinTicks;
    private int remoteStep;

    public static void install(String selection) {
        OceanHarness h = new OceanHarness();
        String first = "";
        for (String line : (selection == null ? "" : selection).split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("origem ")) {
                String[] o = l.substring(7)
                    .trim()
                    .split("[ ,]+");
                originX = Double.parseDouble(o[0]);
                originZ = Double.parseDouble(o[1]);
            } else if (l.startsWith("servidor ")) {
                h.remote = l.substring(9)
                    .trim();
            } else if (first.isEmpty() && !l.isEmpty()) {
                first = l;
            }
        }
        List<String> names = first.isEmpty() ? null : Arrays.asList(first.split("[ ,]+"));
        for (Object[] c : CASES) {
            if (names == null || names.contains(c[0])) {
                h.cases.add(c);
            }
        }
        FMLCommonHandler.instance()
            .bus()
            .register(h);
        OceanWavesMod.LOG
            .warn("HARNESS DE DESENVOLVIMENTO do OceanWaves ligado: este cliente mede {} casos e sai", h.cases.size());
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || index < 0 || done) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0 && ticks > 20) {
            frames.add(now - lastFrame);
        }
        lastFrame = now;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (e.phase != TickEvent.Phase.END || done) {
            return;
        }
        if (!launched && mc.theWorld == null
            && (mc.currentScreen instanceof GuiMainMenu || mc.currentScreen != null && ++menuTicks > 60)) {
            launched = true;
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.limitFramerate = 260;
            mc.gameSettings.enableVsync = false;
            mc.gameSettings.renderDistanceChunks = 8;
            mc.gameSettings.fancyGraphics = true;
            mc.gameSettings.showInventoryAchievementHint = false;
            mc.mouseHelper = new net.minecraft.util.MouseHelper() {

                @Override
                public void mouseXYChange() {
                    deltaX = 0;
                    deltaY = 0;
                }
            };
            if (remote != null) {
                String[] hp = remote.split(":");
                OceanWavesMod.LOG.warn("[harness] conectando a {}", remote);
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .connectToServerAtStartup(hp[0], hp.length > 1 ? Integer.parseInt(hp[1]) : 25565);
                return;
            }
            // fundo de pedra, 2 de terra, 8 de água: o topo da água fica em y = 62, nível do mar 63; bioma oceano
            mc.launchIntegratedServer(
                WORLD,
                WORLD,
                new WorldSettings(1L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT)
                    .func_82750_a("2;7,52x1,2x3,8x9;0;"));
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.currentScreen != null) {
            mc.displayGuiScreen(null);
        }
        mc.gameSettings.hideGUI = true;
        if (remote != null) {
            remoteTick(mc);
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        EntityPlayerMP p = server == null ? null
            : server.getConfigurationManager()
                .func_152612_a(mc.thePlayer.getCommandSenderName());
        if (p == null) {
            return;
        }
        WorldServer ws = p.getServerForPlayer();
        if (!built) {
            built = true;
            island(ws);
            report(
                "java " + System.getProperty("java.version")
                    + ", "
                    + mc.displayWidth
                    + "x"
                    + mc.displayHeight
                    + ", distância "
                    + mc.gameSettings.renderDistanceChunks
                    + " chunks, shaderpack detectado: "
                    + OceanStats.shaderPack());
            index = 0;
            ticks = 0;
            begin(mc, p, ws, cases.isEmpty() ? CASES[0] : cases.get(0));
            return;
        }
        Object[] c = cases.get(index);
        hold(mc, p, c);
        ticks++;
        if (ticks == WARMUP + CASE_TICKS - 1) {
            ScreenShotHelper.saveScreenshot(
                mc.mcDataDir,
                "ondas-" + c[0] + ".png",
                mc.displayWidth,
                mc.displayHeight,
                mc.getFramebuffer());
        }
        if (ticks >= WARMUP + CASE_TICKS) {
            finish(c);
            index++;
            if (index >= cases.size()) {
                done = true;
                write(mc);
                mc.shutdown();
                return;
            }
            ticks = 0;
            begin(mc, p, ws, cases.get(index));
        }
        if (ticks == WARMUP) {
            frames.clear();
            OceanStats.reset();
        }
    }

    /**
     * Multijogador: não há servidor integrado para controlar; os casos são conduzidos por comandos no chat (o jogador
     * precisa ser operador no servidor de teste) e a câmera é posta no cliente.
     */
    private void remoteTick(Minecraft mc) {
        joinTicks++;
        if (index < 0) {
            if (joinTicks == 100) {
                report(
                    "java " + System.getProperty("java.version")
                        + ", "
                        + mc.displayWidth
                        + "x"
                        + mc.displayHeight
                        + ", distância "
                        + mc.gameSettings.renderDistanceChunks
                        + " chunks, servidor "
                        + remote);
                mc.thePlayer.sendChatMessage("/gamemode 1");
            }
            if (joinTicks == 140) {
                report(clockLine("ao entrar"));
                index = 0;
                ticks = 0;
                remoteBegin(mc, cases.get(0));
            }
            return;
        }
        Object[] c = cases.get(index);
        remoteHold(mc, c);
        ticks++;
        if (ticks == WARMUP / 2) {
            report(clockLine(String.valueOf(c[0])));
        }
        if (ticks == WARMUP) {
            frames.clear();
            OceanStats.reset();
        }
        if (ticks == WARMUP + CASE_TICKS - 1) {
            ScreenShotHelper.saveScreenshot(
                mc.mcDataDir,
                "ondas-mp-" + c[0] + ".png",
                mc.displayWidth,
                mc.displayHeight,
                mc.getFramebuffer());
        }
        if (ticks >= WARMUP + CASE_TICKS) {
            finish(c);
            index++;
            if (index >= cases.size()) {
                done = true;
                write(mc);
                mc.shutdown();
                return;
            }
            ticks = 0;
            remoteBegin(mc, cases.get(index));
        }
    }

    private String clockLine(String when) {
        net.minecraft.world.World w = Minecraft.getMinecraft().theWorld;
        com.akashic.oceanwaves.core.WaveSet ws = com.akashic.oceanwaves.client.ClientSea.waves();
        return String.format(
            Locale.ROOT,
            "relógio (%s): %s, %.2f TPS, mar - tempo do mundo = %+.3f s; mar %s, resumo %016x, %d componentes",
            when,
            com.akashic.oceanwaves.client.ClientSea.CLOCK.synced() ? "do servidor" : "local",
            com.akashic.oceanwaves.client.ClientSea.CLOCK.serverTps(),
            com.akashic.oceanwaves.client.ClientSea.time(0) - w.getTotalWorldTime() / 20.0,
            com.akashic.oceanwaves.client.ClientSea.fromServer() ? "do servidor" : "local",
            ws.fingerprint(),
            ws.count);
    }

    private void remoteBegin(Minecraft mc, Object[] c) {
        mc.thePlayer.sendChatMessage(c[1] == null ? "/ondas desligar" : "/ondas estado " + c[1]);
        mc.thePlayer.sendChatMessage("/time set " + c[7]);
        mc.thePlayer.sendChatMessage(
            String.format(
                Locale.ROOT,
                "/tp %.1f %.1f %.1f",
                (Double) c[2] + originX,
                (Double) c[3],
                (Double) c[4] + originZ));
        frames.clear();
        lastFrame = 0;
    }

    private static void remoteHold(Minecraft mc, Object[] c) {
        mc.thePlayer.capabilities.isFlying = true;
        mc.thePlayer.setPositionAndRotation(
            (Double) c[2] + originX,
            (Double) c[3],
            (Double) c[4] + originZ,
            (Float) c[5],
            (Float) c[6]);
        mc.thePlayer.motionX = mc.thePlayer.motionY = mc.thePlayer.motionZ = 0;
    }

    private void begin(Minecraft mc, EntityPlayerMP p, WorldServer ws, Object[] c) {
        OceanWaves.setState(
            0,
            c[1] == null ? null
                : SeaPresets.get((String) c[1])
                    .withSeed(0x0CEA17L));
        ws.setWorldTime((Long) c[7]);
        frames.clear();
        lastFrame = 0;
        hold(mc, p, c);
    }

    private static void hold(Minecraft mc, EntityPlayerMP p, Object[] c) {
        double x = (Double) c[2], y = (Double) c[3], z = (Double) c[4];
        float yaw = (Float) c[5], pitch = (Float) c[6];
        p.capabilities.isFlying = true;
        mc.thePlayer.capabilities.isFlying = true;
        p.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
        mc.thePlayer.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.thePlayer.motionX = mc.thePlayer.motionY = mc.thePlayer.motionZ = 0;
    }

    /** Uma ilha de areia com praia em rampa, a leste da câmera, e um pilar no mar. */
    private static void island(WorldServer w) {
        int cx = 70, cz = 0;
        for (int dz = -24; dz <= 24; dz++) {
            for (int dx = -24; dx <= 24; dx++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                int top = (int) Math.round(66 - (r - 10) * 0.6);
                if (r > 24 || top < 55) {
                    continue;
                }
                for (int y = 55; y <= Math.min(top, 70); y++) {
                    w.setBlock(cx + dx, y, cz + dz, y >= 62 ? Blocks.sand : Blocks.sandstone, 0, 2);
                }
            }
        }
        for (int y = 55; y <= 68; y++) {
            w.setBlock(20, y, -10, Blocks.stonebrick, 0, 2);
        }
    }

    private void finish(Object[] c) {
        long[] f = new long[frames.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = frames.get(i);
        }
        Arrays.sort(f);
        double mean = 0;
        for (long v : f) {
            mean += v;
        }
        mean = f.length == 0 ? 0 : mean / f.length / 1e6;
        double p50 = f.length == 0 ? 0 : f[f.length / 2] / 1e6, p99 = f.length == 0 ? 0 : f[f.length * 99 / 100] / 1e6;
        report(
            String.format(
                Locale.ROOT,
                "%-17s %4d quadros, quadro médio %6.2f ms (p50 %6.2f, p99 %6.2f) | mar: malha %5.2f ms, vértices %5.2f ms, GL %5.2f ms, %3d chunks, %6d quads",
                c[0],
                f.length,
                mean,
                p50,
                p99,
                OceanStats.meshMs(),
                OceanStats.drawMs() - OceanStats.glMs(),
                OceanStats.glMs(),
                OceanStats.chunks(),
                OceanStats.quads()));
        report(
            "    " + OceanStats.diagnostics(f.length)
                + String.format(
                    Locale.ROOT,
                    ", jogador (%.1f, %.1f)",
                    Minecraft.getMinecraft().thePlayer.posX,
                    Minecraft.getMinecraft().thePlayer.posZ));
    }

    private void report(String line) {
        OceanWavesMod.LOG.info("[harness] {}", line);
        report.add(line);
    }

    private void write(Minecraft mc) {
        File out = new File(mc.mcDataDir, "oceanwaves-harness.txt");
        try (PrintWriter w = new PrintWriter(new FileWriter(out))) {
            for (String line : report) {
                w.println(line);
            }
        } catch (IOException e) {
            OceanWavesMod.LOG.error("Não deu para escrever {}", out, e);
        }
    }
}
