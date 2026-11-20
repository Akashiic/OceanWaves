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

* `ServerSea`: o estado de cada dimensão com ondas. Os mundos vêm de `mar.mundos`, pelo nome da pasta (os do
  Multiverse ganham número de dimensão só ao carregar, então as ondas ligam no `WorldEvent.Load` e saem no `Unload`)
  ou pelo número; sem nenhum deles no servidor, o principal. Cada um tem o seu nível do mar: o da configuração
  (`Akashic/Spawn@31`) ou o medido em volta do spawn e dos jogadores (a maioria das colunas de uma grade de 13 × 13 pontos acabando em
  água no mesmo y), medido de novo a cada segundo enquanto os chunks não estão carregados. Uma vez por segundo confere se algum jogador trocou de
  mundo sem os eventos do FML (teleporte de plugin) e manda o mar do novo. Manda a quem entra, troca de
  dimensão ou renasce, e a todos da dimensão quando muda (`MessageSeaState` no canal FML `oceanwaves`).
* `/ondas` (`/oceanwaves`, `/ow`), operadores: `info`, `estado <nome>`, `vento <m/s> [graus]`, `altura`, `escala`,
  `crista`, `componentes`, `semente`, `aqui` (a superfície, a atenuação, a inclinação, o jacobiano e a velocidade
  da água no ponto do jogador, com o tempo da consulta), `regiao <spawn|aqui|tudo> [raio]`, `ligar`, `desligar`.
  Muda o mar de todos em tempo real.
* **Região** (`core.SeaRegion`): o mundo todo (padrão) ou um círculo (em volta do spawn ou de um ponto). Uma coluna
  fora dela não é "mar aberto" para nada: nem para o mixin da face vanilla, nem para o cache do cliente, nem para a
  `shore` da API. Por isso a borda entra no `ShoreField` exatamente como uma costa (a distância até ela e o limite de
  gradiente), e vale a mesma garantia de não dobrar. A região vai no fim da `MessageSeaState` (versão 2; um cliente
  da versão 1 ignora os bytes a mais, um servidor da 1 não manda e o cliente fica com a da configuração).
* `api.OceanWaves`: para mods e plugins Bukkit no Crucible (o jar leva o núcleo; plugin compila `compileOnly`). Só
  tipos do Java e do núcleo nas assinaturas. `state`, `waves`, `setState`, `time` (o tempo total do mundo em
  segundos), `restLevel`, `shore` (a atenuação a partir dos blocos reais e da região), `shoreMargin` (a folga que
  a atenuação de um retângulo pede em volta dele), `region`, `surfaceY`, `dimensionOf` (API 2: a dimensão de um
  mundo pelo nome do Bukkit, para um plugin achar a do mundo onde está).

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

O Expeditions integra o empuxo coluna a coluna (`FloatingBody`) contra uma superfície `SeaSurface.height(x, z, t)`.
Feito no branch `claude/minecraft-ocean-physics-mod-n82b79` do Expeditions (`plugin/OceanWavesSea`, a única classe
dele que toca no OceanWaves), assim:

1. Dois `HeightPatch` sobre o retângulo do casco, no começo e no fim de cada intervalo de `MOTION_TICKS`; os
   subpassos da física interpolam no tempo entre eles. Só as componentes de λ ≥ 4 m
   (`waves.prefixSet(waves.prefix(4))`): as mais curtas se cancelam somadas sobre um casco de dezenas de metros. A
   atenuação é `OceanWaves.shore(...)` do retângulo com 16 blocos de folga mais `shoreMargin`, relida quando o navio
   sai dela, o mar muda ou a cada minuto.
2. **O tempo do mar passa a ser `OceanWaves.time(dimensão)`** (o tempo total do mundo), não o contador do plugin:
   é o que os clientes usam.
3. **A pose vai adiantada.** O Kunftig faz o navio deslizar até a pose em N ticks (`MOTION_TICKS`): a pose
   calculada para o instante t só chega lá em t + N/20 no cliente, e o navio fica atrás da onda. Calcular a física
   até t + N/20 e mandar essa pose com o deslizamento de N ticks põe navio e onda juntos. O atraso de meia latência
   da rede vale para os dois (o relógio do cliente também vem do servidor), então se cancela.
4. O vento da banda é o vento do mar (`SeaState.windSpeed/windDirection`), e `/exp vento` muda o do OceanWaves.
5. O mundo do navio vira dimensão por `OceanWaves.dimensionOf(world.getName())`; só nos mundos com ondas e com a
   linha d'água no nível do mar do mod; fora disso o plugin usa o mar próprio dele. Sem o mod no servidor (ou com a
   API 1), nada muda no Expeditions.

6. **No plano** (avanço, deriva e guinada; `ShipMotion` no Expeditions): as mesmas componentes do OceanWaves
   (`WaveSet` com a profundidade do `SeaState`) dão a oscilação (Froude-Krylov + massa adicional sobre o volume
   submerso), a deriva média (reflexão de Ursell na linha d'água) e a deriva de Stokes; a atenuação da costa e da região
   (`shore(...).at(cg)`) multiplica as amplitudes. O arrasto é sobre a velocidade relativa à água. Documento:
   `docs/DERIVA.md` do Expeditions.

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
