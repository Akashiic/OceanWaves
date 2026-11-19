# ADR-0002: o tempo do mar é o tempo total do mundo, e a síntese é bit a bit determinística

**Estado:** aceita (19/11/2026).

## Contexto

Servidor e clientes precisam da mesma onda no mesmo lugar ao mesmo tempo. O Physics Mod usa um tempo do cliente (cada
jogador vê uma fase; o servidor vê água plana). O Expeditions usava um contador de ticks do próprio plugin.

## Decisão

* **Tempo**: `World.getTotalWorldTime()` / 20 (monotônico; `/time set` não mexe nele). O servidor vanilla já o manda a
  cada 20 ticks no `S03PacketTimeUpdate`; o cliente o lê num mixin em `handleTimeUpdate` e o filtra no `OceanClock`
  (reta re-ancorada, monotônica, que segue um servidor lento). Não precisa de pacote próprio nem do mod no servidor.
* **Estado**: o servidor manda só o `SeaState` (17 números). Cada lado sintetiza as componentes com `StrictMath` e um
  SplitMix64 próprio (a sequência de `java.util.Random` é fixada pela especificação, mas `SplittableRandom` e
  `Math.sin` não são). O resumo das componentes do estado "moderado" é fixado num teste e passa igual no Java 8 e no
  21.
* As avaliações por quadro e por tick usam `Math` (rápido): a diferença entre JVMs é de 1 ulp, ~10⁻¹⁶ m.

## Consequências

* O atraso de meia latência do pacote fica no relógio do cliente; as poses dos navios chegam com o mesmo atraso, então
  se cancelam. Um navio que desliza N ticks até a pose precisa que a pose seja calculada N ticks à frente
  (ARQUITETURA.md, "Navios").
* Mudar a síntese muda o resumo fixado: é uma mudança de protocolo (clientes e servidor de versões diferentes
  desenhariam mares diferentes). O `MessageSeaState` tem versão.
