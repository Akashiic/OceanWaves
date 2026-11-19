package com.akashic.oceanwaves.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

/**
 * Recorte pelo cone de visão da câmera, para quando o frustum vanilla não serve: com shaderpack o Iris não atualiza
 * o {@code ClippingHelperImpl} para a câmera do quadro (visto no BSL: ele descartava quase todos os chunks). O cone
 * tem o meio-ângulo da diagonal da tela mais o raio angular da esfera do pedaço: conservador (nunca some algo que
 * está na tela), barato, e sem depender de matrizes do GL.
 */
final class ViewCone {

    private double cx, cy, cz;
    private double fx, fy, fz;
    private double cosLimit;
    private double halfAngle;
    private boolean everything;

    void set(Minecraft mc, EntityLivingBase view, float partial, double camX, double camY, double camZ) {
        cx = camX;
        cy = camY;
        cz = camZ;
        // a terceira pessoa (F5) olha de outro lugar e às vezes ao contrário: sem recorte
        everything = mc.gameSettings.thirdPersonView != 0;
        float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partial;
        float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partial;
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        // a convenção do Minecraft: guinada 0 olha para +z, 90 para −x; inclinação positiva olha para baixo
        fx = -Math.sin(y) * Math.cos(p);
        fy = -Math.sin(p);
        fz = Math.cos(y) * Math.cos(p);
        double fov = Math.toRadians(Math.max(30, mc.gameSettings.fovSetting) * 1.15); // folga para o FOV dinâmico
        double aspect = mc.displayHeight > 0 ? mc.displayWidth / (double) mc.displayHeight : 16 / 9.0;
        halfAngle = Math.atan(Math.tan(fov / 2) * Math.sqrt(1 + aspect * aspect));
        cosLimit = Math.cos(halfAngle);
    }

    /** A esfera de centro (x, y, z) e raio r toca o cone? */
    boolean contains(double x, double y, double z, double r) {
        if (everything) {
            return true;
        }
        double dx = x - cx, dy = y - cy, dz = z - cz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= r) {
            return true;
        }
        double cos = (dx * fx + dy * fy + dz * fz) / d;
        double angle = Math.acos(Math.max(-1, Math.min(1, cos)));
        return angle <= halfAngle + Math.asin(Math.min(1, r / d));
    }
}
