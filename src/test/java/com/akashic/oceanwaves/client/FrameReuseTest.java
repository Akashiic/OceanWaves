package com.akashic.oceanwaves.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.akashic.oceanwaves.core.SpectralLod;

/** PERF-7: o que o desenho reaproveita de um quadro para o outro ({@link ChunkSpans}, {@link LodMemo}). */
class FrameReuseTest {

    @Test
    void theSpansGrowByDoublingAndNeverShrink() {
        ChunkSpans s = new ChunkSpans();
        s.reset(40);
        assertEquals(40, s.count());
        int c1 = s.capacity();
        assertTrue(c1 >= 40);
        s.reset(50);
        assertEquals(50, s.count());
        assertTrue(s.capacity() >= 50 && s.capacity() >= 2 * c1, "dobra: poucas alocações ao crescer");
        int c2 = s.capacity();
        s.reset(10);
        assertEquals(10, s.count(), "a contagem é a do passe, não a do vetor");
        assertEquals(c2, s.capacity(), "não encolhe e não aloca de novo");
        s.reset(c2);
        assertEquals(c2, s.capacity(), "cabendo, nada é alocado");
    }

    @Test
    void theSpansHoldWhereEachChunkStartsAndEnds() {
        ChunkSpans s = new ChunkSpans();
        s.reset(3);
        s.begin(0, 0);
        s.begin(1, 40);
        s.begin(2, 40); // um chunk sem vértices
        s.end(90);
        assertEquals(0, s.start(0));
        assertEquals(40, s.stop(0));
        assertEquals(40, s.start(1));
        assertEquals(40, s.stop(1));
        assertEquals(40, s.start(2));
        assertEquals(90, s.stop(2));
    }

    @Test
    void theLodIsReusedWhileTheCameraStandsStill() {
        LodMemo m = new LodMemo();
        SpectralLod a = m.get(10.5, 70, -3.25, 62.9, 1.0, 0.02, 2.5, 5.0);
        assertSame(a, m.get(10.5, 70, -3.25, 62.9, 1.0, 0.02, 2.5, 5.0), "parado: o mesmo objeto, nada alocado");
        assertEquals(10.5, a.camX, 0);
        assertEquals(62.9, a.seaY, 0);
    }

    @Test
    void anyChangeOfTheInputsGivesANewLodWithTheNewValues() {
        double[] base = { 10.5, 70, -3.25, 62.9, 1.0, 0.02, 2.5, 5.0 };
        for (int i = 0; i < base.length; i++) {
            LodMemo m = new LodMemo();
            SpectralLod a = m.get(base[0], base[1], base[2], base[3], base[4], base[5], base[6], base[7]);
            double[] q = base.clone();
            q[i] += 0.5;
            SpectralLod b = m.get(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7]);
            assertNotSame(a, b, "parâmetro " + i + " mudou");
            assertEquals(
                q[i],
                new double[] { b.camX, b.camY, b.camZ, b.seaY, b.minSpacing, b.factor, b.fadeStart, b.fadeEnd }[i],
                0);
            // e o novo vale daí em diante
            assertSame(b, m.get(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7]));
        }
    }

    @Test
    void theChunksComeOutNearestFirstAndNoneIsLost() {
        Random r = new Random(7);
        List<OceanChunks.Column> list = new ArrayList<>();
        for (int cx = -9; cx <= 9; cx++) {
            for (int cz = -9; cz <= 9; cz++) {
                list.add(new OceanChunks.Column(cx, cz));
            }
        }
        Collections.shuffle(list, r);
        int n = list.size();
        double camX = 13.7, camZ = -40.2;
        NearestFirst.sort(list, camX, camZ);
        assertEquals(n, list.size());
        assertEquals(n, new java.util.HashSet<>(list).size(), "nenhum chunk repetido nem perdido");
        for (int i = 1; i < n; i++) {
            assertTrue(
                NearestFirst.dist2(list.get(i - 1), camX, camZ) <= NearestFirst.dist2(list.get(i), camX, camZ),
                "fora de ordem em " + i);
        }
        // a lista vazia e a de um elemento não fazem nada
        NearestFirst.sort(new ArrayList<>(), 0, 0);
        List<OceanChunks.Column> one = new ArrayList<>();
        one.add(list.get(0));
        NearestFirst.sort(one, 0, 0);
        assertEquals(1, one.size());
    }
}
