package com.akashic.oceanwaves.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashic.oceanwaves.core.Attenuation;
import com.akashic.oceanwaves.core.SeaPresets;
import com.akashic.oceanwaves.core.Synthesis;
import com.akashic.oceanwaves.core.WaveSet;

/**
 * PERF-3: a costa refeita com orçamento por quadro, do mais perto, sem abrir fresta. O que vale como "fresta" é duas
 * costas desenhadas que discordam na aresta ou no canto que os dois chunks dividem (os vértices dali não são os
 * mesmos).
 */
class ShoreSchedulerTest {

    private static final double WIDTH = 10, GAMMA = 0.78;

    private final WaveSet waves = Synthesis.build(
        SeaPresets.get("tempestade")
            .withChoppiness(1));
    private final ShoreWindow window = new ShoreWindow();

    private ShoreScheduler.Builder builder(ColumnRing ring, List<int[]> log) {
        return c -> {
            if (log != null) {
                log.add(new int[] { c.cx, c.cz });
            }
            return window.build(ring, c, waves, WIDTH, GAMMA, SyntheticSea.everywhere());
        };
    }

    /** Todo par de chunks vizinhos (arestas e cantos) com costa em uso concorda nos valores da borda comum? */
    private static String disagreement(ColumnRing ring) {
        for (int i = 0; i < ring.capacity(); i++) {
            OceanChunks.Column c = ring.at(i);
            if (c == null || !c.any || c.shore == null) {
                continue;
            }
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    OceanChunks.Column y = ring.peek(c.cx + dx, c.cz + dz);
                    if (y == null || !y.any || y.shore == null) {
                        continue;
                    }
                    if (!ShoreScheduler.agree(c.shore, y.shore, c.cx << 4, c.cz << 4, dx, dz)) {
                        return "(" + c.cx + "," + c.cz + ") x (" + y.cx + "," + y.cz + ")";
                    }
                }
            }
        }
        return null;
    }

    private static boolean settled(ColumnRing ring) {
        for (int i = 0; i < ring.capacity(); i++) {
            OceanChunks.Column c = ring.at(i);
            if (c != null && c.any && (c.builtGen != c.shoreGen || c.shore != c.next)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void openDeepSeaCostsNoBudgetAndIsPublishedInOneFrame() {
        SyntheticSea sea = new SyntheticSea(4, 40);
        ColumnRing ring = sea.ringOf(3);
        ShoreScheduler s = new ShoreScheduler();
        s.update(ring, builder(ring, null), 0, 0, 1);
        assertEquals(0, s.lastBuilt, "nenhuma costa de verdade foi calculada");
        assertEquals(49, s.lastOpenSea);
        assertTrue(settled(ring));
        for (int cz = -3; cz <= 3; cz++) {
            for (int cx = -3; cx <= 3; cx++) {
                assertSame(Attenuation.OPEN_SEA, ring.peek(cx, cz).shore, "a sentinela, nunca null nem um ShoreField");
            }
        }
    }

    @Test
    void realShoresAreBuiltNearestFirstAndWithinTheFrameBudget() {
        SyntheticSea sea = new SyntheticSea(6, 40);
        // terra em toda parte perto de todo chunk, para nenhuma janela ser de mar aberto
        for (int z = -80; z < 80; z += 8) {
            for (int x = -80; x < 80; x += 8) {
                sea.island(x, z, 1);
            }
        }
        ColumnRing ring = sea.ringOf(5);
        ShoreScheduler s = new ShoreScheduler();
        List<int[]> log = new ArrayList<>();
        ShoreScheduler.Builder b = builder(ring, log);
        int frames = 0;
        double lastDistance = -1;
        while (!settled(ring) && frames < 200) {
            int before = log.size();
            s.update(ring, b, 2, -1, 3);
            frames++;
            assertTrue(s.lastBuilt <= 3, "mais de 3 costas de verdade num quadro: " + s.lastBuilt);
            assertEquals(log.size() - before, s.lastBuilt + s.lastOpenSea);
            for (int i = before; i < log.size(); i++) {
                double d = Math.hypot(log.get(i)[0] - 2, log.get(i)[1] + 1);
                assertTrue(d >= lastDistance - 1e-9, "ordem: do mais perto para o mais longe");
                lastDistance = d;
            }
            assertNull(disagreement(ring), "fresta no quadro " + frames);
        }
        assertTrue(settled(ring));
        // 121 chunks, 3 por quadro: 41 quadros, não um quadro com tudo
        assertTrue(frames >= 40 && frames <= 45, "quadros: " + frames);
    }

    @Test
    void aCoastThatAppearsNextToTheEdgeNeverOpensACrackWhileItIsBuiltOverSeveralFrames() {
        SyntheticSea sea = new SyntheticSea(6, 40);
        sea.island(-40, -10, 4);
        sea.island(30, 20, 5);
        ColumnRing ring = sea.ringOf(5);
        ShoreScheduler s = new ShoreScheduler();
        ShoreScheduler.Builder b = builder(ring, null);
        int guard = 0;
        while (!settled(ring) && guard++ < 300) {
            s.update(ring, b, 0, 0, 1);
            assertNull(disagreement(ring));
        }
        assertTrue(settled(ring));

        // uma ilha nova de 3 x 3 junto da borda x = 0 entre os chunks (-1, 0) e (0, 0), a 5 blocos dela
        sea.island(-8, 7, 3);
        for (int cz = -1; cz <= 0; cz++) {
            for (int cx = -1; cx <= 0; cx++) {
                sea.reload(ring, cx, cz);
            }
        }
        int frames = 0;
        boolean sawStaleNeighbourWaiting = false;
        while (!settled(ring) && frames < 300) {
            s.update(ring, b, 0, 0, 1);
            frames++;
            assertNull(disagreement(ring), "fresta no quadro " + frames + " depois da mudança");
            sawStaleNeighbourWaiting |= s.lastWaiting > 0;
        }
        assertTrue(settled(ring));
        assertTrue(frames > 1, "com orçamento 1 a costa não sai toda num quadro: " + frames);
        assertTrue(sawStaleNeighbourWaiting, "algum chunk esperou os vizinhos (é a troca em grupo)");

        // o fim é o mesmo de refazer tudo do zero, com a janela de cada chunk
        for (int cz = -5; cz <= 5; cz++) {
            for (int cx = -5; cx <= 5; cx++) {
                OceanChunks.Column c = ring.peek(cx, cz);
                Attenuation fresh = window.build(ring, c, waves, WIDTH, GAMMA, SyntheticSea.everywhere());
                for (int k = 0; k <= 16; k++) {
                    for (int m = 0; m <= 16; m += 4) {
                        assertEquals(
                            fresh.at(cx * 16 + k, cz * 16 + m),
                            c.shore.at(cx * 16 + k, cz * 16 + m),
                            1e-6,
                            "chunk (" + cx + "," + cz + ")");
                    }
                }
            }
        }
    }

    /** O controle do teste acima: trocar cada costa assim que sai, sem conferir a borda, abre fresta. */
    @Test
    void publishingEachShoreAsItIsBuiltWithoutCheckingTheEdgeDoesOpenACrack() {
        SyntheticSea sea = new SyntheticSea(6, 40);
        sea.island(-40, -10, 4);
        ColumnRing ring = sea.ringOf(5);
        ShoreScheduler s = new ShoreScheduler();
        ShoreScheduler.Builder b = builder(ring, null);
        int guard = 0;
        while (!settled(ring) && guard++ < 300) {
            s.update(ring, b, 0, 0, 1);
        }
        sea.island(-8, 7, 3);
        for (int cz = -1; cz <= 0; cz++) {
            for (int cx = -1; cx <= 0; cx++) {
                sea.reload(ring, cx, cz);
            }
        }
        // ingênuo: o chunk mais perto que precisa de costa a recebe e já a usa
        boolean crack = false;
        for (int frame = 0; frame < 200 && !crack; frame++) {
            OceanChunks.Column best = null;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < ring.capacity(); i++) {
                OceanChunks.Column c = ring.at(i);
                if (c != null && c.any && c.builtGen != c.shoreGen) {
                    double d = c.cx * c.cx + c.cz * c.cz;
                    if (d < bd) {
                        bd = d;
                        best = c;
                    }
                }
            }
            if (best == null) {
                break;
            }
            best.shore = b.build(best);
            best.next = best.shore;
            best.builtGen = best.shoreGen;
            crack = disagreement(ring) != null;
        }
        assertTrue(crack, "sem a conferência da borda a mudança abre fresta: o teste acima tem o que provar");
    }

    @Test
    void aChunkWithoutAShoreYetIsNotDrawnAndAWaitingOneIsPublishedAfterTheLimit() {
        SyntheticSea sea = new SyntheticSea(3, 40);
        ColumnRing ring = sea.ringOf(2);
        ShoreScheduler s = new ShoreScheduler();
        // todos já com mar aberto publicado
        s.update(ring, builder(ring, null), 0, 0, 1);
        assertTrue(settled(ring));
        // um chunk muda e o construtor dá, para ele, um valor que nunca concorda com a sentinela dos vizinhos
        OceanChunks.Column x = ring.peek(0, 0);
        ring.touchShores(0, 0, 0);
        Attenuation half = (px, pz) -> 0.5;
        ShoreScheduler.Builder liar = c -> c == x ? half : Attenuation.OPEN_SEA;
        for (int f = 1; f < ShoreScheduler.FORCE_AFTER; f++) {
            s.update(ring, liar, 0, 0, 1);
            assertSame(Attenuation.OPEN_SEA, x.shore, "esperando os vizinhos: a costa em uso continua a de antes");
        }
        assertEquals(0, s.forced);
        s.update(ring, liar, 0, 0, 1);
        assertSame(half, x.shore, "esperou demais: troca assim mesmo e conta");
        assertEquals(1, s.forced);
    }

    @Test
    void aNeverBuiltChunkIsNotDrawnUntilItsShoreAgrees() {
        SyntheticSea sea = new SyntheticSea(3, 40);
        ColumnRing ring = sea.ringOf(2);
        ShoreScheduler s = new ShoreScheduler();
        s.update(ring, builder(ring, null), 0, 0, 1);
        // um chunk novo (sem costa) ao lado de um vizinho publicado: a costa nova discorda na borda
        OceanChunks.Column fresh = ring.get(0, 0);
        fresh.shore = null;
        fresh.next = null;
        fresh.builtGen = 0;
        Attenuation lower = (px, pz) -> 0.25;
        s.update(ring, c -> lower, 0, 0, 1);
        assertNull(fresh.shore, "sem concordar com os vizinhos desenhados, o chunk novo não é desenhado");
        assertNotNull(fresh.next);
        assertFalse(settled(ring));
    }
}
