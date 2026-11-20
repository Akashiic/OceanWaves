package com.akashic.oceanwaves.client;

/**
 * O que obriga o terreno a ser remontado ({@code loadRenderers}): a face de cima da água vanilla depende de o mar
 * estar ativo, da região, do nível do mar e de o renderizador estar vivo, e só vale na montagem do chunk. Os pedidos
 * de um tick se juntam e viram uma remontagem só (um pico de segundos com o pack real, não vale repetir). Sem
 * Minecraft; só a thread do cliente.
 */
final class RemountRequest {

    private boolean activation;
    private boolean region;
    private boolean level;
    private boolean failure;

    /** O mar ligou ou desligou. */
    void activation() {
        activation = true;
    }

    /** A região mudou. */
    void region() {
        region = true;
    }

    /** O nível do mar mudou. */
    void level() {
        level = true;
    }

    /** O renderizador falhou (ou voltou). */
    void failure() {
        failure = true;
    }

    /**
     * Consome os pedidos. Verdadeiro se é preciso remontar: ligar ou desligar sempre; região, nível e falha só se o mar
     * está ativo (desativado, a face de cima nunca é escondida e não há o que remontar).
     */
    boolean take(boolean activeNow) {
        boolean r = activation || activeNow && (region || level || failure);
        activation = false;
        region = false;
        level = false;
        failure = false;
        return r;
    }
}
