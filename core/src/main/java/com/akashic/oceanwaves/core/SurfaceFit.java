package com.akashic.oceanwaves.core;

/**
 * O plano de mínimos quadrados por amostras da superfície sob um casco: altura no centro e as duas inclinações. É a
 * técnica do Atlas (GDC 2019) para corpos pequenos ou servidores lentos: em vez de somar forças ponto a ponto
 * (ruidoso a 20 Hz), ajusta-se um plano às alturas e o corpo é puxado para ele por uma mola amortecida. Bom para
 * barcos, itens e entidades; navios grandes usam as colunas do casco (Expeditions).
 *
 * <p>Pontos no referencial do corpo (u para estibordo/través, v para a proa); a amostragem em si é de quem chama.
 */
public final class SurfaceFit {

    /** Altura do plano no centro (média ponderada). */
    public double height;
    /** dη/du e dη/dv: inclinação de través e longitudinal. */
    public double slopeU;
    public double slopeV;

    /**
     * Ajusta η ≈ h + a·u + b·v. Com pontos simétricos em torno do centro as equações se desacoplam; aqui resolve-se
     * o sistema 3 × 3 completo (pontos quaisquer), por Cramer.
     */
    public SurfaceFit fit(double[] u, double[] v, double[] eta, int n) {
        double s1 = n, su = 0, sv = 0, suu = 0, svv = 0, suv = 0, se = 0, sue = 0, sve = 0;
        for (int i = 0; i < n; i++) {
            su += u[i];
            sv += v[i];
            suu += u[i] * u[i];
            svv += v[i] * v[i];
            suv += u[i] * v[i];
            se += eta[i];
            sue += u[i] * eta[i];
            sve += v[i] * eta[i];
        }
        double det = s1 * (suu * svv - suv * suv) - su * (su * svv - suv * sv) + sv * (su * suv - suu * sv);
        if (Math.abs(det) < 1e-12) {
            height = n > 0 ? se / n : 0;
            slopeU = 0;
            slopeV = 0;
            return this;
        }
        height = (se * (suu * svv - suv * suv) - su * (sue * svv - suv * sve) + sv * (sue * suv - suu * sve)) / det;
        slopeU = (s1 * (sue * svv - suv * sve) - se * (su * svv - suv * sv) + sv * (su * sve - sue * sv)) / det;
        slopeV = (s1 * (suu * sve - sue * suv) - su * (su * sve - sue * sv) + se * (su * suv - suu * sv)) / det;
        return this;
    }

    /** Ângulo de caturro (rad), positivo com a proa para baixo, como no Expeditions. */
    public double pitch() {
        return -Math.atan(slopeV);
    }

    /** Ângulo de balanço (rad), positivo com o lado +u para cima. */
    public double roll() {
        return Math.atan(slopeU);
    }
}
