package com.akashic.oceanwaves.client;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

import com.akashic.oceanwaves.OceanWavesMod;

/**
 * Com shaderpack (Iris do Angelica), o mar é desenhado como a água do terreno: a fase {@code TERRAIN_TRANSLUCENT}
 * (o programa {@code gbuffers_water} do pack) e o material da água, para o pack aplicar a luz, os reflexos e as
 * ondas dele. O material de um desenho imediato vai no atributo de vértice 11 ({@code mc_Entity}), posto por
 * {@code BlockMaterialAttribute.set}: é como o Angelica desenha o bloco caindo com o programa de terreno.
 * {@code Iris.setShaderMaterialOverride} (o caminho do Neo States) só vale na montagem de chunks e fica de reserva para
 * Angelicas sem o {@code BlockMaterialAttribute}. Tudo
 * por reflexão: sem o Angelica, nada acontece. Visto sem isto no BSL: o mar saía com outro programa, sem a luz do
 * pack. **[a verificar com o BSL e o Complementary]**
 */
final class IrisBridge {

    private static boolean resolved;
    private static Method push;
    private static Method pop;
    private static Object translucent;
    private static Method override;
    private static Method reset;
    /** Angelica 2.2.4x: o material de um desenho imediato (atributo de vértice 11, o mc_Entity do pack). */
    private static Method materialSet;
    private static Method materialReset;
    private static boolean pushed;
    private static boolean overridden;

    private IrisBridge() {}

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> phase = Class.forName("net.coderbot.iris.pipeline.WorldRenderingPhase");
            Class<?> programs = Class.forName("net.coderbot.iris.layer.GbufferPrograms");
            translucent = phase.getField("TERRAIN_TRANSLUCENT")
                .get(null);
            push = programs.getMethod("pushOverridePhase", phase);
            pop = programs.getMethod("popOverridePhase");
        } catch (ReflectiveOperationException | LinkageError e) {
            push = null;
        }
        try {
            Class<?> attr = Class.forName("com.gtnewhorizons.angelica.rendering.BlockMaterialAttribute");
            materialSet = attr.getMethod("set", Block.class, int.class);
            materialReset = attr.getMethod("reset");
        } catch (ReflectiveOperationException | LinkageError e) {
            materialSet = null;
        }
        try {
            Class<?> iris = Class.forName("net.coderbot.iris.Iris");
            override = iris.getMethod("setShaderMaterialOverride", Block.class, int.class);
            reset = iris.getMethod("resetShaderMaterialOverride");
        } catch (ReflectiveOperationException | LinkageError e) {
            override = null;
        }
        OceanWavesMod.LOG.info(
            "Ponte com o Iris: fase {}, material do desenho {}, material da montagem {}",
            push != null,
            materialSet != null,
            override != null);
    }

    private static Method currentPhase;
    private static boolean phaseLooked;

    /** A fase atual do Iris (diagnóstico), ou "-" sem o Iris. A busca é feita uma vez: sem o Iris não lança nada. */
    static String phase() {
        resolve();
        if (!phaseLooked) {
            phaseLooked = true;
            try {
                currentPhase = Class.forName("net.coderbot.iris.layer.GbufferPrograms")
                    .getMethod("getCurrentPhase");
            } catch (ReflectiveOperationException | LinkageError e) {
                currentPhase = null;
            }
        }
        if (currentPhase == null) {
            return "-";
        }
        try {
            return String.valueOf(currentPhase.invoke(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return "-";
        }
    }

    /** Antes de desenhar o mar com um shaderpack ligado. */
    static void begin() {
        resolve();
        try {
            if (push != null) {
                push.invoke(null, translucent);
                pushed = true;
            }
            if (materialSet != null) {
                // o caminho do bloco caindo no Angelica (MixinRenderFallingBlock): o pack reconhece a água pelo id
                materialSet.invoke(null, Blocks.water, 0);
                overridden = true;
            } else if (override != null) {
                override.invoke(null, Blocks.water, 0);
                overridden = true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            OceanWavesMod.LOG.warn("Ponte com o Iris falhou; desligada: {}", e.toString());
            push = null;
            override = null;
        }
    }

    /** Depois (sempre, num finally: o Iris confere o equilíbrio das fases). */
    static void end() {
        try {
            if (overridden) {
                overridden = false;
                if (materialSet != null) {
                    materialReset.invoke(null);
                } else {
                    reset.invoke(null);
                }
            }
            if (pushed) {
                pushed = false;
                pop.invoke(null);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            OceanWavesMod.LOG.warn("Ponte com o Iris falhou ao fechar: {}", e.toString());
        }
    }
}
