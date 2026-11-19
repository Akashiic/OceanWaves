package com.akashic.oceanwaves.client;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

import com.akashic.oceanwaves.OceanWavesMod;

/**
 * Com shaderpack (Iris do Angelica), o mar é desenhado como a água do terreno: a fase {@code TERRAIN_TRANSLUCENT}
 * (o programa {@code gbuffers_water} do pack) e o material da água ({@code Iris.setShaderMaterialOverride}, o mesmo
 * caminho que o Neo States usa para a água hospedada), para o pack aplicar a luz, os reflexos e as ondas dele. Tudo
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
            Class<?> iris = Class.forName("net.coderbot.iris.Iris");
            override = iris.getMethod("setShaderMaterialOverride", Block.class, int.class);
            reset = iris.getMethod("resetShaderMaterialOverride");
        } catch (ReflectiveOperationException | LinkageError e) {
            override = null;
        }
        OceanWavesMod.LOG.info("Ponte com o Iris: fase {}, material {}", push != null, override != null);
    }

    /** Antes de desenhar o mar com um shaderpack ligado. */
    static void begin() {
        resolve();
        try {
            if (push != null) {
                push.invoke(null, translucent);
                pushed = true;
            }
            if (override != null) {
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
                reset.invoke(null);
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
