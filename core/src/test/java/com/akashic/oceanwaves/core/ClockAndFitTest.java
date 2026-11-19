package com.akashic.oceanwaves.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClockAndFitTest {

    /** Servidor a 15 TPS, pacotes a cada 20 ticks com 0 a 120 ms de atraso variável: o relógio segue sem recuar. */
    @Test
    void clockFollowsASlowJitteryServerMonotonically() {
        OceanClock c = new OceanClock();
        SplitMix64 r = new SplitMix64(1);
        double serverTps = 15;
        long ns = 1_000_000_000L;
        double last = -1;
        double worstLate = 0;
        long nextPacket = 0;
        for (long now = 0; now < 120 * ns; now += 16_666_667L) {
            if (now >= nextPacket) {
                long serverTicks = (long) Math.floor(now / 1e9 * serverTps);
                long arrival = now + (long) (r.nextDouble() * 120e6);
                c.observe(serverTicks, arrival);
                nextPacket += (long) (20 / serverTps * ns);
            }
            double t = c.ticksAt(now + 130_000_000L);
            assertTrue(t >= last, "o relógio recuou");
            last = t;
            if (now > 30 * ns) {
                worstLate = Math.max(worstLate, Math.abs(now / 1e9 * serverTps - c.ticksAt(now)));
            }
        }
        assertEquals(15, c.serverTps(), 0.3);
        assertTrue(worstLate < 3.5, "erro de " + worstLate + " ticks");
    }

    @Test
    void clockSnapsOnLargeJumps() {
        OceanClock c = new OceanClock();
        c.observe(1000, 0);
        c.observe(1020, 1_000_000_000L);
        c.observe(50_000, 2_000_000_000L);
        assertEquals(50_000, c.ticksAt(2_000_000_000L), 1e-9);
    }

    @Test
    void planeFitRecoversAPlane() {
        double[] u = { -1, 1, -1, 1, 0, 0.3 }, v = { -2, -2, 2, 2, 0, 1.1 }, e = new double[6];
        for (int i = 0; i < 6; i++) {
            e[i] = 0.5 + 0.1 * u[i] - 0.2 * v[i];
        }
        SurfaceFit f = new SurfaceFit().fit(u, v, e, 6);
        assertEquals(0.5, f.height, 1e-12);
        assertEquals(0.1, f.slopeU, 1e-12);
        assertEquals(-0.2, f.slopeV, 1e-12);
        assertEquals(Math.atan(0.2), f.pitch(), 1e-12);
    }
}
