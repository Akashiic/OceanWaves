package com.akashic.oceanwaves.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ColumnRingTest {

    @Test
    void getCreatesOnceAndPeekNeverCreates() {
        ColumnRing r = new ColumnRing();
        r.ensure(6);
        assertNull(r.peek(3, -4));
        OceanChunks.Column c = r.get(3, -4);
        assertSame(c, r.get(3, -4));
        assertSame(c, r.peek(3, -4));
        assertEquals(1, r.size());
        assertNull(r.peek(3, -3));
    }

    @Test
    void chunksFarApartNeverShareAPlaceWhileInsideTheKeptRadius() {
        int radius = 8;
        ColumnRing r = new ColumnRing();
        r.ensure(radius);
        int center = 1000;
        OceanChunks.Column[][] all = new OceanChunks.Column[2 * radius + 5][2 * radius + 5];
        // tudo o que prune() deixa (raio + folga) ao mesmo tempo, longe da origem e com coordenadas negativas
        for (int dz = -(radius + 2); dz <= radius + 2; dz++) {
            for (int dx = -(radius + 2); dx <= radius + 2; dx++) {
                all[dz + radius + 2][dx + radius + 2] = r.get(center + dx, -center + dz);
            }
        }
        assertEquals((2 * radius + 5) * (2 * radius + 5), r.size());
        for (int dz = -(radius + 2); dz <= radius + 2; dz++) {
            for (int dx = -(radius + 2); dx <= radius + 2; dx++) {
                assertSame(all[dz + radius + 2][dx + radius + 2], r.peek(center + dx, -center + dz));
            }
        }
    }

    @Test
    void pruneForgetsTheFarOnesAndAStaleOccupantIsReplaced() {
        ColumnRing r = new ColumnRing();
        r.ensure(4);
        OceanChunks.Column near = r.get(0, 0);
        OceanChunks.Column far = r.get(5, 0);
        r.prune(0, 0, 3);
        assertSame(near, r.peek(0, 0));
        assertSame(far, r.peek(5, 0), "5 = raio 3 + folga 2: fica");
        r.prune(0, 0, 2);
        assertNull(r.peek(5, 0));
        assertEquals(1, r.size());
        // o mesmo lugar do anel, ocupado por um chunk que ficou para trás, é de quem chega
        int side = 2 * (4 + ColumnRing.SLACK) + 3;
        OceanChunks.Column old = r.get(1, 1);
        OceanChunks.Column arrival = r.get(1 + side, 1);
        assertNotSame(old, arrival);
        assertNull(r.peek(1, 1));
        assertSame(arrival, r.peek(1 + side, 1));
    }

    @Test
    void growingKeepsWhatIsThere() {
        ColumnRing r = new ColumnRing();
        r.ensure(2);
        OceanChunks.Column a = r.get(1, 1);
        OceanChunks.Column b = r.get(-2, 3);
        r.ensure(12);
        assertSame(a, r.peek(1, 1));
        assertSame(b, r.peek(-2, 3));
        assertEquals(2, r.size());
        r.ensure(3);
        assertSame(a, r.peek(1, 1), "o anel não encolhe");
    }

    @Test
    void touchBumpsTheShoreGenerationOfTheSquareAroundAChunk() {
        ColumnRing r = new ColumnRing();
        r.ensure(6);
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                r.get(dx, dz);
            }
        }
        r.touchShores(0, 0, 2);
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                int expected = Math.abs(dx) <= 2 && Math.abs(dz) <= 2 ? 2 : 1;
                assertEquals(expected, r.peek(dx, dz).shoreGen, dx + "," + dz);
            }
        }
    }
}
