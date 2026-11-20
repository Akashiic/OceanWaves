package com.akashic.oceanwaves.client;

import java.util.List;

/**
 * Põe os chunks do passe do mais perto para o mais longe da câmera. A espiral já os entrega quase assim, então é uma
 * inserção (rápida nesse caso) e, ao contrário de {@code List.sort} com um comparador, não aloca nada. Sem Minecraft.
 */
final class NearestFirst {

    private NearestFirst() {}

    static void sort(List<OceanChunks.Column> list, double camX, double camZ) {
        for (int i = 1; i < list.size(); i++) {
            OceanChunks.Column c = list.get(i);
            double d = dist2(c, camX, camZ);
            int j = i - 1;
            while (j >= 0 && dist2(list.get(j), camX, camZ) > d) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, c);
        }
    }

    /** O quadrado da distância, no plano, da câmera ao centro do chunk. */
    static double dist2(OceanChunks.Column c, double x, double z) {
        double dx = c.cx * 16 + 8 - x, dz = c.cz * 16 + 8 - z;
        return dx * dx + dz * dz;
    }
}
