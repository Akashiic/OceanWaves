package com.akashic.oceanwaves.core;

/**
 * As componentes de um mar, em vetores paralelos (estrutura de vetores, para o laço quente ler memória contígua),
 * ordenadas do comprimento de onda mais longo para o mais curto: cortar as curtas (o filtro de nível de detalhe) é
 * tomar um prefixo.
 *
 * <p>A componente i é uma onda trocoidal (Gerstner): fase θᵢ = kᵢ·x₀ − ωᵢt + φᵢ, deslocamento
 * D = −Σ Qᵢaᵢ d̂ᵢ sen θᵢ no plano e η = Σ aᵢ cos θᵢ na vertical.
 */
public final class WaveSet {

    public final int count;
    /** Amplitude, m. */
    public final double[] amplitude;
    /** Número de onda, rad/m. */
    public final double[] k;
    /** Vetor de onda, rad/m. */
    public final double[] kx;
    public final double[] kz;
    /** Direção unitária. */
    public final double[] dirX;
    public final double[] dirZ;
    /** Frequência angular, rad/s. */
    public final double[] omega;
    /** Fase inicial, rad. */
    public final double[] phase;
    /** Fator de Gerstner de cada componente (o deslocamento horizontal é Qᵢ·aᵢ). */
    public final double[] q;

    /** Σ Qᵢkᵢaᵢ: a constante de Lipschitz do deslocamento horizontal. Menor que 1 garante que a malha não dobra. */
    public final double lipschitz;
    /**
     * Σ Qᵢkᵢaᵢ²/2: quanto a superfície trocoidal fica, em média, abaixo do nível de repouso. Somado à altura, deixa o
     * nível médio euleriano no nível do mar (conservação da massa).
     */
    public final double meanLevel;
    /** Altura significativa Hs = 4·√(Σaᵢ²/2), m. */
    public final double significantHeight;
    /** A maior crista possível, Σaᵢ (todas em fase), m. */
    public final double maxAmplitude;
    /** O maior deslocamento horizontal possível, ΣQᵢaᵢ, m. */
    public final double maxDisplacement;

    WaveSet(double[] amplitude, double[] k, double[] dirX, double[] dirZ, double[] omega, double[] phase,
        double[] q) {
        this.count = amplitude.length;
        this.amplitude = amplitude;
        this.k = k;
        this.dirX = dirX;
        this.dirZ = dirZ;
        this.omega = omega;
        this.phase = phase;
        this.q = q;
        this.kx = new double[count];
        this.kz = new double[count];
        double lip = 0, mean = 0, var = 0, maxA = 0, maxD = 0;
        for (int i = 0; i < count; i++) {
            kx[i] = k[i] * dirX[i];
            kz[i] = k[i] * dirZ[i];
            lip += q[i] * k[i] * amplitude[i];
            mean += q[i] * k[i] * amplitude[i] * amplitude[i] / 2;
            var += amplitude[i] * amplitude[i] / 2;
            maxA += amplitude[i];
            maxD += q[i] * amplitude[i];
        }
        this.lipschitz = lip;
        this.meanLevel = mean;
        this.significantHeight = 4 * Math.sqrt(var);
        this.maxAmplitude = maxA;
        this.maxDisplacement = maxD;
    }

    public static WaveSet empty() {
        double[] z = new double[0];
        return new WaveSet(z, z, z, z, z, z, z);
    }

    public double wavelength(int i) {
        return 2 * Math.PI / k[i];
    }

    /** Quantas componentes têm comprimento de onda maior ou igual a {@code lambda} (o prefixo que sobrevive). */
    public int prefix(double lambda) {
        double kmax = 2 * Math.PI / lambda;
        int lo = 0, hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (k[mid] <= kmax) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Só as {@code n} componentes mais longas (para a física de um casco, que não sente as ondas curtas). */
    public WaveSet prefixSet(int n) {
        int m = Math.max(0, Math.min(n, count));
        return new WaveSet(java.util.Arrays.copyOf(amplitude, m), java.util.Arrays.copyOf(k, m),
            java.util.Arrays.copyOf(dirX, m), java.util.Arrays.copyOf(dirZ, m), java.util.Arrays.copyOf(omega, m),
            java.util.Arrays.copyOf(phase, m), java.util.Arrays.copyOf(q, m));
    }

    /** Um resumo de conferência: igual nos dois lados se as componentes são as mesmas, bit a bit. */
    public long fingerprint() {
        long h = 1125899906842597L;
        for (int i = 0; i < count; i++) {
            h = 31 * h + Double.doubleToLongBits(amplitude[i]);
            h = 31 * h + Double.doubleToLongBits(k[i]);
            h = 31 * h + Double.doubleToLongBits(dirX[i]);
            h = 31 * h + Double.doubleToLongBits(dirZ[i]);
            h = 31 * h + Double.doubleToLongBits(omega[i]);
            h = 31 * h + Double.doubleToLongBits(phase[i]);
            h = 31 * h + Double.doubleToLongBits(q[i]);
        }
        return h;
    }
}
