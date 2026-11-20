package com.akashic.oceanwaves;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import net.minecraftforge.common.config.Configuration;

import com.akashic.oceanwaves.core.SeaPresets;
import com.akashic.oceanwaves.core.SeaState;

/**
 * {@code config/oceanwaves.cfg}. O mar ({@code mar}, {@code costa}) vale pelo servidor: ele o manda aos clientes. Sem
 * o mod no servidor, o cliente usa o próprio. O desenho ({@code desenho}) é só do cliente.
 */
public final class OceanConfig {

    private static Configuration file;

    // mar
    public static String preset = "moderado";
    public static double windSpeed = -1;
    public static double windDirection = 20;
    public static double heightScale = 1;
    public static double lengthScale = SeaPresets.SCALE;
    public static double choppiness = 0.8;
    public static int components = 64;
    public static long seed = 0x0CEA17L;
    public static int seaLevel = 63;
    /**
     * Mundos com ondas (vale pelo servidor): o nome da pasta do mundo (o do Multiverse, ex.: {@code Akashic/Spawn}) ou
     * o número da dimensão.
     */
    public static java.util.List<String> worlds = new java.util.ArrayList<>();
    /**
     * O nível do mar de cada mundo da lista que o diz ({@code Akashic/Spawn@31}: o bloco de água mais alto é o 30); os
     * que não dizem são medidos pelo servidor em volta do spawn e dos jogadores ({@code server.ServerSea}).
     */
    public static java.util.Map<String, Integer> worldLevels = new java.util.HashMap<>();
    /** Nenhum dos {@link #worlds} existe neste servidor (jogo sozinho, outro servidor): ondas no mundo principal. */
    public static boolean mainWorldFallback = true;
    /** As dimensões da configuração para um cliente num servidor sem o mod (só números; sem nomes, a 0). */
    public static Set<Integer> dimensions = new HashSet<>();

    // região
    public static String regionMode = "tudo";
    public static double regionRadius = 384;
    public static double regionX = 0;
    public static double regionZ = 0;

    // costa
    public static double shoreWidth = 10;
    public static double breakerIndex = 0.78;

    // desenho
    public static boolean render = true;
    public static double minSpacing = 1.0;
    public static double lodFactor = 0.02;
    public static float alpha = 0.78f;
    public static boolean shading = true;
    public static boolean foam = true;
    public static boolean specular = true;
    public static double relief = 2.0;
    public static boolean edgeFade = true;
    public static boolean waterShadows = true;
    public static int rebuildsPerFrame = 6;

    private OceanConfig() {}

    public static void load(File f) {
        file = new Configuration(f);
        read();
    }

