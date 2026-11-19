package com.akashic.oceanwaves.client;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import com.akashic.oceanwaves.OceanConfig;
import com.akashic.oceanwaves.core.OceanMesher;

/**
 * A cor de cada vértice da superfície, uma vez por vértice da grade ({@link OceanMesher.Shade}).
 *
 * <p>
 * Com shaderpack, só a cor da água (bioma × tom da textura) e a espuma: o pack ilumina, com a normal e o mapa de
 * luz. Sem shaderpack, a luz é feita aqui, sem o mapa de luz (ele multiplicaria o vértice depois da cor saturar em
 * 1, e o reflexo do céu do entardecer sairia escuro):
 * <ul>
 * <li>a água: a cor por uma difusa do sol e pela luz da coluna (céu × brilho do dia, ou tochas), mais um tom
 * turquesa nas cristas (a luz que atravessa a crista, o espalhamento sob a superfície);</li>
 * <li>o reflexo pelo termo de Fresnel de Schlick: a cor do céu ao longo do raio refletido, da névoa no horizonte (com
 * o nascer/pôr do sol na direção do sol) ao zênite;</li>
 * <li>o brilho do sol (ou da lua) por Blinn-Phong, alaranjado com o sol baixo;</li>
 * <li>a espuma das cristas (jacobiano).</li>
 * </ul>
 * A normal da iluminação tem a inclinação multiplicada por {@code desenho.relevo}: sem iluminação por pixel, as ondas
 * se leem melhor.
 */
final class SurfaceShader implements OceanMesher.Shade {

    boolean own;
    private boolean foamOn;
    private boolean spec;
    private float alpha;
    private float relief;
    private float sx, sy, sz, sun, sunR, sunG, sunB;
    private float skyR, skyG, skyB, horR, horG, horB;
    private float[] dawn;
    private float sunSide;
    private float daylight;
    private float crestScale;
    private float[] tint;
    // por chunk
    private int[] color;
    private int[] light;
    private float camX, camY, camZ;

    void frame(World world, EntityLivingBase view, float partial, boolean pack, float[] tint, double hs) {
        float celestial = world.getCelestialAngle(partial);
        double phi = celestial * 2 * Math.PI;
        // o mesmo giro do céu do vanilla (RenderGlobal.renderSky): Ry(−90°)·Rx(360°·ângulo)·(0, 1, 0)
        sx = (float) -Math.sin(phi);
        sy = (float) Math.cos(phi);
        sz = 0;
        // o disco do sol ainda aparece um pouco abaixo do horizonte matemático
        sun = Math.max(0, Math.min(1, (sy + 0.08f) * 5));
        sunR = 1;
        sunG = 1;
        sunB = 0.95f;
        if (sun <= 0) {
            sx = -sx;
            sy = -sy;
            sun = 0.22f * Math.max(0, Math.min(1, sy * 4));
            sunR = 0.75f;
            sunG = 0.8f;
            sunB = 1;
        }
        sun *= 1 - 0.85f * world.getRainStrength(partial);
        Vec3 sky = world.getSkyColor(view, partial);
        skyR = (float) sky.xCoord;
        skyG = (float) sky.yCoord;
        skyB = (float) sky.zCoord;
        Vec3 fog = world.getFogColor(partial);
        horR = (float) fog.xCoord;
        horG = (float) fog.yCoord;
        horB = (float) fog.zCoord;
        dawn = world.provider.calcSunriseSunsetColors(celestial, partial);
        if (dawn != null) {
            sunR += (dawn[0] - sunR) * dawn[3];
            sunG += (dawn[1] - sunG) * dawn[3];
            sunB += (dawn[2] - sunB) * dawn[3];
        }
        sunSide = (float) Math.signum(-Math.sin(phi));
        daylight = world.getSunBrightness(partial);
        own = OceanConfig.shading && !pack;
        foamOn = OceanConfig.foam;
        spec = OceanConfig.specular;
        alpha = OceanConfig.alpha;
        relief = (float) OceanConfig.relief;
        this.tint = tint;
        crestScale = (float) (1 / Math.max(0.05, 0.6 * hs));
    }

