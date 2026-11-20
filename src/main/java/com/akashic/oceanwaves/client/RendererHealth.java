package com.akashic.oceanwaves.client;

/**
 * Se o renderizador do mar falhou. A falha desliga o desenho, e a face de cima da água do terreno (que o mod esconde
 * onde desenha a superfície) tem de voltar: sem isto o oceano some por inteiro, sem a folha e sem a água vanilla. O
 * estado é lido nas threads de montagem de chunks do Angelica (por isso o volátil); a remontagem dos chunks, para a
 * face voltar, é pedida aqui mas feita no tick do cliente (nunca dentro do {@code catch} do desenho). Sem Minecraft.
 */
final class RendererHealth {

    private volatile boolean failed;
    private volatile String reason = "";

    /** A thread do cliente: o estado em que os chunks foram montados pela última vez. */
    private boolean applied;

    /** O desenho falhou: desligado nesta sessão (até a troca de mundo). Pode vir de qualquer thread. */
    void fail(Throwable cause) {
        reason = cause == null ? "?" : cause.toString();
        failed = true;
    }

    boolean failed() {
        return failed;
    }

    /** O motivo, para o F3 e o log. */
    String reason() {
        return reason;
    }

    /**
     * A face de cima da água do terreno some? Só com o mar ativo e o renderizador vivo. Chamado na montagem de chunks.
     */
    boolean hidesTopFace(boolean active) {
        return active && !failed;
    }

    /**
     * Pelo tick do cliente: o estado de falha mudou desde a última remontagem dos chunks? Consome o aviso (uma vez
     * por mudança). Verdadeiro: o terreno precisa ser remontado para a face de cima voltar (ou sumir).
     */
    boolean takeRemount() {
        boolean f = failed;
        if (f == applied) {
            return false;
        }
        applied = f;
        return true;
    }

    /** Troca de mundo (thread do cliente): recomeça; o mundo novo é montado do zero pelo vanilla. */
    void reset() {
        failed = false;
        reason = "";
        applied = false;
    }
}
