package com.akashic.oceanwaves.mixin;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Chaves de desligar que valem antes de qualquer arquivo de configuração: {@code -Doceanwaves.disable=true} desliga
 * todos os mixins (o mod fica inerte no cliente); {@code -Doceanwaves.topFace.disable=true} só o que esconde a face de
 * cima da água vanilla (para depurar: as duas superfícies aparecem juntas).
 */
public final class OceanMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LogManager.getLogger("OceanWaves");

    @Override
    public void onLoad(String mixinPackage) {
        if (Boolean.getBoolean("oceanwaves.disable")) {
            LOG.warn("Mixins do OceanWaves desligados por -Doceanwaves.disable=true");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (Boolean.getBoolean("oceanwaves.disable")) {
            return false;
        }
        if (mixinClassName.endsWith("BlockLiquidTopFaceMixin") && Boolean.getBoolean("oceanwaves.topFace.disable")) {
            LOG.warn("A face de cima da água vanilla continua desenhada (-Doceanwaves.topFace.disable=true)");
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
