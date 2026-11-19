# Arquitetura

```
                      SeaState (17 números, ~140 bytes)            tempo total do mundo (ticks)
                               │                                              │
             ┌─────────────────┴──────────────────┐            S03PacketTimeUpdate (vanilla, 1/s)
             ▼                                    ▼                           ▼
   ┌──────────────────────┐            ┌──────────────────────┐     ┌──────────────────┐
   │ SERVIDOR (Crucible)  │  canal     │ CLIENTE (Java 8)     │     │ OceanClock       │
   │ ServerSea            │ oceanwaves │ ClientSea            │◄────│ reta re-ancorada │
   │ /ondas, API          │──────────► │ (ou a config local)  │     │ monotônica       │
   └─────────┬────────────┘            └─────────┬────────────┘     └──────────────────┘
             │ Synthesis (determinística)        │ Synthesis (a mesma, bit a bit)
             ▼                                   ▼
        WaveSet ═══════════ as mesmas componentes ═══════════ WaveSet
             │                                   │
   OceanField / HeightPatch               OceanMesher → GridEvaluator (fasores tabelados, LOD espectral)
   + ShoreField (blocos do servidor)      + ShoreField (os mesmos blocos, no cliente)
             │                                   │
   física: Expeditions (FloatingBody),    OceanRenderer → Tessellator (passe 1 do terreno)
   plugins, mods (API OceanWaves)         + a face de cima da água vanilla escondida (mixin)
```

## Camadas

### 1. Núcleo (`core/`, `com.akashic.oceanwaves.core`)

Java 8 puro, sem Minecraft, determinístico, com testes. Tudo que é matemática mora aqui e é compartilhado pela malha
e pela física: `Spectrum`, `Spreading`, `Dispersion`, `Synthesis` → `WaveSet`; `OceanField` (consultas pontuais,
inversa de Newton), `HeightPatch` (cascos), `GridEvaluator` + `SpectralLod` + `OceanMesher` (a malha), `ShoreField`
(costa), `OceanClock`, `SurfaceFit`, `SeaPresets`. Detalhes e provas em [MATEMATICA.md](MATEMATICA.md).

Regras: nada de `Math` na síntese (só `StrictMath`); nada alocado nos laços quentes (amostras e buffers
reaproveitados); uma instância por thread de tudo que guarda estado de trabalho (`GridEvaluator`, `OceanField`,
`HeightPatch`).

### 2. Servidor (`server/`, `api/`)

* `ServerSea`: o estado de cada dimensão com ondas (padrão: a 0, da configuração). Manda a quem entra, troca de
  dimensão ou renasce, e a todos da dimensão quando muda (`MessageSeaState` no canal FML `oceanwaves`).
* `/ondas` (`/oceanwaves`, `/ow`), operadores: `info`, `estado <nome>`, `vento <m/s> [graus]`, `altura`, `escala`,
  `crista`, `componentes`, `semente`, `aqui` (a superfície, a atenuação, a inclinação, o jacobiano e a velocidade
  da água no ponto do jogador, com o tempo da consulta), `ligar`, `desligar`. Muda o mar de todos em tempo real.
* `api.OceanWaves`: para mods e plugins Bukkit no Crucible (o jar leva o núcleo; plugin compila `compileOnly`). Só
  tipos do Java e do núcleo nas assinaturas. `state`, `waves`, `setState`, `time` (o tempo total do mundo em
  segundos), `restLevel`, `shore` (a atenuação a partir dos blocos reais), `surfaceY`.

### 3. Cliente (`client/`, `mixin/`)

* `ClientSea`: o estado em vigor (do servidor; sem o mod no servidor, o da configuração para as dimensões listadas),
  as componentes e o relógio. Campos voláteis e imutáveis (a montagem de chunks do Angelica lê de outras threads).
* `NetHandlerPlayClientTimeMixin`: cada `S03PacketTimeUpdate` alimenta o `OceanClock`.
* `BlockLiquidTopFaceMixin`: a face de cima da água some nas colunas de mar aberto
  (`world.OceanColumns.isOpenSea`, a mesma regra do servidor), só com o desenho ligado. Ligar/desligar remonta os
  chunks.
* `OceanChunks`: por chunk, lido do mundo uma vez: colunas de mar, profundidade, cor da água do bioma
  (`Blocks.water.colorMultiplier`, que passa pelos eventos do Forge: o Neo States muda a cor por ali), luz, e a
  atenuação da costa (precisa dos vizinhos). Fica sabendo das mudanças como `IWorldAccess` do mundo do cliente.
* `OceanRenderer`, no fim de `RenderGlobal.sortAndRender` passe 1 (`RenderGlobalOceanPassMixin`): chunks no alcance
  e no frustum, do mais perto para o mais longe; tabelas de fasores do quadro; malha de cada chunk no espaçamento que
  a distância pede, costurada às vizinhas mais grossas (junções em T); a cor de cada vértice da grade pelo
  `SurfaceShader`; vértices no `Tessellator` com cor, normal (para os shaderpacks) e UV na posição de repouso das
  partículas (a textura acompanha o movimento orbital); profundidade escrita, faces dos dois lados.
* `SurfaceShader`: sem shaderpack, a luz é feita aqui e o mapa de luz fica desligado (difusa, Fresnel de Schlick com o
  céu do raio refletido, do horizonte com o pôr do sol ao zênite, Blinn-Phong do sol ou da lua, tom das cristas,
  espuma; a luz da coluna pelo céu × brilho do dia ou pelas tochas). Com shaderpack (`ShaderPacks.inUse()`, por
  reflexão no Iris do Angelica **[a verificar]**), só a cor da água e a espuma, com o mapa de luz: o pack ilumina.
