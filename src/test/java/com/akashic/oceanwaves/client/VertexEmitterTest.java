package com.akashic.oceanwaves.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashic.oceanwaves.core.FloatBuffer;
import com.akashic.oceanwaves.core.OceanMesher;

/**
 * O laço de envio dos vértices ({@link VertexEmitter}) exercitado sem Minecraft, com um gravador no lugar do
 * {@code Tessellator}. O gravador guarda, a cada vértice, o estado que o Tessellator teria (cor, luz e normal valem até
 * serem trocadas): se o laço pula chamadas, o estado de cada vértice tem de continuar o do buffer.
 *
 * <p>
 * O teste que importa é o dos quadros de tamanhos diferentes: o vetor de inícios ({@link ChunkSpans}) só cresce, então
 * o comprimento dele passa da contagem de chunks assim que ela cai, ou assim que ele dobra. O laço de envio já foi até
 * o fim do vetor (lia chunks que não existem, lançava no meio do desenho e o renderizador se desligava na sessão).
 */
class VertexEmitterTest {

    /** Um vértice como o Tessellator o teria guardado. */
    private static final class Vertex {

        double x, y, z, u, v;
        int translation;
        int color, brightness;
        float nx, ny, nz;
    }

    private static final class Recorder implements VertexSink {

        final List<double[]> translations = new ArrayList<>();
        final List<Vertex> vertices = new ArrayList<>();
        int colorCalls, brightnessCalls, normalCalls;
        int color, brightness = -1;
        float nx, ny, nz;

        @Override
        public void setTranslation(double x, double y, double z) {
            translations.add(new double[] { x, y, z });
        }

        @Override
        public void setColorRGBA(int r, int g, int b, int a) {
            color = r << 24 | g << 16 | b << 8 | a;
            colorCalls++;
        }

        @Override
        public void setBrightness(int b) {
            brightness = b;
            brightnessCalls++;
        }

        @Override
        public void setNormal(float x, float y, float z) {
            nx = x;
            ny = y;
            nz = z;
            normalCalls++;
        }

        @Override
        public void addVertexWithUV(double x, double y, double z, double u, double v) {
            Vertex t = new Vertex();
            t.x = x;
            t.y = y;
            t.z = z;
            t.u = u;
            t.v = v;
            t.translation = translations.size() - 1;
            t.color = color;
            t.brightness = brightness;
            t.nx = nx;
            t.ny = ny;
            t.nz = nz;
            vertices.add(t);
        }
    }

    /** Os chunks de uma cena: o chunk j tem {@code 4 + j % 5} vértices, cada um reconhecível pelo que carrega. */
    private static final class Scene {

        final List<OceanChunks.Column> visible = new ArrayList<>();
        final FloatBuffer buffer = new FloatBuffer();
        final ChunkSpans spans;
        int vertices;

        Scene(ChunkSpans spans, int chunks, boolean varyColor) {
            this.spans = spans;
            spans.reset(chunks);
            for (int j = 0; j < chunks; j++) {
                // posições do anel de chunks de verdade: negativas também (a textura usa cx & 63)
                OceanChunks.Column c = new OceanChunks.Column(j - 7, 3 - 2 * j);
                for (int i = 0; i < c.light.length; i++) {
                    c.light[i] = (j * 31 + i) << 4;
                }
                visible.add(c);
                spans.begin(j, buffer.size);
                int n = 4 + j % 5;
                for (int i = 0; i < n; i++) {
                    buffer.reserve(OceanMesher.FLOATS);
                    buffer.put(j * 1000 + i); // x: de quem é o vértice
                    buffer.put(i * 0.25f);
                    buffer.put(-j);
                    buffer.put(0.1f * i);
                    buffer.put(1f - 0.05f * i);
                    buffer.put(0.01f * j);
                    buffer.put(0);
                    buffer.put((i * 3 + j) % 16); // célula x
                    buffer.put((i * 5 + 2 * j) % 16); // célula z
                    int tone = varyColor ? (j + i / 2) % 3 : 0;
                    buffer.put(Float.intBitsToFloat(0x3F000000 | (0x10 + tone) << 16 | 0x4000 | 0xC0 + tone));
                    vertices++;
                }
            }
            spans.end(buffer.size);
        }
    }

    private static Recorder emit(Scene s, boolean own, double camX, double camY, double camZ, double rest) {
        Recorder r = new Recorder();
        VertexEmitter.emit(r, s.visible, s.spans, s.buffer.data, own, camX, camY, camZ, rest);
        return r;
    }

