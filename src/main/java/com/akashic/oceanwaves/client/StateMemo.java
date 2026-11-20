package com.akashic.oceanwaves.client;

/**
 * O estado do GL que o desenho do mar guarda para devolver (máscara de profundidade, corte de faces, mistura), lido
 * uma vez e reaproveitado. Um {@code glGet*} por quadro é de graça com o Angelica (que guarda esses estados), mas sem
 * ele pode esperar o driver; e o que o vanilla deixa ligado ao fim do passe translúcido não muda de um quadro para o
 * outro. A leitura é refeita quando a situação muda (a chave: passagem de sombra, shaderpack, gráficos, mundo) e, por
 * segurança, a cada {@link #REFRESH} usos. Sem Minecraft nem GL.
 */
final class StateMemo {

    /** Usos entre duas leituras de verdade. */
    static final int REFRESH = 120;

    private boolean valid;
    private long key;
    private int uses;

    boolean depthMask;
    boolean cull;
    boolean blend;

    /** Precisa ler o estado do GL agora? Conta um uso. */
    boolean due(long situation) {
        if (!valid || key != situation || ++uses >= REFRESH) {
            return true;
        }
        return false;
    }

    /** Guarda o que foi lido. */
    void store(long situation, boolean depthMask, boolean cull, boolean blend) {
        valid = true;
        key = situation;
        uses = 0;
        this.depthMask = depthMask;
        this.cull = cull;
        this.blend = blend;
    }

    /** Esquece (troca de mundo, falha, mudança de configuração). */
    void invalidate() {
        valid = false;
    }
}
