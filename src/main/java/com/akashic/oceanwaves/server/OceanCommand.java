package com.akashic.oceanwaves.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.akashic.oceanwaves.OceanConfig;
import com.akashic.oceanwaves.api.OceanWaves;
import com.akashic.oceanwaves.core.OceanField;
import com.akashic.oceanwaves.core.SeaPresets;
import com.akashic.oceanwaves.core.SeaRegion;
import com.akashic.oceanwaves.core.SeaState;
import com.akashic.oceanwaves.core.ShoreField;
import com.akashic.oceanwaves.core.WaveSample;
import com.akashic.oceanwaves.core.WaveSet;

/**
 * {@code /ondas} (ou {@code /oceanwaves}): o mar da dimensão de quem chama, ajustado em tempo real. Nível de
 * permissão 2 (operadores).
 */
public final class OceanCommand extends CommandBase {

    private static final List<String> SUBS = Arrays.asList(
        "info",
        "estado",
        "vento",
        "altura",
        "escala",
        "crista",
        "componentes",
        "semente",
        "aqui",
        "regiao",
        "ligar",
        "desligar");

    @Override
    public String getCommandName() {
        return "ondas";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("oceanwaves", "ow");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ondas <info|estado <nome>|vento <m/s> [graus]|altura <x>|escala <x>|crista <0-1>|componentes <n>|semente <n>|aqui|regiao <spawn|aqui|tudo> [raio]|ligar|desligar>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        int dim = sender.getEntityWorld().provider.dimensionId;
        SeaState s = OceanWaves.state(dim);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (s == null && !sub.equals("ligar")
            && !sub.equals("info")
            && !sub.equals("estado")
            && !sub.equals("regiao")) {
            say(
                sender,
                "Sem ondas neste mundo. Use /ondas ligar (até o servidor reiniciar; para sempre: mundos na configuração).");
            return;
        }
        switch (sub) {
            case "info":
                info(sender, dim);
                return;
            case "estado":
                if (args.length < 2) {
                    say(
                        sender,
                        "Estados: " + String.join(
                            ", ",
                            SeaPresets.all()
                                .keySet()));
                    return;
                }
                apply(
                    sender,
                    dim,
                    SeaPresets.get(args[1].toLowerCase(Locale.ROOT))
                        .withSeed(OceanConfig.seed));
                return;
            case "vento":
                need(args, 2, sender);
                apply(
                    sender,
                    dim,
                    s.withWind(num(args[1], 0, 40), args.length > 2 ? num(args[2], -360, 360) : s.windDirection));
                return;
            case "altura":
                need(args, 2, sender);
                apply(sender, dim, s.withScale(num(args[1], 0, 5)));
                return;
            case "escala":
                need(args, 2, sender);
                apply(sender, dim, s.withLengthScale(num(args[1], 0.05, 2)));
                return;
            case "crista":
                need(args, 2, sender);
                apply(sender, dim, s.withChoppiness(num(args[1], 0, 1)));
                return;
            case "componentes":
                need(args, 2, sender);
                apply(sender, dim, s.withComponents((int) num(args[1], 8, 256)));
                return;
            case "semente":
                need(args, 2, sender);
                apply(sender, dim, s.withSeed(Long.decode(args[1])));
                return;
            case "aqui":
                here(sender, dim);
                return;
            case "regiao":
                region(sender, dim, args);
                return;
            case "ligar":
                apply(sender, dim, s != null ? s : OceanConfig.seaState());
                return;
            case "desligar":
                OceanWaves.setState(dim, null);
                say(sender, "Ondas desligadas nesta dimensão.");
                return;
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    /**
     * {@code /ondas regiao} (mostra), {@code /ondas regiao spawn [raio]}, {@code /ondas regiao aqui [raio]} (em volta
     * de quem chama), {@code /ondas regiao tudo}. Muda para todos na hora e grava na configuração.
     */
    private static void region(ICommandSender sender, int dim, String[] args) {
        if (args.length < 2) {
            say(sender, "Ondas em " + OceanWaves.region(dim) + ". Use /ondas regiao <spawn|aqui|tudo> [raio].");
            return;
        }
        double radius = args.length > 2 ? num(args[2], 16, 100_000) : OceanConfig.regionRadius;
        SeaRegion r;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "tudo":
                OceanConfig.saveRegion("tudo", radius, OceanConfig.regionX, OceanConfig.regionZ);
                r = SeaRegion.EVERYWHERE;
                break;
            case "aqui": {
                net.minecraft.util.ChunkCoordinates p = sender.getPlayerCoordinates();
                OceanConfig.saveRegion("ponto", radius, p.posX + 0.5, p.posZ + 0.5);
                r = SeaRegion.circle(p.posX + 0.5, p.posZ + 0.5, radius);
                break;
            }
            case "spawn": {
                net.minecraft.util.ChunkCoordinates s = sender.getEntityWorld()
                    .getSpawnPoint();
                OceanConfig.saveRegion("spawn", radius, OceanConfig.regionX, OceanConfig.regionZ);
                r = SeaRegion.circle(s.posX + 0.5, s.posZ + 0.5, radius);
                break;
            }
            default:
                throw new WrongUsageException("/ondas regiao <spawn|aqui|tudo> [raio]");
        }
        ServerSea.INSTANCE.setRegion(dim, r);
        say(sender, "Ondas agora em " + r + ".");
    }

    private static void apply(ICommandSender sender, int dim, SeaState s) {
        OceanWaves.setState(dim, s);
        info(sender, dim);
    }

    private static void info(ICommandSender sender, int dim) {
        SeaState s = OceanWaves.state(dim);
        say(sender, "Ondas em: " + com.akashic.oceanwaves.server.ServerSea.INSTANCE.describe() + ".");
        if (s == null) {
            say(sender, "Sem ondas neste mundo.");
            return;
        }
        WaveSet w = OceanWaves.waves(dim);
        int peak = 0;
        for (int i = 1; i < w.count; i++) {
            if (w.amplitude[i] > w.amplitude[peak]) {
                peak = i;
            }
        }
        say(sender, s.toString());
        say(sender, "Ondas em " + OceanWaves.region(dim) + ".");
        say(
            sender,
            String.format(
                Locale.ROOT,
                "Hs %.2f m, pico de %.1f m (período %.1f s), crista máxima %.2f m, L = %.2f, %d componentes, resumo %016x",
                w.significantHeight,
                w.wavelength(peak),
                2 * Math.PI / w.omega[peak],
                w.maxAmplitude,
                w.lipschitz,
                w.count,
                w.fingerprint()));
    }

    private static void here(ICommandSender sender, int dim) {
        if (!(sender instanceof EntityPlayerMP)) {
            say(sender, "Só para jogadores.");
            return;
        }
        EntityPlayerMP p = (EntityPlayerMP) sender;
        WaveSet w = OceanWaves.waves(dim);
        int r = (int) Math.ceil(ShoreField.influenceRadius(OceanConfig.shoreWidth, w.lipschitz, w.maxDisplacement)) + 2;
        int bx = (int) Math.floor(p.posX), bz = (int) Math.floor(p.posZ);
        ShoreField shore = OceanWaves.shore(dim, bx - r, bz - r, bx + r, bz + r);
        OceanField f = new OceanField(w, shore);
        WaveSample s = new WaveSample();
        long t0 = System.nanoTime();
        double h = f.eulerian(p.posX, p.posZ, OceanWaves.time(dim), w.count, s, false);
        long dt = System.nanoTime() - t0;
        double[] n = new double[3];
        s.normal(n);
        say(
            sender,
            String.format(
                Locale.ROOT,
                "Superfície em Y %.3f (%.3f do repouso), atenuação %.2f, inclinação %.1f°, jacobiano %.2f, velocidade (%.2f, %.2f, %.2f) m/s, %d iterações, %.1f µs",
                OceanWaves.restLevel(dim) + h,
                h,
                s.attenuation,
                Math.toDegrees(Math.acos(n[1])),
                s.jacobian(),
                s.vx,
                s.vy,
                s.vz,
                s.iterations,
                dt / 1e3));
    }

    private static void need(String[] args, int n, ICommandSender sender) {
        if (args.length < n) {
            throw new WrongUsageException("/ondas " + args[0] + " <valor>");
        }
    }

    private static double num(String s, double min, double max) {
        double v = parseDouble(null, s.replace(',', '.'));
        return Math.max(min, Math.min(max, v));
    }

    private static void say(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText("§3[Ondas] §f" + text));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsFromIterableMatchingLastWord(args, SUBS);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("estado")) {
            return getListOfStringsFromIterableMatchingLastWord(
                args,
                new ArrayList<>(
                    SeaPresets.all()
                        .keySet()));
        }
        return null;
    }
}
