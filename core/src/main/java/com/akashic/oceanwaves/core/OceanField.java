package com.akashic.oceanwaves.core;

/**
 * O mar como função do espaço e do tempo, para consultas em pontos (a física). A mesma conta que a malha do cliente
 * faz em grade ({@link GridEvaluator}), sem o filtro de distância.
 *
 * <p>Tudo em precisão dupla. A fase θ = k·x₀ − ωt + φ é calculada inteira em double: com x até 3·10⁷ blocos (a borda
 * do mundo) e t de anos, θ fica abaixo de 10⁹ rad e o erro de arredondamento abaixo de 10⁻⁷ rad.
 */
public final class OceanField {

    private static final double TWO_PI = 2 * Math.PI;

    private final WaveSet waves;
    private final Attenuation attenuation;
    private final double[] grad = new double[2];

    public OceanField(WaveSet waves, Attenuation attenuation) {
        this.waves = waves;
        this.attenuation = attenuation == null ? Attenuation.OPEN_SEA : attenuation;
    }

    public OceanField(WaveSet waves) {
        this(waves, Attenuation.OPEN_SEA);
    }

    public WaveSet waves() {
        return waves;
    }

    public Attenuation attenuation() {
        return attenuation;
    }

    public OceanField withAttenuation(Attenuation a) {
        return new OceanField(waves, a);
    }

    /**
     * A partícula que em repouso estaria em (x₀, z₀), no instante t, usando as {@code count} componentes mais longas.
     * Não é thread-safe por causa do vetor de gradiente: use um campo por thread.
     */
    public void lagrangian(double x0, double z0, double t, int count, WaveSample s) {
        WaveSet w = waves;
        int n = Math.min(count, w.count);
        double eta = 0, dx = 0, dz = 0, hx = 0, hz = 0, xx = 0, xz = 0, zz = 0, vx = 0, vy = 0, vz = 0;
        for (int i = 0; i < n; i++) {
            double th = w.kx[i] * x0 + w.kz[i] * z0 - w.omega[i] * t + w.phase[i];
            // reduzir a [0, 2π) antes: o Math.sin do Java 8 tem um caminho lento para argumentos grandes
            th -= TWO_PI * Math.floor(th / TWO_PI);
            double c = Math.cos(th), sn = Math.sin(th);
            double a = w.amplitude[i], qa = w.q[i] * a;
            double qadx = qa * w.dirX[i], qadz = qa * w.dirZ[i];
            eta += a * c;
            dx -= qadx * sn;
            dz -= qadz * sn;
            hx -= a * w.kx[i] * sn;
            hz -= a * w.kz[i] * sn;
            xx -= qadx * w.kx[i] * c;
            xz -= qadx * w.kz[i] * c;
            zz -= qadz * w.kz[i] * c;
            double ow = w.omega[i];
            vx += qadx * ow * c;
            vz += qadz * ow * c;
            vy += a * ow * sn;
        }
        double att = attenuation.at(x0, z0);
        s.x0 = x0;
        s.z0 = z0;
        s.attenuation = att;
        if (attenuation.uniform()) {
            s.dx = dx;
            s.dz = dz;
            s.height = eta + w.meanLevel;
            s.hx = hx;
            s.hz = hz;
            s.dxx = xx;
            s.dxz = xz;
            s.dzz = zz;
            s.vx = vx;
            s.vy = vy;
            s.vz = vz;
            return;
        }
        attenuation.gradient(x0, z0, grad);
        double ax = grad[0], az = grad[1];
        s.dx = att * dx;
        s.dz = att * dz;
        s.height = att * eta + att * att * w.meanLevel;
        s.hx = att * hx + ax * eta;
        s.hz = att * hz + az * eta;
        // ∂(A·Dx)/∂x₀ = A·∂Dx/∂x₀ + Dx·∂A/∂x₀; o termo cruzado é simetrizado para a matriz continuar simétrica
        s.dxx = att * xx + ax * dx;
        s.dxz = att * xz + 0.5 * (az * dx + ax * dz);
        s.dzz = att * zz + az * dz;
        s.vx = att * vx;
        s.vy = att * vy;
        s.vz = att * vz;
    }

    /**
     * A superfície no ponto do mundo (x, z): acha a partícula x₀ que o deslocamento leva até lá, resolvendo
     * x₀ + D(x₀) = x por Newton (a matriz I + ∇D já sai da amostra). Com Σ Qkа &lt; 1 o mapa é uma contração
     * perturbada da identidade e Newton converge quadraticamente a partir de x₀ = x; quando a amostra já traz um x₀ da
     * consulta anterior (um casco que se move pouco entre ticks), começa dali e costuma bastar uma iteração.
     *
     * @param warm usar {@code s.x0, s.z0} como chute inicial
     * @return a altura da superfície acima do nível do mar em repouso
     */
    public double eulerian(double x, double z, double t, int count, WaveSample s, boolean warm) {
        double x0 = warm ? s.x0 : x, z0 = warm ? s.z0 : z;
        double tol = 1e-6;
        int it = 0;
        double res = Double.MAX_VALUE;
        for (; it < 12; it++) {
            lagrangian(x0, z0, t, count, s);
            double fx = x0 + s.dx - x, fz = z0 + s.dz - z;
            res = Math.sqrt(fx * fx + fz * fz);
            if (res < tol) {
                break;
            }
            double j11 = 1 + s.dxx, j12 = s.dxz, j22 = 1 + s.dzz;
            double det = j11 * j22 - j12 * j12;
            if (det < 0.05) {
                // perto de dobrar (não deveria acontecer com L < 1): passo de ponto fixo, sempre seguro
                x0 -= fx;
                z0 -= fz;
            } else {
                x0 -= (j22 * fx - j12 * fz) / det;
                z0 -= (j11 * fz - j12 * fx) / det;
            }
        }
        s.iterations = it;
        s.residual = res;
        return s.height;
    }

    /** Atalho: a altura da superfície em (x, z) com todas as componentes. */
    public double height(double x, double z, double t) {
        return eulerian(x, z, t, waves.count, new WaveSample(), false);
    }
}
