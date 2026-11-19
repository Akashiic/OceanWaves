package com.akashic.oceanwaves.core;

/**
 * O resultado de amostrar o mar num ponto. Reutilizável (sem alocação no laço da física).
 *
 * <p>Campos lagrangianos: a partícula de superfície que em repouso estaria em (x₀, z₀) está agora em
 * (x₀ + dx, nível + height, z₀ + dz). Derivadas em relação a (x₀, z₀).
 */
public final class WaveSample {

    /** Ponto de repouso da partícula. */
    public double x0;
    public double z0;
    /** Deslocamento horizontal. */
    public double dx;
    public double dz;
    /** Altura da superfície acima do nível do mar em repouso (já com a correção do nível médio). */
    public double height;
    /** Derivadas: ∂η/∂x₀, ∂η/∂z₀, ∂Dx/∂x₀, ∂Dx/∂z₀ (= ∂Dz/∂x₀), ∂Dz/∂z₀. */
    public double hx;
    public double hz;
    public double dxx;
    public double dxz;
    public double dzz;
    /** Velocidade da partícula de superfície, m/s. */
    public double vx;
    public double vy;
    public double vz;
    /** Atenuação usada. */
    public double attenuation;
    /** Iterações de Newton gastas na última inversão. */
    public int iterations;
    /** Resíduo horizontal final da inversão, m. */
    public double residual;

    /** Normal unitária {nx, ny, nz} (a partir das tangentes da superfície parametrizada). */
    public void normal(double[] out) {
        // T_x = (1 + dxx, hx, dxz); T_z = (dxz, hz, 1 + dzz); N = T_z × T_x
        double ax = 1 + dxx, ay = hx, az = dxz;
        double bx = dxz, by = hz, bz = 1 + dzz;
        double nx = by * az - bz * ay;
        double ny = bz * ax - bx * az;
        double nz = bx * ay - by * ax;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
    }

    /** O jacobiano do mapa x₀ ↦ x₀ + D: área da superfície sobre a área em repouso, projetada. Menor que 1: crista. */
    public double jacobian() {
        return (1 + dxx) * (1 + dzz) - dxz * dxz;
    }
}