    /** O chunk cujos vértices vêm a seguir, e a câmera relativa à origem dele. */
    void chunk(OceanChunks.Column c, double camX, double camY, double camZ, double rest) {
        color = c.color;
        light = c.light;
        this.camX = (float) (camX - c.cx * 16.0);
        this.camY = (float) (camY - rest);
        this.camZ = (float) (camZ - c.cz * 16.0);
    }

    @Override
    public int shade(int col, float px, float py, float pz, float nx, float ny, float nz, float foam) {
        int rgb = color[col];
        float r = (rgb >> 16 & 255) / 255f * tint[0], g = (rgb >> 8 & 255) / 255f * tint[1],
            b = (rgb & 255) / 255f * tint[2];
        float a = alpha;
        if (!foamOn) {
            foam = 0;
        }
        if (own) {
            int bright = light[col];
            float open = (bright >> 20 & 15) / 15f;
            float lit = 0.04f + 0.96f * Math.max(open * daylight, (bright >> 4 & 15) / 15f * 0.85f);
            float vx = camX - px, vy = camY - py, vz = camZ - pz;
            float il = (float) (1 / Math.sqrt(vx * vx + vy * vy + vz * vz + 1e-6));
            vx *= il;
            vy *= il;
            vz *= il;
            float lx = nx * relief, ly = ny, lz = nz * relief;
            float ll = (float) (1 / Math.sqrt(lx * lx + ly * ly + lz * lz));
            lx *= ll;
            ly *= ll;
            lz *= ll;
            float ndv = Math.max(1e-3f, lx * vx + ly * vy + lz * vz);
            float ndl = Math.max(0, lx * sx + ly * sy + lz * sz);
            float m = 1 - ndv, m2 = m * m;
            float fres = 0.02f + 0.98f * m2 * m2 * m;
            // o raio refletido: do horizonte ao zênite
            float rx = 2 * ndv * lx - vx, ry = 2 * ndv * ly - vy;
            float up = (float) Math.sqrt(Math.max(0, Math.min(1, ry)));
            float hr = horR, hg = horG, hb = horB;
            if (dawn != null) {
                float toward = Math.max(0, rx * sunSide) * dawn[3] * (1 - up);
                hr += (dawn[0] - hr) * toward;
                hg += (dawn[1] - hg) * toward;
                hb += (dawn[2] - hb) * toward;
            }
            float reflR = (hr + (skyR - hr) * up) * open, reflG = (hg + (skyG - hg) * up) * open,
                reflB = (hb + (skyB - hb) * up) * open;
            float diffuse = (0.55f + 0.45f * ndl * sun) * lit;
            float crest = Math.max(0, Math.min(1, py * crestScale)) * (0.35f + 0.65f * sun) * lit;
            r = r * diffuse + 0.04f * crest;
            g = g * diffuse + 0.20f * crest;
            b = b * diffuse + 0.16f * crest;
            r += (reflR - r) * fres;
            g += (reflG - g) * fres;
            b += (reflB - b) * fres;
            a = a + (1 - a) * 0.7f * fres;
            if (spec && sun > 0) {
                float hx = sx + vx, hy = sy + vy, hz = sz + vz;
                float hl = (float) (1 / Math.sqrt(hx * hx + hy * hy + hz * hz + 1e-6));
                float ndh = Math.max(0, (lx * hx + ly * hy + lz * hz) * hl);
                float p2 = ndh * ndh, p4 = p2 * p2, p8 = p4 * p4, p16 = p8 * p8;
                float p64 = p16 * p16 * p16 * p16;
                float sp = sun * open * (1.6f * p64 * p64 + 0.05f * p16 * p16);
                r += sp * sunR;
                g += sp * sunG;
                b += sp * sunB;
                a = Math.min(1, a + sp);
            }
            if (foam > 0) {
                float f = foam * 0.7f * lit;
                r += (lit - r) * f;
                g += (lit - g) * f;
                b += (lit - b) * f;
                a += (1 - a) * foam * 0.5f;
            }
        } else if (foam > 0) {
            float f = foam * 0.7f;
            r += (1 - r) * f;
            g += (1 - g) * f;
            b += (1 - b) * f;
            a += (1 - a) * foam * 0.5f;
        }
        return to255(r) << 24 | to255(g) << 16 | to255(b) << 8 | to255(a);
    }

    private static int to255(float v) {
        return v <= 0 ? 0 : v >= 1 ? 255 : (int) (v * 255 + 0.5f);
    }
}