* `IrisBridge`, `ShaderPacks`, `ViewCone`: a integração com os shaderpacks do Iris do Angelica (fase translúcida
  forçada, material da água no `mc_Entity`, recorte por cone, desenho também na passagem de sombra com a malha
  reaproveitada na principal). Ver [adr/ADR-0004](adr/ADR-0004-shaderpacks.md).
* `EdgeFade`: as ondas morrem nos últimos chunks do alcance, para encontrar sem degrau a água plana distante do Mirai.
* `WaterTexture`: o quadro atual da água parada do atlas copiado para uma textura própria com repetição, em tons de
  cinza (o detalhe), com o tom médio passado à cor do vértice: no GL fixo a cor do vértice é multiplicada pela
  textura, e uma textura azul apagaria o reflexo laranja do pôr do sol.
* F3: o estado, Hs, componentes, L, chunks, quads, o custo da malha e do envio, o relógio.

### Por que CPU + `Tessellator` e não um shader

O pack tem jogadores sem OpenGL 3.3 (modo de compatibilidade sem Angelica) e no Android, e o Angelica intercepta
o GL (contexto core, `GLStateManager`): um programa GLSL próprio no meio do mundo brigaria com o Iris quando há
shaderpack e não existe quando não há Angelica. O `Tessellator` é o caminho que o Angelica garante (o Kunftig
desenha tudo por ele ou por VBO simples) e que todo shaderpack recebe com normal e textura. O custo de CPU é pago
com as tabelas de fasores, o LOD espectral e (próxima fase) threads de trabalho. Uma camada em GPU fica como
opcional para quando não há shaderpack. Ver [adr/ADR-0003](adr/ADR-0003-desenho-na-cpu.md).

## Navios (Expeditions)

O Expeditions já integra o empuxo coluna a coluna (`FloatingBody`) contra `WaveField.height(x, z, t)`. A troca:

1. `WaveField` vira uma interface (ou um adaptador) com a altura dada por um `HeightPatch` do OceanWaves, atualizado
   uma vez por tick sobre o retângulo do casco (com as componentes de λ ≥ 2 m: `waves.prefixSet(waves.prefix(2))`),
   e a atenuação `OceanWaves.shore(...)` do mesmo retângulo com folga.
2. **O tempo do mar passa a ser `OceanWaves.time(dimensão)`** (o tempo total do mundo), não o contador do plugin:
   é o que os clientes usam.
3. **A pose vai adiantada.** O Kunftig faz o navio deslizar até a pose em N ticks (`MOTION_TICKS`): a pose
   calculada para o instante t só chega lá em t + N/20 no cliente, e o navio fica atrás da onda. Calcular a física
   até t + N/20 e mandar essa pose com o deslizamento de N ticks põe navio e onda juntos. O atraso de meia latência
   da rede vale para os dois (o relógio do cliente também vem do servidor), então se cancela.
4. O amortecimento pode usar a velocidade da água (`WaveSample.vx/vy/vz`) em vez da do navio sozinha.

Isso é trabalho no repositório do Expeditions (outro PR), não aqui. **[a fazer]**

## Desempenho medido

Núcleo (`./gradlew :core:bench`, este contêiner: 4 núcleos, sem GPU):

| Medida | Java 8 (cliente) | Java 21 (servidor) |
|---|---|---|
| Uma consulta pontual com inversa, 64 componentes (partida fria / quente) | ~7,4 µs / ~4 µs | ~4,8 µs / ~2,5 µs |
| Grade 17 × 17 sem filtro, caminho rápido | ~55 µs | ~42 µs |
| Cena de 441 chunks (raio 12, câmera a 7 m), 28,5 mil vértices, caminho rápido com tabelas do quadro | ~11 ms | ~10,5 ms |

No jogo (harness, distância 8, GL por software; tabela completa em [PLANO-DE-TESTES.md](PLANO-DE-TESTES.md)): o mar
custa **~8 a 10 ms por quadro** neste contêiner (32,5 ms contra 22,8 ms da água vanilla na mesma câmera), dos quais
~5 ms são a malha com a cor, ~3,5 ms a montagem dos vértices no `Tessellator`, e o resto a rasterização em software.

É caro para um processador fraco a 60 FPS. Próximos cortes, em ordem de ganho esperado:

1. **Threads de trabalho**: a malha e a cor do quadro seguinte (tempo previsto) em paralelo enquanto o quadro atual é
   desenhado. As entradas (máscaras, atenuações, componentes) já são imutáveis por quadro e o mesher não toca no
   mundo: só falta o encanamento. Tira os ~5 ms da thread de desenho.
2. **LOD temporal**: chunks longe (espaçamento ≥ 4) refeitos a 20 Hz, com interpolação linear dos vértices entre duas
   avaliações.
3. **VBO por chunk** com só a posição e a cor atualizadas, no lugar do `Tessellator` (onde o Angelica permitir).
4. Camada em GPU (vertex shader) quando não há shaderpack: o custo de CPU vai a quase zero.

## Próximas fases

1. Threads de trabalho e LOD temporal (acima).
2. Camada GPU opcional (vertex shader com as mesmas componentes, sem shaderpack).
3. Física de entidades: barcos vanilla (heave pelo `SurfaceFit`, inclinação desenhada), itens e jogadores
   ("na água" pela superfície da onda), em acordo com o Neo States (registrar um `FluidStage` determinístico no
   `FluidPipeline`, como a ADR-0003 de lá prevê).
4. Névoa submersa coerente (câmera dentro da crista = debaixo d'água; no vale, fora).
5. Esteiras e respingos dos navios.
6. Integração no Expeditions (acima).
