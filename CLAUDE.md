# OceanWaves: contexto para quem trabalha neste repositório

Mod Forge 1.7.10 (cliente + servidor) de ondas oceânicas para o modpack **TEOF** (Crucible no servidor, Java 21;
clientes em Java 8, a maioria com Angelica). Parte da expansão marítima: o
[Expeditions](https://github.com/Akashiic/Expeditions) (navios, plugin) usa o mar daqui para a física; o
[Kunftig](https://github.com/Akashiic/Kunftig) desenha os navios; o [Neo States](https://github.com/Akashiic/Neo-States)
é a água do 1.21.1. Comece por `README.md` e `docs/`.

## Estrutura

| Pasta | Conteúdo |
|---|---|
| `core/` | `com.akashic.oceanwaves.core`: a matemática pura, Java 8, sem Minecraft, com testes JUnit. Espectro, síntese, campo, inversa, malha, costa, relógio |
| `src/main/java/.../oceanwaves` | o mod: `OceanWavesMod`, `OceanConfig`, `server/` (estado por dimensão, `/ondas`), `api/` (para mods e plugins), `net/`, `world/OceanColumns` (a regra das colunas de mar, igual nos dois lados), `client/` (relógio, cache de chunks, renderizador, sombreamento), `mixin/` |
| `src/main/java/.../dev` | o harness de desenvolvimento (nunca no jar de lançamento: o `shadowJar` exclui) |
| `docs/` | pesquisa, matemática, arquitetura, plano de testes, ADRs, capturas |

```bash
./gradlew spotlessApply build -x javadoc     # o spotless reformata: confira as substituições feitas por script
./gradlew :core:bench
./gradlew clientBench    # o caminho do renderizador fora do jogo, antes e depois (ver docs/PLANO-DE-TESTES.md, 2b)
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient   # com run/client/oceanwaves-harness
```

## Regras

* Documentação e mensagens em **português (pt-BR)**.
* **Determinismo**: a síntese (`Synthesis`, `Spectrum`, `Spreading`, `Dispersion`) só usa `StrictMath` e o
  `SplitMix64`. `SynthesisTest.fingerprintIsPinned` fixa o resumo das componentes: se mudar, é mudança de protocolo
  (suba `MessageSeaState.VERSION` e diga no commit). Rodar os testes também no Java 8
  (`~/.gradle/jdks/temurin-8-*`) quando mexer na síntese.
* **O tempo do mar é o tempo total do mundo / 20**, nos dois lados. Nunca um contador próprio.
* **A superfície nunca dobra**: L = Σ Qkа < `maxSteepness` (síntese) e o limite de gradiente da costa
  (`ShoreField`, em B = −ln(1 − L·A)/L). Mudou a forma do deslocamento ou da atenuação: rode
  `surfaceNeverFolds` e `shoreNeverFoldsTheSurface`.
* **A regra "coluna de mar aberto" é uma só** (`world.OceanColumns.isOpenSea`): servidor, cliente e o mixin da face
  vanilla. Ela roda nas threads de montagem de chunks do Angelica: só leia o `IBlockAccess` recebido e campos
  voláteis.
* **Nada alocado nos laços quentes** (malha, sombreamento, física): amostras e buffers reaproveitados; uma instância
  por thread de `GridEvaluator`, `OceanField`, `HeightPatch`, `OceanMesher`.
* **Atenuação por pedaços**: a folga em volta de um chunk vem de `ShoreField.influenceRadius` (a faixa de costa e o
  alcance do limite de gradiente), não da largura da costa. Com menos, dois chunks vizinhos discordam na borda e abre
  fresta (visto no ambiente real). Rode `neighbouringChunksAgreeOnTheirEdge`.
* **Malha sem frestas**: o peso do LOD é função só da posição e da câmera; as arestas com vizinho mais grosso são
  costuradas (`OceanMesher.stitch`). Rode `neighboursOfDifferentResolutionShareTheirEdge` e
  `stitchingClosesTJunctions`.
* GL: sempre pelo `Tessellator` e pelos estados que o vanilla usa (o Angelica intercepta o GL; nada de
  `glPushAttrib`, programas próprios ou funções que o perfil core não tem). Guardar e devolver o estado mexido.
* **Shaderpacks** (ADR-0004): o mar é desenhado com a fase `TERRAIN_TRANSLUCENT` forçada e o material da água no
  `mc_Entity` (`IrisBridge`, por reflexão). O Angelica reescreve o `sortAndRender` e fecha a fase no fim: nada que
  dependa da fase pode ser desenhado depois dele sem o override. Com shaderpack o frustum vanilla não serve (use o
  `ViewCone`) e a passagem de sombra também chama o passe 1 (`ShaderPacks.renderingShadowPass`).
* **A textura do mar é a da água dos chunks** (`WaterTexture.stillIcon`): com o Neo States, o sprite cinza dele
  (`GrayWater.icon`, por reflexão), porque fora da montagem de chunks o bloco devolve o azul do vanilla e a cor do bioma
  do 1.21.1 sobre ele escurece o mar. Mudou a cor ou a textura do mar: compare com `/ondas desligar` no mesmo lugar.
* **Cada mundo tem o seu nível do mar** (`ServerSea.seaLevel(dim)`, `OceanWaves.restLevel(dim)`, na mensagem aos
  clientes): o spawn do TEOF tem a água em y 30 (nível 31), não 63. Nada de `OceanConfig.seaLevel` direto fora do
  `ServerSea` e do modo local do cliente.
* **Mundos por nome, não por número**: os mundos do Multiverse (o spawn do TEOF é o `Akashic/Spawn`) ganham a
  dimensão só ao carregar e podem trocar de número a cada início. A configuração e a API (`dimensionOf`) vão por
  nome; o cliente aplica o mar ao mundo em que está, mandado pelo servidor a cada troca.
* **Não conte com os avisos do mundo**: com o Celeritas o `markBlockRangeForRenderUpdate` não chega aos
  `IWorldAccess` registrados. O cache confere por conta própria se um chunk chegou (`OceanChunks.arrived`, por quadro,
  só para os ainda não lidos) e relê em rodízio **no tick do cliente** (`OceanChunks.maintain`), não no quadro: a cor do
  bioma e a profundidade não mudam entre dois quadros.
* **A costa é refeita aos poucos e só troca quando as bordas concordam** (`ShoreScheduler`, `ShoreWindow`): uma mudança
  marca os 5 × 5 chunks em volta; do mais perto, `desenho.costasPorQuadro` costas de verdade por quadro (janela de mar
  aberto fundo é a sentinela `Attenuation.OPEN_SEA`, sem conta e sem orçamento); o chunk só passa a usar a costa nova se
  nos vértices da aresta e do canto que divide com cada vizinho desenhado ela dá o mesmo valor (os que discordam trocam
  juntos). Um chunk sem costa não é desenhado. `Column.shore` nunca é `null` depois de pronta (sem costa: `OPEN_SEA`).
  Mexeu nisto: rode `ShoreSchedulerTest` e `ShoreWindowTest`, além dos testes de borda do core.
* Não quebrar o jogo: o renderizador desliga a si mesmo com um log se lançar `RuntimeException` ou `LinkageError`, e a
  face de cima da água do terreno **volta** (`RendererHealth`: o `hideTopFace` respeita a falha; a remontagem dos chunks
  é feita pelo `ClientSea.tick`, nunca dentro do `catch`); o `Tessellator` aberto é fechado no `finally`. O F3 diz
  "desligadas por erro". Uma mudança de nível do mar, de região ou de ligado remonta o terreno uma vez só por tick
  (`RemountRequest`).
* O que não foi verificado em jogo fica marcado **[a verificar]** nos documentos. O harness do ambiente de
  desenvolvimento roda sem GPU e sem o Angelica; o pack real (TEOF) e o servidor do Forbidden podem ser montados aqui
  como em `docs/TESTES-AMBIENTE-REAL.md` (o jar de teste com o harness: tirar o `exclude` do `shadowJar`, construir,
  `git checkout build.gradle.kts`).
* Plataforma (as mesmas restrições do Kunftig): bytecode Java 8 (Jabel: sintaxe moderna, API do Java 8); mixins
  UniMixins; nada de `getMethod` em classes do Bukkit (a API não toca no Bukkit).
