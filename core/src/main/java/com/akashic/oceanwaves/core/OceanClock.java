package com.akashic.oceanwaves.core;

/**
 * O relógio do mar no cliente: uma estimativa suave e monotônica do tempo do servidor (o tick total do mundo), a
 * partir de observações esparsas e atrasadas (um pacote por segundo).
 *
 * <p>O relógio do mundo do cliente vanilla avança a 20 ticks/s locais e salta para o valor do servidor a cada
 * pacote; com o servidor a 15 TPS ou com variação de latência, as ondas tremeriam a cada segundo. Aqui o tempo é
 * uma reta re-ancorada a cada observação: a inclinação é a frequência estimada do servidor (média móvel das
 * observações) mais uma correção que absorve o erro ao longo de {@link #absorbSeconds} segundos. Inclinação sempre
 * positiva: o mar nunca anda para trás. Erro acima de {@link #snapTicks}: salta (entrada no mundo, /tp entre
 * dimensões, servidor que travou).
 *
 * <p>O atraso de meia latência das observações fica embutido na estimativa; as poses dos navios chegam com o mesmo
 * atraso, então navio e onda continuam juntos (docs/ARQUITETURA.md).
 */
public final class OceanClock {

    private static final double NOMINAL = 20.0 / 1e9;

    public double absorbSeconds = 1.5;
    public double snapTicks = 40;

    private boolean synced;
    private double baseTicks;
    private long baseNanos;
    private double rate = NOMINAL;
    private double freq = NOMINAL;
    private long lastObsTicks;
    private long lastObsNanos;

    public synchronized void observe(long serverTicks, long nanos) {
        if (!synced) {
            snap(serverTicks, nanos);
            return;
        }
        double now = ticksAt(nanos);
        double err = serverTicks - now;
        if (Math.abs(err) > snapTicks || nanos <= lastObsNanos) {
            snap(serverTicks, nanos);
            return;
        }
        long dn = nanos - lastObsNanos;
        long dt = serverTicks - lastObsTicks;
        if (dn > 200_000_000L && dt >= 0) {
            double measured = dt / (double) dn;
            measured = Math.max(0.05 * NOMINAL, Math.min(2 * NOMINAL, measured));
            freq += 0.2 * (measured - freq);
            lastObsTicks = serverTicks;
            lastObsNanos = nanos;
        }
        baseTicks = now;
        baseNanos = nanos;
        rate = Math.max(0.1 * freq, freq + err / (absorbSeconds * 1e9));
    }

    private void snap(long serverTicks, long nanos) {
        synced = true;
        baseTicks = serverTicks;
        baseNanos = nanos;
        lastObsTicks = serverTicks;
        lastObsNanos = nanos;
        rate = freq;
    }

    public synchronized boolean synced() {
        return synced;
    }

    public synchronized void reset() {
        synced = false;
        freq = NOMINAL;
        rate = NOMINAL;
    }

    /** Ticks do servidor estimados no instante local {@code nanos} ({@link System#nanoTime()}). */
    public synchronized double ticksAt(long nanos) {
        return baseTicks + (nanos - baseNanos) * rate;
    }

    /** Segundos do mar (ticks / 20). */
    public double secondsAt(long nanos) {
        return ticksAt(nanos) / 20.0;
    }

    /** Frequência estimada do servidor em ticks por segundo. */
    public synchronized double serverTps() {
        return freq * 1e9;
    }
}
