package com.akashic.oceanwaves.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * VIS-4 e VIS-5: a lógica de estado que decide se a face de cima da água vanilla some e quando o terreno é remontado.
 */
class RendererHealthTest {

    @Test
    void topFaceIsHiddenOnlyWhileTheSeaIsActiveAndTheRendererAlive() {
        RendererHealth h = new RendererHealth();
        assertTrue(h.hidesTopFace(true));
        assertFalse(h.hidesTopFace(false), "mar desligado: a água do terreno fica");
        h.fail(new IllegalStateException("x"));
        assertTrue(h.failed());
        assertFalse(h.hidesTopFace(true), "renderizador falhou: a face de cima da água vanilla volta");
        assertTrue(
            h.reason()
                .contains("IllegalStateException"));
    }

    @Test
    void failureAsksForOneRemountOnTheClientTickNotInTheCatch() {
        RendererHealth h = new RendererHealth();
        assertFalse(h.takeRemount(), "sem falha, nada a remontar");
        h.fail(new LinkageError("NoSuchMethodError simulado"));
        // o catch só publica o estado: o aviso é consumido no tick do cliente, uma vez
        assertTrue(h.takeRemount());
        assertFalse(h.takeRemount());
        // falhar de novo (já falhado) não pede outra remontagem
        h.fail(new RuntimeException("de novo"));
        assertFalse(h.takeRemount());
    }

    @Test
    void worldChangeRestartsAndTheNewWorldIsMountedByVanilla() {
        RendererHealth h = new RendererHealth();
        h.fail(new RuntimeException("x"));
        assertTrue(h.takeRemount());
        h.reset();
        assertFalse(h.failed());
        assertTrue(h.hidesTopFace(true));
        assertFalse(h.takeRemount(), "o mundo novo é montado do zero: sem remontagem extra");
        // e uma falha no mundo novo volta a pedir
        h.fail(new RuntimeException("y"));
        assertTrue(h.takeRemount());
    }

    @Test
    void failureBeforeAnyTickStillRemountsWhenItIsTaken() {
        RendererHealth h = new RendererHealth();
        h.fail(new RuntimeException("z"));
        h.reset();
        h.fail(new RuntimeException("w"));
        assertTrue(h.takeRemount());
    }

    @Test
    void remountRequestsOfATickAreMergedIntoOne() {
        RemountRequest r = new RemountRequest();
        assertFalse(r.take(true), "nada pedido");
        r.activation();
        r.level();
        r.region();
        r.failure();
        assertTrue(r.take(true));
        assertFalse(r.take(true), "os pedidos foram consumidos");
    }

    @Test
    void seaLevelChangeRemountsOnlyWhileTheSeaIsActive() {
        RemountRequest r = new RemountRequest();
        r.level();
        assertFalse(r.take(false), "sem mar ativo a face de cima nunca foi escondida: nada a remontar");
        r.level();
        assertTrue(r.take(true), "VIS-5: o nível mudou com o mar ativo, o terreno montado com o outro nível é refeito");
    }

    @Test
    void activationAlwaysRemountsAndRegionAndFailureNeedAnActiveSea() {
        RemountRequest r = new RemountRequest();
        r.activation();
        assertTrue(r.take(false), "desligar o mar: a face de cima precisa voltar");
        r.region();
        assertFalse(r.take(false));
        r.failure();
        assertFalse(r.take(false));
        r.region();
        assertTrue(r.take(true));
    }
}
