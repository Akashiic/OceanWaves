package com.akashic.oceanwaves.core;

import java.util.Locale;

/**
 * Onde há ondas: o mundo todo ou um círculo (o spawn do servidor, por exemplo). Fora da região as colunas não contam
 * como mar aberto: a borda vira uma costa, e a atenuação ({@link ShoreField}) leva as ondas suavemente à água parada
 * nela, com a mesma garantia de não dobrar. Servidor e cliente usam a mesma região (o servidor a manda): física e
 * desenho concordam. Imutável.
 */
public final class SeaRegion {

    public static final SeaRegion EVERYWHERE = new SeaRegion(false, 0, 0, 0);

    /** Falso: o mundo todo. */
    public final boolean bounded;
    public final double centerX;
    public final double centerZ;
    public final double radius;
    private final double radius2;

    private SeaRegion(boolean bounded, double centerX, double centerZ, double radius) {
        this.bounded = bounded;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.radius2 = radius * radius;
    }

    public static SeaRegion circle(double centerX, double centerZ, double radius) {
        if (!(radius > 0)) {
            throw new IllegalArgumentException("o raio da região tem de ser positivo");
        }
        return new SeaRegion(true, centerX, centerZ, radius);
    }

    /** A coluna (x, z) do mundo está na região? Pelo centro da coluna. */
    public boolean containsColumn(int x, int z) {
        if (!bounded) {
            return true;
        }
        double dx = x + 0.5 - centerX, dz = z + 0.5 - centerZ;
        return dx * dx + dz * dz <= radius2;
    }

    /** O retângulo [minX, maxX] × [minZ, maxZ] (colunas) toca a região? */
    public boolean touches(int minX, int minZ, int maxX, int maxZ) {
        if (!bounded) {
            return true;
        }
        double nx = Math.max(minX, Math.min(centerX, maxX + 1)), nz = Math.max(minZ, Math.min(centerZ, maxZ + 1));
        double dx = nx - centerX, dz = nz - centerZ;
        return dx * dx + dz * dz <= radius2;
    }

    /** {limitada (0 ou 1), x, z, raio}, para a rede. */
    public double[] toArray() {
        return new double[] { bounded ? 1 : 0, centerX, centerZ, radius };
    }

    public static SeaRegion fromArray(double[] v) {
        return v.length >= 4 && v[0] != 0 && v[3] > 0 ? circle(v[1], v[2], v[3]) : EVERYWHERE;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SeaRegion)) {
            return false;
        }
        SeaRegion r = (SeaRegion) o;
        return bounded == r.bounded && (!bounded || centerX == r.centerX && centerZ == r.centerZ && radius == r.radius);
    }

    @Override
    public int hashCode() {
        return bounded ? java.util.Arrays.hashCode(toArray()) : 0;
    }

    @Override
    public String toString() {
        return bounded ? String.format(Locale.ROOT, "raio de %.0f blocos em volta de (%.0f, %.0f)", radius, centerX, centerZ)
            : "o mundo todo";
    }
}