    /** Confere cada vértice contra o buffer e contra o que o Tessellator teria guardado se tudo fosse mandado. */
    private static void assertAllEmitted(Scene s, Recorder r, boolean own, double camX, double camY, double camZ,
        double rest) {
        assertEquals(s.visible.size(), r.translations.size(), "uma translação por chunk");
        assertEquals(s.vertices, r.vertices.size(), "todos os vértices, de todos os chunks");
        int v = 0;
        for (int j = 0; j < s.visible.size(); j++) {
            OceanChunks.Column c = s.visible.get(j);
            double[] t = r.translations.get(j);
            assertEquals(c.cx * 16.0 - camX, t[0], 0);
            assertEquals(rest - camY, t[1], 0);
            assertEquals(c.cz * 16.0 - camZ, t[2], 0);
            for (int k = s.spans.start(j); k < s.spans.stop(j); k += OceanMesher.FLOATS, v++) {
                float[] d = s.buffer.data;
                Vertex e = r.vertices.get(v);
                assertEquals(j, e.translation, "o vértice " + v + " é do chunk " + j);
                assertEquals(d[k], e.x, 0);
                assertEquals(d[k + 1], e.y, 0);
                assertEquals(d[k + 2], e.z, 0);
                assertEquals(((c.cx & 63) * 16.0) + d[k + 7], e.u, 0);
                assertEquals(((c.cz & 63) * 16.0) + d[k + 8], e.v, 0);
                int rgba = Float.floatToRawIntBits(d[k + 9]);
                assertEquals(rgba, e.color, "cor do vértice " + v);
                if (!own) {
                    int col = (int) d[k + 7] + 16 * (int) d[k + 8];
                    assertEquals(c.light[col], e.brightness, "luz do vértice " + v);
                    assertEquals(d[k + 3], e.nx, 0);
                    assertEquals(d[k + 4], e.ny, 0);
                    assertEquals(d[k + 5], e.nz, 0);
                }
            }
        }
    }

    /**
     * O defeito de antes: o vetor de inícios dobrava (ou ficava maior que a contagem) e o laço de envio ia até o fim
     * dele. Quadro 1 com 40 chunks, quadro 2 com 50 (o vetor passa a 82 posições), depois a contagem cai, sobe, zera.
     */
    @Test
    void everyFrameEmitsExactlyItsChunksWhateverTheSizeOfTheStartsVector() {
        ChunkSpans spans = new ChunkSpans();
        int[] frames = { 40, 50, 10, 200, 3, 3, 0, 1, 120, 77 };
        for (int n : frames) {
            Scene s = new Scene(spans, n, true);
            for (boolean own : new boolean[] { true, false }) {
                Recorder r = emit(s, own, 3.25, 70.5, -11.75, 62.9);
                assertAllEmitted(s, r, own, 3.25, 70.5, -11.75, 62.9);
            }
            assertEquals(n, spans.count());
        }
        // a pré-condição do defeito: o vetor ficou maior que a contagem do último quadro
        assertTrue(spans.capacity() > 77, "o teste só vale se o vetor passa da contagem");
    }

    @Test
    void aShrinkingFrameDoesNotReadStaleChunks() {
        ChunkSpans spans = new ChunkSpans();
        Scene big = new Scene(spans, 60, false);
        emit(big, true, 0, 0, 0, 0);
        Scene small = new Scene(spans, 5, false);
        assertTrue(spans.capacity() >= 60);
        Recorder r = emit(small, true, 0, 0, 0, 0);
        assertEquals(5, r.translations.size());
        assertEquals(small.vertices, r.vertices.size());
    }

    /** Com a luz própria (sem shaderpack) a luz do mapa e a normal não vão: a luz já está na cor. */
    @Test
    void withOwnLightNeitherTheMapLightNorTheNormalIsSent() {
        Scene s = new Scene(new ChunkSpans(), 12, true);
        Recorder r = emit(s, true, 0, 0, 0, 63);
        assertEquals(0, r.normalCalls);
        assertEquals(0, r.brightnessCalls);
        assertTrue(r.colorCalls > 0);
    }

    /** Sem luz própria a normal vai em todo vértice e a luz só quando muda. */
    @Test
    void withoutOwnLightTheNormalGoesWithEveryVertexAndTheLightOnlyWhenItChanges() {
        Scene s = new Scene(new ChunkSpans(), 12, true);
        Recorder r = emit(s, false, 0, 0, 0, 63);
        assertEquals(s.vertices, r.normalCalls);
        assertTrue(r.brightnessCalls > 0 && r.brightnessCalls <= s.vertices);
        int changes = 0, last = Integer.MIN_VALUE;
        for (Vertex v : r.vertices) {
            if (v.brightness != last) {
                changes++;
                last = v.brightness;
            }
        }
        assertEquals(changes, r.brightnessCalls, "uma chamada por troca de luz, nenhuma a mais");
    }

    /** A cor só é mandada quando muda (com shaderpack é a mesma em quase todo vértice). */
    @Test
    void theColourIsSentOnlyWhenItChanges() {
        Scene same = new Scene(new ChunkSpans(), 20, false);
        Recorder r = emit(same, true, 0, 0, 0, 63);
        assertEquals(1, r.colorCalls, "uma só cor em todos os vértices: uma só chamada");
        Scene varied = new Scene(new ChunkSpans(), 20, true);
        Recorder q = emit(varied, true, 0, 0, 0, 63);
        int changes = 0, last = 0;
        for (Vertex v : q.vertices) {
            if (changes == 0 || v.color != last) {
                changes++;
                last = v.color;
            }
        }
        assertEquals(changes, q.colorCalls);
        assertTrue(q.colorCalls > 1 && q.colorCalls < varied.vertices);
    }
}
