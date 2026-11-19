package com.akashic.oceanwaves.core;

/**
 * Nível de detalhe no espaço das frequências: longe da câmera, as ondas curtas somem aos poucos em vez de a malha
 * ficar grossa e serrilhar (aliasing). O peso de uma componente de comprimento λ num ponto é
 * smoothstep(λ/s; início, fim), com s o espaçamento que o ponto merece, s = max(sMin, distância·fator).
 *
 * <p>O peso é função só da posição no mundo e da câmera, nunca do pedaço de malha a que o vértice pertence: dois
 * pedaços vizinhos, de resoluções diferentes, calculam o mesmo valor nos vértices que dividem, e não abre fresta.
 * E cada pedaço escolhe um espaçamento de grade menor ou igual ao s do seu ponto mais próximo, então o que sobra do
 * espectro sempre tem pelo menos {@link #fadeStart} amostras por comprimento de onda.
 */
public final class SpectralLod {

    public final double camX;
    public final double camY;
    public final double camZ;
    public final double seaY;
    public final double minSpacing;
    public final double factor;
    public final double fadeStart;
    public final double fadeEnd;

    public SpectralLod(double camX, double camY, double camZ, double seaY, double minSpacing, double factor,
        double fadeStart, double fadeEnd) {
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.seaY = seaY;
        this.minSpacing = minSpacing;
        this.factor = factor;
        this.fadeStart = fadeStart;
        this.fadeEnd = fadeEnd;
    }

    public static SpectralLod at(double camX, double camY, double camZ, double seaY) {
        return new SpectralLod(camX, camY, camZ, seaY, 0.5, 0.02, 2.5, 5.0);
    }

    public double distance(double x, double z) {
        double dx = x - camX, dy = seaY - camY, dz = z - camZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double spacing(double x, double z) {
        return Math.max(minSpacing, distance(x, z) * factor);
    }

    public double weight(double wavelength, double spacing) {
        double u = (wavelength / spacing - fadeStart) / (fadeEnd - fadeStart);
        if (u <= 0) {
            return 0;
        }
        if (u >= 1) {
            return 1;
        }
        return u * u * (3 - 2 * u);
    }

    /** O menor espaçamento que este ponto pede dentro de um retângulo (distância ao ponto mais próximo). */
    public double spacingForBox(double minX, double minZ, double maxX, double maxZ) {
        double nx = Math.max(minX, Math.min(camX, maxX)), nz = Math.max(minZ, Math.min(camZ, maxZ));
        return spacing(nx, nz);
    }
}
