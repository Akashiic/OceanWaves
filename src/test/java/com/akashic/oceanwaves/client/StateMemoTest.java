package com.akashic.oceanwaves.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** PERF-8: o estado do GL que se devolve é lido de vez em quando, não todo quadro. */
class StateMemoTest {

    @Test
    void readsOnceAndAgainWhenTheSituationChangesOrAfterTheRefreshPeriod() {
        StateMemo m = new StateMemo();
        assertTrue(m.due(1), "primeira vez: lê");
        m.store(1, true, false, true);
        int reads = 0;
        for (int i = 0; i < 3 * StateMemo.REFRESH; i++) {
            if (m.due(1)) {
                reads++;
                m.store(1, true, false, true);
            }
        }
        // lê de novo a cada REFRESH usos: três voltas, três leituras (e nenhuma por quadro)
        assertEquals(3, reads);
        assertTrue(m.depthMask);
        assertFalse(m.cull);
        assertTrue(m.blend);
        assertTrue(m.due(2), "a situação mudou (shaderpack ligou, passagem de sombra...): lê");
        m.store(2, false, true, false);
        assertFalse(m.due(2));
        assertFalse(m.depthMask);
        m.invalidate();
        assertTrue(m.due(2), "troca de mundo: lê");
    }
}