    private static void read() {
        Configuration c = file;
        c.load();
        String m = "mar";
        c.setCategoryComment(
            m,
            "O mar. Vale pelo servidor (que o manda aos clientes); sem o mod no servidor, vale o do cliente.");
        preset = c.getString(
            "estado",
            m,
            preset,
            "Estado de mar de partida: " + String.join(
                ", ",
                SeaPresets.all()
                    .keySet()));
        windSpeed = c.get(m, "vento", windSpeed, "Vento em m/s (-1: o do estado)")
            .getDouble();
        windDirection = c.get(m, "direcao", windDirection, "Para onde o vento sopra, graus (0 = leste, 90 = sul)")
            .getDouble();
        heightScale = c.get(m, "altura", heightScale, "Multiplica a altura das ondas")
            .getDouble();
        lengthScale = c
            .get(
                m,
                "escala",
                lengthScale,
                "Escala de Froude do mar (0,5: maquete em meia escala do mar real que aquele vento faz)")
            .getDouble();
        choppiness = c.get(m, "crista", choppiness, "0 a 1: quanto as cristas se aguçam")
            .getDouble();
        components = c.get(m, "componentes", components, "Quantas ondas somadas (8 a 256)")
            .getInt();
        seed = Long.decode(c.getString("semente", m, "0x0CEA17", "Semente das fases e direções"));
        seaLevel = c
            .get(
                m,
                "nivel",
                seaLevel,
                "Nível do mar quando um mundo não o diz e não dá para medir (o bloco de água mais alto é nivel - 1)")
            .getInt();
        c.getCategory(m)
            .remove("dimensoes");
        worlds.clear();
        worldLevels.clear();
        dimensions.clear();
        for (String w : c.get(
            m,
            "mundos",
            new String[] { "Akashic/Spawn@31" },
            "Mundos com ondas: o nome da pasta do mundo (o do Multiverse, ex.: Akashic/Spawn) ou o número da dimensão, "
                + "com o nível do mar depois de @ (Akashic/Spawn@31: a água mais alta no y 30). Sem o @, o servidor mede o "
                + "nível em volta do spawn do mundo e dos jogadores nele (escreva o @ quando souber: não depende de medir). Nos outros mundos a água é a vanilla.")
            .getStringList()) {
            String t = w.trim();
            int at = t.lastIndexOf('@');
            if (at > 0) {
                try {
                    int level = Integer.parseInt(
                        t.substring(at + 1)
                            .trim());
                    t = t.substring(0, at)
                        .trim();
                    worldLevels.put(t, level);
                } catch (NumberFormatException e) {
                    // sem nível válido: o nome inteiro, medido pelo servidor
                }
            }
            if (t.isEmpty()) {
                continue;
            }
            worlds.add(t);
            try {
                dimensions.add(Integer.parseInt(t));
            } catch (NumberFormatException e) {
                // um nome: só o servidor sabe a dimensão dele
            }
        }
        mainWorldFallback = c.get(
            m,
            "principalSeNenhum",
            mainWorldFallback,
            "Se nenhum desses mundos existir no servidor (um jogo sozinho, outro servidor), ondas no mundo principal "
                + "(dimensão 0)")
            .getBoolean();
        if (dimensions.isEmpty() && mainWorldFallback) {
            dimensions.add(0);
        }
        String g = "regiao";
        c.setCategoryComment(g, "Onde há ondas. Fora da região a água continua a vanilla (vale pelo servidor).");
        regionMode = c.getString(
            "modo",
            g,
            regionMode,
            "tudo: o mundo inteiro; spawn: um círculo em volta do spawn do mundo; ponto: um círculo em volta de (x, z)");
        regionRadius = c.get(g, "raio", regionRadius, "Raio da região em blocos (spawn e ponto)")
            .getDouble();
        regionX = c.get(g, "x", regionX, "Centro da região no modo ponto")
            .getDouble();
        regionZ = c.get(g, "z", regionZ, "Centro da região no modo ponto")
            .getDouble();
        String s = "costa";
        shoreWidth = c.get(s, "largura", shoreWidth, "Em quantos blocos as ondas morrem perto da costa")
            .getDouble();
        breakerIndex = c.get(s, "quebra", breakerIndex, "Altura máxima da onda sobre a profundidade (McCowan: 0,78)")
            .getDouble();
        String r = "desenho";
        c.setCategoryComment(r, "Só do cliente.");
        render = c.get(r, "ligado", render, "Desenhar as ondas")
            .getBoolean();
        minSpacing = c.get(r, "espacamento", minSpacing, "Espaçamento da malha perto da câmera, blocos (0,5 ou 1)")
            .getDouble();
        lodFactor = c
            .get(r, "detalhe", lodFactor, "Espaçamento por bloco de distância (menor: mais detalhe longe, mais caro)")
            .getDouble();
        alpha = (float) c.get(r, "opacidade", alpha, "Opacidade da superfície")
            .getDouble();
        shading = c.get(r, "sombreamento", shading, "Luz do sol e do céu na superfície (sem shader)")
            .getBoolean();
        foam = c.get(r, "espuma", foam, "Espuma nas cristas")
            .getBoolean();
        specular = c.get(r, "brilho", specular, "Reflexo do sol")
            .getBoolean();
        edgeFade = c.get(
            r,
            "bordaSuave",
            edgeFade,
            "As ondas morrem nos últimos chunks do alcance (encontram a água plana do Mirai/Distant Horizons sem degrau)")
            .getBoolean();
        waterShadows = c
            .get(
                r,
                "sombraDaAgua",
                waterShadows,
                "Com shaderpack: o mar entra no mapa de sombra (sombra colorida e cáusticas embaixo d'água)")
            .getBoolean();
        rebuildsPerFrame = c.get(r, "chunksPorQuadro", rebuildsPerFrame, "Chunks de mar relidos por quadro")
            .getInt();
        if (c.hasChanged()) {
            c.save();
        }
    }

    /** Grava a região (o /ondas regiao muda e guarda para o próximo início). */
    public static void saveRegion(String mode, double radius, double x, double z) {
        regionMode = mode;
        regionRadius = radius;
        regionX = x;
        regionZ = z;
        if (file == null) {
            return;
        }
        file.get("regiao", "modo", mode)
            .set(mode);
        file.get("regiao", "raio", radius)
            .set(radius);
        file.get("regiao", "x", x)
            .set(x);
        file.get("regiao", "z", z)
            .set(z);
        file.save();
    }

    /** A região da configuração, com o spawn da dimensão (spawnX, spawnZ) para o modo spawn. */
    public static com.akashic.oceanwaves.core.SeaRegion region(double spawnX, double spawnZ) {
        double r = Math.max(16, regionRadius);
        switch (regionMode == null ? "" : regionMode.toLowerCase(java.util.Locale.ROOT)) {
            case "tudo":
                return com.akashic.oceanwaves.core.SeaRegion.EVERYWHERE;
            case "ponto":
                return com.akashic.oceanwaves.core.SeaRegion.circle(regionX, regionZ, r);
            case "spawn":
                return com.akashic.oceanwaves.core.SeaRegion.circle(spawnX + 0.5, spawnZ + 0.5, r);
            default:
                return com.akashic.oceanwaves.core.SeaRegion.EVERYWHERE;
        }
    }

    /** O estado de mar da configuração. */
    public static SeaState seaState() {
        SeaState s = SeaPresets.all()
            .getOrDefault(preset, SeaPresets.get("moderado"));
        if (windSpeed >= 0) {
            s = s.withWind(windSpeed, windDirection);
        } else {
            s = s.withWind(s.windSpeed, windDirection);
        }
        return s.withScale(heightScale)
            .withLengthScale(lengthScale)
            .withChoppiness(choppiness)
            .withComponents(Math.max(8, Math.min(256, components)))
            .withSeed(seed);
    }
}
