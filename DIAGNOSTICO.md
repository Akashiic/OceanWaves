# DIAGNÓSTICO: o mar do OceanWaves, os barcos, a fragata e o FPS

Síntese dos oito relatórios em `docs/auditoria/` (BOAT, PERF, WAVE, VIS, EXP, KUN, NEO, ENV), com as
correções dos revisores já aplicadas. Nenhum repositório foi alterado. Nenhum número de FPS nem resultado de jogo foi
medido: o que aparece como "medido" foi rodado fora do Minecraft, neste contêiner (Xeon 2,3 GHz, 4 núcleos, sem GPU).

As marcas usadas aqui:

* **[V]** = verificado, ou seja, lido no código ou no bytecode, ou executado fora do jogo;
* **[H]** = hipótese ou dedução;
* **[a verificar]** = só o jogo responde.

Achados que os revisores não conferiram levam a marca **(não revisado)** e mantêm a classificação do autor.


## Resumo

* **Barco reto.** O OceanWaves só desenha: nenhuma entidade lê a onda. `surfaceY` não tem chamadores, e os mixins são
  só de desenho. No TEOF o barco é o `EntityNewBoat` do Et Futurum (`replaceOldBoats=true`), que tira a linha d'água do
  topo dos blocos (plano, 8/9) e não tem grau de liberdade de rotação. Além disso, um cavado de ~0,2 m já o daria por
  submerso e derrubaria o passageiro. Ligar só a altura não basta: é preciso um corpo flutuante de verdade nos dois
  lados.
* **Fragata.** A integração com o mar do OceanWaves já existe no branch `claude/minecraft-ocean-physics-mod-n82b79` do
  Expeditions, com 13 commits à frente da `main`, de ontem e de hoje, sem mesclar e sem implantar. Mas ela tem defeitos de física:
  * falta o momento das pressões horizontais, e o balanço sai 2,1 a 2,8× maior;
  * não há decaimento da pressão com a profundidade;
  * o casco do runtime não é o da ficha (GM +32%);
  * o plugin cai num servidor sem o OceanWaves.

  O Kunftig desenha o navio um tick atrás e sem colchão contra o jitter da rede.
* **FPS.** O mar inteiro é refeito em todo quadro, na thread de desenho, e enviado vértice a vértice pelo
  `Tessellator`, com cada vértice duplicado 3,5×.
  * Medido fora do jogo: 4,3 a 4,8 ms de malha e cor por quadro com distância 8. O envio vem por cima.
  * A meta de 10% dá ~1,85 ms a 60 FPS. Dá para chegar lá, mas exige malha em threads de trabalho e cortes de
    trabalho repetido, e talvez trocar o envio (decisão abaixo).
* **Imersão.** A água "de verdade" continua plana. Câmera, névoa, nado, itens e anzol decidem pelo bloco (Neo States),
  e a folha ondula em volta.
* **Ambiente real.** Lido nos jars do TEOF 6.3 e do servidor. O OceanWaves **não está instalado** em nenhum dos dois
  lados, e o Expeditions do servidor é anterior à fragata. O Kunftig está na API 16 no cliente e na 13 no servidor.

## Decisões do engenheiro-chefe

Respostas às perguntas da seção 5 que cabem a mim. As que dependem de você continuam lá, marcadas.

| # | Pergunta | Decisão |
|---|---|---|
| 1 | Envio fora do `Tessellator` | **Ainda não.** Primeiro o que não fere a regra do CLAUDE.md: malha em threads, cortes baratos, emissão direta sem `setNormal`. Só se a medida sob o Angelica mostrar que o envio sozinho estoura a meta, uma ADR nova com o buffer próprio |
| 2 | Neo States | **Estender o Neo** (API 5 aditiva, provedor de superfície por ponto). O `FluidStage` por célula não cobre as cristas |
| 3 | Dentro do casco | Quem está sobre o convés de um navio **não está na água**. O casco exclui a água acima do piso dele |
| 4 | Balanço do barco do Et Futurum para quem vê de fora | Cada cliente integra o mesmo corpo com o mesmo mar. O y, a guinada e o caturro continuam vindo do piloto. Um barco pequeno converge em ~1 s, então é física, não animação |
| 5 | Espectro dos corpos | **Espectro completo**, com costa e região. O LOD da malha é aproximação de desenho (2,5 cm rms perto da câmera) e não entra na física |
| 7 | Profundidade finita | Fica para depois (item 20): muda o protocolo e o resumo fixado |
| 8 | Radiação, emborcar, alagar | Coeficientes por faixas na frequência natural, sem Cummins. Emborcar e alagar ficam fora desta etapa |
| 9 | Folga da costa | **O mesmo teto nos dois lados**: a configuração é limitada ao teto do cliente |
| 10 | EdgeFade em volta dos navios | **Sim**: as ondas não somem perto de um navio visível |
| — | Integração já feita no Expeditions | Construir **sobre** o branch `n82b79` (avanço direto), corrigindo os defeitos, em vez de refazer |

## Roteiro de medição da linha de base (para você)

Jar da versão atual, sem nenhuma mudança minha: `entregas/oceanwaves-c56f672-linha-de-base.jar` (SHA-256
`a3ed766e…633282`). O mesmo jar vai no cliente e no servidor; o OceanWaves é dos dois lados. O CastleOfStone do servidor só aceita mods
cujo SHA-256 está no `WhitelistMods.yml`: inclua o deste jar.

1. Preparo: vsync desligado e FPS máximo ilimitado (`Opções > Vídeo`), distância 6, sem shaderpack. Spark instalado
   no cliente, se der.
2. **(i) Sem o OceanWaves.** No `Akashic/Spawn`, de frente para o mar aberto, câmera parada a ~3 m da água. Espere 60 s
   e anote por 30 s o FPS do F3 (mínimo e médio). Se houver Spark no cliente, rode o profiler da thread do cliente
   pelos mesmos 30 s e mande o link.
3. **(ii) Com o jar** nos dois lados, no mesmo lugar e na mesma direção: mesmo procedimento. Anote também as linhas
   "[Ondas]" do F3 (malha, envio, Hs).
4. Repita (ii) com `/ondas desligar` (o mod carregado, mas sem desenhar) e com distância 8.
5. Se quiser, uma série com o BSL.
6. Preencha a tabela da seção 3.4, ou mande os números soltos e o link do Spark, e eu preencho.

---

## 1. Contexto técnico confirmado

| Item | Valor | Fonte |
|---|---|---|
| Minecraft, Forge e FML | 1.7.10, Forge 10.13.4.1614, FML 7.99.4.1614, iguais nos dois lados [V] | cliente: `ext/teof/x/bin/version.json` (id `1.7.10-Forge10.13.4.1614-1.7.10`); servidor: `fmlbuild.*` no `server.jar`; `OceanWaves/gradle.properties:24,27` |
| Mixins | UniMixins 0.3.1 (`+unimixins-all-1.7.10-0.3.1.jar`) e Hodgepodge 2.7.219, nos dois lados. Não há GTNHMixins, Celeritas solto, OptiFine nem FastCraft [V] | ENV §1 e ENV-7 |
| OceanWaves: pasta, versão e build | `/home/user/OceanWaves`, HEAD `c56f672`; convenção GTNH (`gtnhsettingsconvention 2.0.33`, RetroFuturaGradle), Jabel (bytecode Java 8), `usesMixins = true`; o `core/` compila com `release 8` [V] | `settings.gradle.kts:19`, `build.gradle.kts:2`, `gradle.properties:47,101`, `core/build.gradle.kts:18` |
| OceanWaves: comandos | build: `./gradlew spotlessApply build -x javadoc`; bancada: `./gradlew :core:bench`; cliente de desenvolvimento: `LIBGL_ALWAYS_SOFTWARE=1 xvfb-run … ./gradlew runClient` [V] | `OceanWaves/CLAUDE.md`; o `:core:bench` foi rodado pela frente PERF com Gradle 9.7.1 e o daemon em Java 25 (`tmp/perf/bench2.log`) |
| JDK 8 para os testes | ausente neste contêiner: o `~/.gradle/jdks/temurin-8-*` citado no CLAUDE.md não existe. A síntese só foi conferida no Java 21: o resumo do "moderado", `3473730547258373915`, é igual ao `SynthesisTest.PINNED` [V] | PERF §3.1, WAVE resumo item 1 |
| Et Futurum Requiem | `etfuturum-2.6.64-GTNH-git.1+77e9997e09`, jar byte a byte igual nos dois lados (`cmp`). `enableNewBoats=true`, `replaceOldBoats=true` (`config/etfuturum/blocksitems.cfg:14,37`, iguais nos dois lados); `adjustedLiquidPhysics=true`, `liquidItemFloat=true` [V] | BOAT-1 e a revisão dele; ENV §3.3 |
| Angelica | `angelica-2.2.43.jar`, só no cliente, com o Celeritas embutido. `shaders.properties`: `enableShaders=true` e `shaderPack=` vazio, portanto **nenhum shaderpack ativo**. BSL 8.2.09 e Complementary Unbound r5.9.3 estão em `shaderpacks/` sem seleção. `options.txt`: `renderDistance:6`, `maxFps:60`, `enableVsync:true`, `mipmapLevels:0` [V] | ENV §3.1, §3.2 e ENV-6 |
| Neo States | `NeoStates-0.1.0-alpha.10.jar` com SHA-256 igual no pack e no servidor; coerente com o HEAD `288b929` do repositório (classes, mixins, constantes; sem rebuild). `API_VERSION = 4`. `neostates.cfg` igual nos dois lados: `modernWaterPhysics=true`, `floatingItems=true`, `modernWaterFog=true` [V] | NEO-9, `Neo-States/.../api/NeoStates.java:26`, ENV §3.4 |
| Kunftig | cliente `kunftig-2bb00b3` com **API 16**; servidor `kunftig-0.1.0-dev-8349814` com **API 13**. O campo é privado: conferido com `javap -p -constants` ou pelo `bipush` de `apiVersion()`. O fio de exibições (operações 0 a 10, canais `display`, `display2` e `display3`) tem bytecode idêntico nos dois. O servidor não tem `posture/*`, `setPosture` nem `setZoom` [V] | ENV-2, KUN-11 e as revisões |
| Crucible e Java | servidor Crucible `Implementation-Version: 1.7.10-staging-67b53034`, Java 21 (`java9args.txt` com `-Dcrucible.weAreJava9=true`); a versão do processo não foi observada. Cliente em Java 8 (`bin/1.7.10.json`, `javaVersion.majorVersion = 8`, `jre-legacy`) [V] | ENV §2 e ENV-9 |
| Expeditions | servidor: `plugins/Expeditions-0.1.0.jar` (12/nov), **anterior à fragata**: o `plugin.yml` só tem `<chalupa\|brigue\|galeao>` e o `navios.yml` não tem fragata. Ele usa só as APIs 8 a 13 do Kunftig. Checkout local: `74903e3` no branch `claude/happy-ptolemy-s5hpvz`. A `main` remota é `381cdf7`. A integração com o OceanWaves está no branch remoto `claude/minecraft-ocean-physics-mod-n82b79` (`b50c9cf`), que não foi mesclado e está 13 commits à frente da `main`; `381cdf7` e `74903e3` são ancestrais dele, então a mesclagem seria um avanço direto [V] | ENV-3, EXP-5, WAVE-4 e as revisões |
| futura3d | cliente `90c7159`, servidor `0.1.0-dev-1c242cb`: versões diferentes. Traz `RenderBoatMixin` (`@Inject HEAD cancellable` em `RenderBoat.func_76986_a`) [V] | ENV-8, BOAT-8 |
| **OceanWaves instalado?** | **Não, em nenhum dos dois lados.** Nenhum jar (217 no cliente e 227 no servidor, contados recursivamente) nem config contém `oceanwaves`. O mar que o Expeditions implantado usa é o `WaveField` do próprio plugin [V] | ENV-1 e a revisão dele |
| Lacunas do inventário | em `server/x` faltam o `server.properties` (`allow-flight`, `level-name`) e as pastas de mundo. Por isso não dá para conferir o nível do mar do `Akashic/Spawn` nem se ele tem `@` no `oceanwaves.cfg` (decide se o VIS-5 acontece) | ENV-10 |

---

## 2. Causas prováveis por problema

### (a) O barco fica reto e preso ao topo do bloco

1. **O barco do TEOF é o `EntityNewBoat` do Et Futurum, não o vanilla** [V].
   * `ServerEventHandler.entityAdded` retorna logo se `isRemote` (offsets 4 a 10). Nos offsets 131 a 208, se `getClass() == EntityBoat.class` (classe exata), cria um `EntityNewBoat` (reconferido no jar: `tmp/refut1/efr/.../ServerEventHandler.class`).
   * A troca só acontece no **servidor**. O cliente recebe o `EntityNewBoat` pelo pacote de spawn, então o mixin tem de existir nos dois lados.
   * Ficam vanilla as subclasses: IC2 (`EntityIC2Boat` e as três que derivam dele), Thaumic Horizons (`EntityBoatGreatwood`, `EntityBoatThaumium`) e Adventure Backpack (`EntityInflatableBoat`).
   * O `EntityNewBoatWithChest` estende o `EntityNewBoat` e fica coberto pelo mesmo mixin. (BOAT-1)
2. **A linha d'água vem só dos blocos e é um escalar plano por tick** [V].
   * **Vanilla** (`xi.h()V`, offsets 64 a 215 e 814 a 880):
     * 5 fatias do AABB, cada uma deslocada −0,125;
     * `World.isAABBInMaterial` (`ahb.b(azt,awt)`) é um OU sobre a pegada inteira;
     * o topo da água é y+1 para meta 0 e meta ≥ 8, e y+1−meta/8 para meta de 1 a 7, ou seja, **1/9 acima do repouso desenhado** (`OceanColumns.SOURCE_TOP = 8/9`, `OceanColumns.java:16`).
   * **Et Futurum**:
     * `checkInWater` varre só a camada de `minY` e **sai no primeiro bloco** cujo topo passa de `minY`, então `waterLevel` é o máximo só das colunas visitadas até ali;
     * a altura é 8/9 com ar em cima, ou 1 com água em cima;
     * `updateMotion`: `motionY += −0,04`; se d2 > 0, soma `d2·0,04/0,65` e multiplica por 0,75. O equilíbrio é d2 = 0,65, cerca de 0,366 bloco acima do fundo do AABB.
   * As duas leis são uma lei de caixa aceitável. O defeito está na **entrada**: topo de bloco em degraus, num casco sem rotação. (BOAT-2)
3. **Não há grau de liberdade de rotação** [V].
   * O vanilla zera `rotationPitch` no offset 1518. Esse trecho só roda em quem simula: o cliente com barco vazio interpola o pitch da rede, e o servidor sempre manda 0.
   * No `EntityNewBoat`, `rotationPitch` só é escrito em `tickLerp` e nenhum código o calcula.
   * `RenderBoat.doRender` (`bne.a(xi,DDDFF)V`) e `NewBoatRenderer.setupRotation` (offsets 0 a 78) aplicam só a guinada (`glRotatef` ordinal 0) e o tremor de dano (ordinal 1).
   * `renderMultipass` também chama `setupRotation`, e o `ChestBoatRenderer` o herda.
   * Os pivôs são diferentes: o Et Futurum translada +0,375 (`setupTranslation`) e o vanilla translada no y cru. (BOAT-3)
4. **Armadilha do cavado** [V no bytecode; o efeito em jogo é [a verificar]].
   * `getUnderwaterStatus` compara o **topo do AABB** (`maxY = posY + 0,5625`) com a água da camada de `maxY`. No equilíbrio o topo fica cerca de 0,196 bloco acima da linha d'água. Logo **um cavado de ~0,2 m abaixo do repouso basta** para `UNDER_WATER`; não é preciso mais de 1 m.
   * Em `UNDER_WATER`: d2 = 0,01 e momentum 0,45. O `motionY` converge para cerca de −0,118 bloco/tick (−2,4 m/s), sem força que traga o barco de volta. O contador `outOfControlTicks` só zera fora de `UNDER_*`, então a expulsão aos 60 ticks (`removePassengers`, **só no servidor**: `isRemote` no offset 61) é **praticamente certa a partir do primeiro tick submerso**.
   * Na crista, o estado vira `IN_AIR`. Ao voltar para a água, `updateMotion` (offsets 16 a 106) **teletransporta** o barco para `getWaterLevelAbove() − altura + 0,101`, que é leitura de bloco, e zera `motionY`. Isso desfaz o seguimento da onda. (BOAT-4)
5. **O OceanWaves não alimenta nenhuma entidade** [V].
   * Fora dos testes, `OceanField.eulerian` só é chamado em `api/OceanWaves.java:152` (`surfaceY`, sem nenhum chamador em nenhum dos cinco repositórios) e em `server/OceanCommand.java:230` (`/ondas aqui`).
   * `mixins.oceanwaves.json` tem `mixins` e `server` vazios e quatro mixins de cliente, todos de desenho ou tempo.
   * O plano existe (`docs/ARQUITETURA.md:155`), mas não foi feito. (BOAT-6)
6. **Gravidade da entidade diferente** [V]. Os dois barcos usam −0,04 bloco/tick², ou seja 16 m/s², e a dispersão usa 9,81. O equilíbrio não depende de g, mas os períodos naturais dependem. (BOAT-7, não revisado)
7. **Autoridade de rede** [V] (BOAT-5, não revisado).
   * Et Futurum: o cliente do piloto manda `BoatMoveMessage(x,y,z,yaw,pitch)` a cada tick e o servidor aceita (anti-trapaça de 100). Barcos vazios são simulados no servidor e interpolados em 5 passos.
   * Vanilla: as duas pontas simulam, e o cliente do piloto ignora o servidor até 1 bloco².
   * O pitch já viaja na rede; o balanço não tem campo.
8. Conflitos a evitar [V] (BOAT-8, não revisado):
   * o Angelica faz `@Redirect` de `Render.doRender` em `RenderManager.func_147939_a`;
   * o futura3d cancela `RenderBoat.doRender` no HEAD;
   * o FoamFix (só no servidor) altera por ASM `setIsBoatEmpty` e `setPositionAndRotation2`: ali só `@Inject`;
   * o IC2 sobrescreve o `onUpdate` inteiro.

### (b) Queda de FPS

O mar inteiro é refeito em todo quadro, na thread de desenho, e enviado vértice a vértice. Os números com distância
8, mar moderado e sem shaderpack estão na seção 3.

1. **Malha, cor e emissão do zero em todo quadro** [V código; tempo medido no `PerfBench`] (PERF-1, crítico).
   * `OceanRenderer.java:251-270`: `beginFrame` e `mesh` de todos os chunks visíveis, sem cache entre quadros e sem threads (um grep por `Thread`, `Executor` e `ForkJoin` fora de `dev/` não acha nada).
   * Custo medido: 4,3 a 4,8 ms em r 8 (78 chunks, 15.360 quads; o harness em Java 8 deu 4,7 a 6,6 ms) e 6,35 ms em r 12. O r 12 só foi medido no bench.
   * O custo por quadro é constante; o que cresce com o FPS é a CPU por segundo, que é a fração que a meta de 10% mede.
   * Um trabalhador precisaria de instantâneo de mais coisas do que o PERF diz: `Column.color` e `Column.light`, e também `Column.open` (reescrito no lugar por `OceanChunks.rebuild:157-169`), `Column.shore`, `neighbours[]`, `edge`, os campos do `shader` e o `mesher`.
2. **Envio pelo `Tessellator` com duplicação de 3,5×** [V] (PERF-2; severidade **alta**, rebaixada de crítica pela revisão).
   * Cada célula sai como quad de 4 vértices: 17.550 vértices de grade viram 61.440 vértices, com 3 a 4 chamadas cada (`OceanRenderer.java:321-345`, `OceanMesher.java:113-121`).
   * `setNormal` é chamado sempre (`:337`), mesmo quando a luz já vai na cor (`own = shading && !pack`, `SurfaceShader.java:84`).
   * **Corrigido pela revisão**: os hooks do Angelica no `addVertex` são um teste de campo na thread de desenho. `addElementState` sai se `celeritasMeshing` for falso; essa flag só é ligada nas threads de montagem de chunk. `blendVertexColor` devolve a cor intacta sem `liquidBlender`.
   * O "9 a 11 ms no Angelica" só aparece em prosa (`TESTES-AMBIENTE-REAL.md:62`) e coincide com a coluna "GL" do harness (`tess.draw` com llvmpipe). Pode ser confusão entre colunas [a verificar]. O que se apoia em medida é o laço de `addVertex` no harness (3,2 a 5,6 ms, sem Angelica) e o quadro do TEOF real de 64 a 80 ms contra 58 ms.
   * O Angelica já sobe tudo por buffer de streaming e converte quads em triângulos por índices.
3. **Costas refeitas sem orçamento e em cascata; atenuação pedida vértice a vértice em alto-mar** [V, medido] (PERF-3).
   * `OceanRenderer.java:231-235` refaz todas as costas sujas visíveis no mesmo quadro, a 0,23 a 0,48 ms cada (medido: 0,32 ms por `ShoreField.build`).
   * Cada chunk lido marca os 5×5 vizinhos (`OceanChunks.java:188-197`). O teto é de 6 a 12 ms por chunk que chega; só contam os vizinhos que existem, têm mar e estão visíveis.
   * `rebuildShore` faz `map.get` com boxing por coluna (`:277`).
   * `ShoreField` não sobrescreve `uniform()`. O padrão está em `Attenuation.java:21-23`, e não nas linhas 79-81 que o achado cita.
   * Resultado: todo chunk passa pelo caminho não uniforme de `GridEvaluator.java:379-388`, custando +0,4 a 0,6 ms por quadro (medido).
4. **Releitura de um chunk inteiro e da luz de dois em todo quadro**, com cerca de 2.300 eventos `GetWaterColor` (que o Neo ouve) por quadro: estimativa de 0,3 a 0,8 ms (`OceanRenderer.java:237-247`, `OceanChunks.java:128-185`). [H] (PERF-4, não revisado)
5. **Com shaderpack** [V código; magnitude em GPU a verificar] (PERF-5).
   * O ADR-0004:42-46 mediu cerca de 160 a 230 ms por 18 mil quads, mas em GL por software com a CPU disputada.
   * `minSpacing` é o mesmo com e sem shaderpack (`OceanRenderer.java:249`).
   * `sombraDaAgua = true` é o padrão (`OceanConfig.java:65`) e redesenha 213 chunks sem recorte. Com células ≥ 4 isso dá cerca de 3.408 quads, uns 22% da passagem principal, e não o dobro.
   * A passagem de sombra repete a espiral, o `arrived`, a ordenação, o rodízio de 3 chunks, o `beginFrame` e o `shader.frame`. A releitura e as costas pesadas **não** se repetem, porque os flags já foram zerados.
   * O TEOF vem sem shaderpack.
6. Também lidos, de impacto menor:
   * buffer intermediário de 2,4 MB por quadro (PERF-6);
   * 15 a 50 KB de lixo por quadro, contra a regra do CLAUDE.md (PERF-7);
   * `glGet*` por quadro (PERF-8);
   * borda avaliada em dobro: +41% de somas, cerca de 0,7 ms (PERF-9);
   * cor por vértice na CPU: 1,1 a 1,3 ms (PERF-10);
   * `loadRenderers` repetido ao ligar ou trocar de região (PERF-11);
   * `frame` nunca lido (PERF-12, [a verificar]).
   Nenhum desses foi revisado.
7. Não é o problema [V]: trigonometria e `StrictMath`. O laço interno da soma está em 1,6 ns por vértice×componente, perto do limite escalar, e reescrevê-lo piorou (`LoopBench`).

### (c) Dinâmica e coerência das ondas (física × desenho)

A matemática está certa [V]:

* JONSWAP com o teto de Pierson-Moskowitz;
* Donelan-Banner pela inversa exata;
* Gerstner com L = ΣQka < 1;
* correção do nível médio e inversa de Newton;
* o servidor e o cliente recebem o mesmo `SeaState`, a mesma semente, o mesmo nível e a mesma regra de colunas (`net/MessageSeaState.java:198-216`).

O que **não** é coerente:

1. **`OceanWaves.surfaceY` não é a superfície desenhada** [V] (WAVE-1).
   * Usa um `OceanField` de mar aberto (`Attenuation.OPEN_SEA`), sem `ShoreField` e sem `SeaRegion`, e aloca `OceanField` e `WaveSample` a cada chamada (`api/OceanWaves.java:146-153`; 23 a 30 µs, `TESTES-AMBIENTE-REAL.md:40`).
   * A omissão está documentada no javadoc (`:142-145`): é falha de contrato, não de cálculo.
   * Perto da costa, o teto do erro é Σa = 2,5 m no moderado; o rms é da ordem de Hs/4, cerca de 0,29 m.
   * Região: só pesa com região limitada. O padrão é "tudo" (`OceanConfig.java:46`).
2. **EdgeFade** [V] (WAVE-5, não revisado; reforçado por VIS-11).
   * O cliente apaga as ondas entre 88 e 120 m com distância 8, e entre **56 e 88 m com o `renderDistance:6` do TEOF** (`EdgeFade.java:22-31`). A física não faz isso.
   * Erro rms: 0,29 m no moderado e 0,75 m na tempestade.
   * Uma fragata vista a 60 a 100 m caturra num mar que aparece liso.
3. **LOD espectral com `espacamento = 1`** [V] (WAVE-6 e BOAT-9, não revisados).
   * Corta λ < 2,5 m e atenua até 5 m (`SpectralLod.java:269-278`).
   * Erro: 2,5 a 3 cm rms perto da câmera e 5 a 7 cm a 100 a 128 m.
   * Num casco o efeito se cancela; num jogador ou item, não.
4. **Folga da costa diferente nos dois lados** [V] (WAVE-7, não revisado).
   * O cliente limita a 32 (`OceanChunks.java:266-268`); o servidor não tem limite (`api/OceanWaves.java:134-140`).
   * Com escala ≥ 0,75 e altura ×2, o raio de influência chega a 46 e 62 blocos: abre fresta e as duas atenuações divergem.
5. **Falta a cinemática para um corpo** [V] (WAVE-2).
   * `WaveSample.vx/vz` é a velocidade da partícula de Gerstner (`OceanField.java:62-65`): 0,67 a 0,77 da orbital linear; no moderado, 0,32 contra 0,47 m/s.
   * Não há decaimento com a profundidade, aceleração, pressão dinâmica nem ∂ζ/∂t euleriano.
   * O `vy` lagrangiano difere de ∂ζ/∂t em 6 a 8% rms (WAVE-10, não revisado).
   * Ressalva da revisão: com Wheeler, p_din/ρg na superfície vale η(x₀) = ζ − c, com c = `meanLevel` (`OceanField.java:74,89`). A referência hidrostática tem de ser declarada, senão o corpo carrega um viés de c: 1,5 cm no moderado e 4,6 cm na tempestade.
6. **Empuxo de coluna contra η superestima a força de onda (efeito Smith)** [V] (WAVE-3, severidade **média** pela revisão).
   * `FloatingBody.java:91-99` integra ρg(η − y) sem e^{kz}.
   * Com as 254 colunas submersas reais (profundidade média 1,68 m) e a soma por fase, a força verdadeira dividida pela da coluna dá:

     | Estado | Arfagem | Caturro |
     |---|---|---|
     | brisa | 0,52 (coluna 1,9× maior) | — |
     | moderado | 0,76 (1,32×) | 0,74 |
     | tempestade | 0,86 (1,16×) | 0,82 (1,21×) |
     | marulho | 0,91 (1,09×) | — |

   * O "1,3 a 1,7×" e o "3× nas ondas curtas" do autor não se sustentam (ver Descartados).
7. **Dispersão sempre de água funda** [V] (WAVE-8, não revisado).
   * O oceano tem 15 a 25 blocos de fundo. A componente mais longa anda 1,2 a 1,47× mais rápido do que o fundo permite.
   * O mesmo erro existe nos dois lados, então não há desacordo entre eles.
   * Para física abaixo da superfície, o decaimento certo é cosh.
8. **Relógio do cliente** [V código] (WAVE-9, VIS-17 e KUN-12, não revisados).
   * `OceanClock.observe` salta, inclusive para trás, quando o erro passa de 40 ticks: depois de travadas maiores que 2 s, ou depois de pausa no jogo solo (`OceanClock.java:121-125, 39-41, 76-84`).
   * Um corpo integrado no cliente com dt fixo de 0,05 s se desencontra de um mar a 15 TPS.
   * O total do mundo no cliente é ressincronizado a cada 20 ticks (KUN-12, [H]).
9. Menores [V]:
   * `waves()` devolve o `WaveSet` em cache, com vetores públicos mutáveis (WAVE-11);
   * o preset "tempestade" promete 80 componentes, mas `OceanConfig.seaState()` força 64 (`OceanConfig.java:264`; WAVE-12). Isso também afeta a certificação do branch do Expeditions (EXP-15).

### (d) Imersão: entidades e câmera ainda decidem pelo plano 8/9

1. **Câmera e olhos** [V] (VIS-1, crítico).
   * O Neo decide "molhado" pela superfície da célula, y + 8/9:
     * `ModernWater.submerged` usa `floor(py)`;
     * `MixinActiveRenderInfo_Water.java:26-47` é por quadro, com `partialTicks`;
     * `UnderwaterFog.java:48-49` usa `isInsideOfMaterial` com a posição do tick, para a visão adaptativa;
     * `UnderwaterFog.java:101-123` lê o bloco do ponto de vista.
   * Isso dá **duas decisões separadas**, uma da câmera por quadro e outra dos olhos por tick, e a correção precisa cobrir as duas.
   * O `isEyeInWater` do Iris dentro do Angelica (`CommonUniforms.isEyeInWater`) usa `isInsideOfMaterial(water)`, **só em primeira pessoa**.
   * O OceanWaves não toca em entidade nem em câmera.
   * Sintoma atual: o estado fica **constante** enquanto a folha sobe e desce em volta da câmera.
2. **Corpo, itens, orbes e anzol** [V código; o efeito em jogo é [H]] (VIS-2).
   * `ModernWater.update` é chamado de `MixinEntity_ModernWater` e de `MixinEntityItem_Float` (que flutua se `waterHeight > 0,1`). Os orbes passam por `submerged`.
   * O anzol do Kunftig usa `EntityFishHookMixin.java:273` → `NeoStatesBridge.waterSurface` → `NeoStates.surfaceY`, que é por célula. Na crista devolve null e o anzol cai 0,03 por tick.
   * A chuva do Dynamic Surroundings usa `World.func_72874_g`, altura por bloco.
3. **Cristas acima do topo do bloco são invisíveis ao Neo** [V] (NEO-2).
   * `FluidPipeline.height` devolve 0 antes de chamar qualquer estágio quando a célula é `EMPTY` (`FluidPipeline.java:44-47`).
   * O contrato do `FluidStage` diz que a célula nunca é vazia e que o resultado fica entre 0 e 1 (`FluidStage.java:17-21`).
   * O limiar real não é o repouso: é o topo do bloco-fonte, y + 8/9. Para quem tem os pés em [y+8/9, y+1), qualquer ζ > −0,111 já fica cego.
   * O caso inverso também existe: no cavado o Neo conta "dentro" com a malha abaixo.
4. **O encaixe de ondas do Neo é por célula inteira** [V] (NEO-1).
   * A assinatura é `FluidStage.height(IBlockAccess,int,int,int,int,HeightKind,float)`: sem tempo, sem posição contínua, sem velocidade.
   * `HeightKind.RENDER` não tem chamador. Nenhum estágio está registrado em nenhum jar do pack.
   * O ADR-0003 prevê só `flow`, `cornerHeight` e `register` com prioridade, também por inteiros; o código implementa só `height` e `deterministic`.
5. **`waterHeight` não é a superfície** [V] (ENV-5, proposta corrigida pela revisão). `neostates$waterHeight` é a **profundidade de submersão** da caixa, uma saída recalculada a cada `ModernWater.update` (`ModernWater.java:131`). Consomem esse valor o pulo em água rasa e a flutuação de itens. O encaixe real é `ModernWater.surface()`/`FluidPipeline.height(INTERACTION)`.
6. **O convés do navio num cavado** [H] (KUN-6, não revisado). A água de blocos não acompanha o casco, e o vanilla põe o jogador "na água" em cima do convés. Nada em `display/` nem em `client/display/` trata `inWater`.
7. **Sem velocidade orbital no Neo** [V] (NEO-5, não revisado).
   * O empurrão é `getFlowVector`, que é zero no mar aberto.
   * `EntityXPOrb` tem `handleWaterMovement` próprio, que o Neo não desvia.

### (e) Visual

Defeitos de comportamento, todos [V] no código:

* **Falha do renderizador apaga a superfície de todo o oceano** (VIS-4).
  * `failed=true` (`OceanRenderer.java:137-140`) não chega a `ClientSea.hideTopFace` (`:98-100`), e a face de cima das colunas de mar aberto continua escondida até trocar de mundo. As faces laterais e de baixo continuam.
  * Só `RuntimeException` é capturada; um `Error` de reflexão sobe pelo passe.
  * O F3 continua mostrando "[Ondas]" ativo.
* **Nível do mar medido não invalida o cliente** (VIS-5; é uma corrida).
  * `ClientSea.tick` grava `seaLevel` sem `version++` nem `loadRenderers` (`:120-130`).
  * Os chunks lidos com 63 ficam com `any=false`, saem de `visible` e nunca mais são relidos.
  * Resultado: água vanilla plana nos chunks já montados, e buracos nos remontados.
  * Afeta quem estava logado quando o servidor mediu (`ServerSea.java:401-411`); quem entra depois recebe o nível certo direto.
* **Buracos transitórios enquanto o cache lê** (VIS-6). Visto na captura `docs/img/real/teof-mp-moderado.png`.

Aparência:

* **Vista de baixo** (VIS-3).
  * `ndv = max(1e-3, l·v)` (`SurfaceShader.java:125`) e a normal nunca é virada. Na maior parte da folha dá Fresnel ≈ 0,995 e alfa ≈ 0,93.
  * Nas encostas inclinadas para a câmera com visada rasante (o relevo dobra a inclinação), `l·v` fica positivo e o Fresnel vira o de "visto de cima".
  * Não há janela de Snell (48,6°) nem reflexão interna total.
* **Textura** NEAREST sem mipmap (`TextureUtil.uploadTexture` com blur/mipmap falsos; o pack usa `mipmapLevels:0`) (VIS-7).
* **Luz própria** ignora o lightmap: gamma, relâmpago, visão noturna, cor de tocha (VIS-8).
* **Clima** desacoplado e troca de estado instantânea (VIS-9).
* **Cor** sem profundidade: `Column.depth` não é lido pelo shader (VIS-10).
* **Ondas morrem** a 56 a 88 blocos com `renderDistance:6` (VIS-11).
* **Faltam efeitos**: espuma de costa, espuma de casco, esteira, sons, partículas (VIS-12).
* **Navio desenhado depois da folha**: o Kunftig desenha em `RenderWorldLastEvent`, então a folha corta o casco submerso e atravessa o interior (VIS-13, [H] com shaderpack).
* **Shaderpack**: partes não vistas e linhas tracejadas sem causa conhecida (VIS-14).
* **Harness** sem câmera na onda, sem noite, chuva ou entidades (VIS-15).
* **Água vanilla plana** convivendo com a folha (VIS-16).
* **Especular facetado** (VIS-18).
* **Junção com o Mirai** só por altura (VIS-19).

Revisados: VIS-1 a VIS-5. Os demais não foram revisados.

### (f) Fragata, Expeditions e Kunftig

1. **O que roda e onde** [V] (EXP-5, WAVE-4 corrigido).
   * A integração com o OceanWaves **existe** em `origin/claude/minecraft-ocean-physics-mod-n82b79` (`b50c9cf`). Ela contém:
     * `OceanWavesSea` e `HullSea`: `OceanWaves.time`, `restLevel`, `dimensionOf`, `HeightPatch`, `ShoreField`;
     * `ShipMotion` e `WaveForces`;
     * `docs/DERIVA.md` e `libs/oceanwaves-8b71472.jar`.
   * Ela não está no clone local, não foi mesclada e não está no servidor.
   * O branch declara `KUNFTIG_API = 9` como mínimo e condiciona as APIs 14 a 16 a `apiVersion() >=` (`ExpeditionsPlugin.java:16,86,95,104`). Ele roda com API 13 sem leme, posturas e zoom.
2. **O branch derruba o plugin num servidor sem o OceanWaves** [V, sonda reexecutada] (EXP-1).
   * `OceanWavesSea` chama `HullSea.shore(Attenuation)` passando um `ShoreField`. Para decidir a atribuição, o verificador carrega `Attenuation` **ao ligar a classe**, antes do corpo de `present()`. Por isso o `catch (…LinkageError)` de `OceanWavesSea.java:64-71` nunca pega o erro.
   * A primeira chamada é no inicializador de campo do `ExpeditionsCommand` (`:191`, dentro de `onEnable`, sem try/catch). A segunda é em `:2511`.
   * A sonda só reproduz com as classes do core do Expeditions no classpath. Com `-Xverify:none`, `present()` devolve falso, o que confirma que a causa é o verificador.
   * É bloqueador de mesclagem e implantação; não é defeito vivo em produção.
3. **O balanço é excitado 2,1 a 2,8 vezes demais** (mar irregular de través; no RAO, 2,4 a 2,9) [V, reexecutado] (EXP-2).
   * Falta o momento, em torno do CG, da força horizontal das pressões. O torque só das pressões verticais é Δg·BM·declive; com a força horizontal em B dá Δg·GM·declive. Sem ela, o balanço estático fica em BM/GM ≈ 2,9 × o declive.
   * RAO na ressonância: 11,18 (plugin) contra 4,62. Cerca de 95% da queda vem do momento horizontal, e não do e^{kz}.
   * No mar real de través: 4,18° contra 1,65° rms no fresco e 6,99° contra 2,90° na tempestade. Esses valores vêm do modelo C, que é Froude-Krylov sem difração nem radiação.
   * O `ShipMotion` do branch aplica a força horizontal no CG, sem momento (`ShipMotion.java:180-182`).
4. **Efeito Smith no casco** [V] (EXP-3). Contra um modelo de Froude-Krylov com decaimento:
   * arfagem 1,15 a 1,34 vez maior;
   * caturro 1,2 a 1,35 vez maior;
   * pico do RAO de arfagem de través: 1,52 contra 1,04 no mesmo λ = 28 m.
   O `PressurePatch` só vertical não corrige o balanço, que é o erro maior.
5. **O casco do runtime não é o da ficha** [V, recalculado] (EXP-4).
   * 36×7 colunas dão GM 1,085 m; a ficha, com 56×12, diz 0,821 m: **GM +32%**. O BM sobe só 9% (2,884 para 3,146). O KB é igual.
   * Período de balanço: 10,64 a 10,8 s de verdade; `Loading.rollPeriod()` diz 12,23 s; o yml diz 10,94 s porque dois erros se cancelam.
   * A restauração vem das colunas. O GM do yml só entra no amortecimento (`rollC`, `pitchC`), em `rollPeriod()` e no `/exp ficha`.
   * Nenhum teste compara GM, GML ou Awp com as colunas gravadas.
6. Modelo grosseiro [V código] (EXP-6, EXP-7, EXP-9, não revisados):
   * massa adicional e amortecimento fixos (2,0/1,25/1,8 e 30/10/35% do crítico), sobre a velocidade **absoluta**;
   * inércias de regra: kxx = 0,38·B, kzz = 0,25·L; KG a 46% do pontal; LCG = LCB;
   * colunas sem tumblehome e sem os lançamentos.
   Os períodos são plausíveis: arfagem 3,85 s, caturro 4,43 s, balanço 10,8 s.
7. **Custo** [V medido] (EXP-8, não revisado). O vertical custa 0,92 ms por tick por navio:
   * duas `HeightPatch` de 54×54 m a cada 2 ticks: 1,21 ms;
   * 504 colunas com Newton frio: 80 µs por subpasso.
   O `DERIVA.md` diz "4 a 6 µs por passo", o que não bate.
8. **Mistura de modelos** [V] (EXP-10, não revisado): o vertical usa Gerstner euleriano; o plano usa Airy linear a·ω, que não é a velocidade da malha.
9. **Tempo do mar do checkout local** [V] (EXP-12 e KUN-3): um contador próprio (`ExpeditionsCommand.java:89,95`), contra a regra. O branch corrige.
10. **Kunftig: sem horário nem extrapolação** [V executado] (KUN-1).
    * O `MOVE` leva só posição, ângulos e deslize (`DisplayWire.java:226-242`). O cliente faz uma rampa linear e para (`DisplayMotion.java:77-97`).
    * Atraso estrutural: **glide + 1 ticks**, ou seja 3 ticks no ritmo do Expeditions (cadência 2, deslize 2). O desenho anda um tick atrás do estado.
    * Reproduzido: 0,234 m de desencontro com arfagem de 1,2 m e período de 4,8 s. Prevendo g+1 ticks, cai para 0,003 m.
    * **Correção da revisão**: o mar sincronizado é desenhado em `OceanClock.secondsAt(System.nanoTime())`, e não em `(totalWorldTime+parcial)/20`, que é só a reserva (`ClientSea.java:89-94`). Os 150 ms e os 0,234 m são relativos ao relógio de ticks do cliente; contra o `OceanClock` o valor pode variar ±1 tick. A constante da previsão precisa de calibração em jogo.
    * **Divergência**: o EXP (EXP-13, não revisado) diz que a física do branch, adiantada 2 ticks, já casa a fase. Pelo KUN-1 (revisado) falta 1 tick: é preciso prever g+1 = 3 ticks.
11. **Sem jitter buffer** [V executado] (KUN-2).
    * Um `MOVE` atrasado faz o navio parar e depois correr. Pico de 0,118 m/tick, no modelo sintético com 1/3 das mensagens atrasadas 1 tick; não é taxa medida.
    * A folga é zero (glide = cadência = 2). Com glide 3 o pico cai para 0,053; com glide 4, para 0,035.
12. **Kunftig sem mar nem horário do mundo nas exibições** [V] (KUN-5).
    * Nenhuma referência a `oceanwaves`.
    * `DisplayClient.clock` conta ticks de cliente não pausados (`:134, 171-174, 376-380`). Mas o Kunftig já lê `getTotalWorldTime()` em `CameraClient.java:148` e `BladeHud.java:90`, então expor o horário não é capacidade nova.
    * `FloatingBody.java` usa `Math.*` em 17 ocorrências, em 8 linhas, e nenhum `StrictMath`; `WaveField`, `Frame` e `Loading` também usam `Math.*`. É risco, não violação de regra: a regra de `StrictMath` vale para a síntese.
    * O core do Expeditions é Java 21 com `record` (`HullColumns`, `Loading`): um módulo compartilhado em Java 8 exige reescrevê-los.
13. **O que já funciona** [V executado] (KUN-8 e KUN-9, não revisados).
    * Com roll de 35°, pitch de 14°, arfagem de 1,9 m e períodos até 1,4 s, o `DeckClient` e o `DeckWalker` levam o jogador sem cair (0 ticks fora do piso), com 0,3 a 28 mm de diferença entre jogador e casco.
    * Euler contra slerp: no máximo 0,07°.
    * A pose a 10 Hz custa pouco: 0,52 ms por tick para 20 navios e 100 jogadores (KUN-10).
14. Riscos [H] (KUN-7, não revisado): jogadores remotos no convés podem se desencaixar em mar forte, pela folga de `REMOTE_REACH` de 0,4 a 0,8 bloco.

### (g) Neo States

* **O Neo não tem física de barco** [V] (NEO-3).
  * O `EntityNewBoat` lê `World.getBlock` e `Material.water` em `checkInWater`, `getWaterLevelAbove` e `getUnderwaterStatus`. Os três precisam de gancho, não só o `waterLevel`.
  * O vanilla lê `isAABBInMaterial`, onde **o Neo já tem dois `@WrapOperation`** de água hospedada com `@Share("hosted")` (`MixinWorld_Water.java:124-139`). Um desvio novo tem de conviver com eles, e o `VanillaFingerprintTest:94-131` trava as contagens.
* **Registro e condições** [V] (NEO-4, não revisado).
  * `FluidPipeline.register` lança exceção depois do `freeze()` do `postInit` do Neo: é preciso registrar no `init`.
  * `modernWaterPhysics` desliga **todos** os desvios do Neo, e vale o valor do servidor.
  * O cliente precisa do relógio do mar, não do total do mundo.
* **Três usuários da API de célula** [V] (NEO-6, não revisado): Kunftig, Player Backport alpha.30 e ThePatcher beta.53. Uma API nova tem de ser aditiva (`surfaceAt`).
* **Convivência** [V] (NEO-7, NEO-8, não revisados).
  * O único alvo de mixin comum é `BlockLiquid.shouldSideBeRendered`, com resultado compatível em qualquer ordem.
  * A textura e a cor convivem: `GrayWater.icon(1)` por reflexão e `colorMultiplier` pelo evento do Neo.
* **Onde não pôr gancho** [V] (NEO §3.4):
  * um segundo wrap em `Entity.handleWaterMovement` (o do Neo não chama `original`);
  * constantes novas em `moveEntityWithHeading`;
  * as duas comparações `minY < 0.0` do BelowCore.
* Menores:
  * `new double[3]` por chamada em `ModernWater.update` (NEO-10);
  * a câmera numa crista não vê névoa (NEO-11, coberto pelo VIS-1);
  * o módulo `neostates` do ThePatcher depende de nomes internos do Neo (NEO-12, [H]).

---

## 3. Linha de base de FPS

**Nenhum FPS foi medido em jogo neste ambiente**: não há GPU, e o OceanWaves não está no pack. Abaixo, só o que foi
medido fora do jogo.

### 3.1 `./gradlew :core:bench`

Rodado pela frente PERF em Java 25, preset "fresco", 64 componentes (`tmp/perf/bench2.log`). A cena de 441 chunks não
recorta pelo frustum (pior caso) e mede só a avaliação.

| Rodada | grade 17×17 | 33×33 | consulta fria / quente | cena de 441 chunks (28.505 vértices) |
|---|---|---|---|---|
| 1 (JIT frio) | 88,9 µs | 241,7 µs | 5,44 / 3,66 µs | 168,7 ms |
| 2 | 56,1 µs | 195,5 µs | 5,27 / 3,62 µs | 13,1 ms |
| 3 | 54,8 µs | 191,5 µs | 5,18 / 3,52 µs | 18,6 ms |

### 3.2 `PerfBench`

Reproduz o `OceanRenderer.draw` sem o Minecraft, em Java 21 (`tmp/perf/bench/PerfBench.java`; reexecutado pelo
revisor com o mesmo resultado). Valida contra o harness: 78 chunks e 15.360 quads em r 8.

| Caso | malha completa (avaliação + costura + cor + emissão) | avaliação com costa / sem costa |
|---|---|---|
| moderado r 6 | 3,3 a 3,4 ms | 2,1 a 2,2 / 1,8 |
| moderado r 8 | 4,3 a 4,8 ms | 2,7 a 3,0 / 2,3 a 2,5 |
| moderado r 12 | 6,35 a 6,5 ms | 3,9 / 3,3 a 3,4 |
| moderado r 8 sem recorte (F5 ou sombra) | 12,8 ms | 7,6 / 6,5 |
| moderado r 8 com `espacamento` 2 | 1,7 ms | 1,1 / 1,0 |
| sombra (r 8, células ≥ 4) | 1,9 ms | 1,4 / 1,2 |

Envio pelo `Tessellator` (fora da tabela):

* emulado sem os mixins do Angelica: 0,8 a 1,2 ms;
* harness de desenvolvimento, laço de `addVertex` sem Angelica: 3,2 a 5,6 ms (`PLANO-DE-TESTES.md:41-50`);
* Angelica: **sem medida confiável** (ver 2(b).2).

O único dado do pack real nos documentos é de GL por software: um quadro de 64 a 80 ms com o mar contra 58 ms sem
(`TESTES-AMBIENTE-REAL.md`).

### 3.3 Meta

A meta é queda de FPS ≤ 10%, ou seja, custo extra ≤ T/9 por quadro:

| FPS | orçamento por quadro |
|---|---|
| 144 | 0,77 ms |
| 100 | 1,1 ms |
| 60 | 1,85 ms |
| 40 | 2,8 ms |

Hoje a estimativa é de 8 a 16 ms (r 8, sem shaderpack), de 4 a 9 vezes o orçamento a 60 FPS (PERF §6).

### 3.4 Tabela a preencher pelo usuário (cliente TEOF 6.3, com GPU)

Condições fixas, registradas junto de cada linha:

* local: `Akashic/Spawn`, mar aberto, câmera parada a ~3 m da superfície, e uma segunda tomada na costa;
* `renderDistance` 6 e 8;
* **vsync desligado e `maxFps` ilimitado** (com vsync a queda só aparece acima de 16,7 ms);
* `shaderPack=` vazio e, numa segunda série, BSL 8.2.09;
* estado do mar "moderado";
* 60 s após entrar, média de 30 s;
* do F3 (`ClientProxy.java:349-359`): "malha", "envio", o pior quadro (p99) e o `Hs` mostrado.

| Versão | rd | shaderpack | FPS médio | tempo de quadro médio / p99 (ms) | F3 malha (ms) | F3 envio (ms) | queda vs (i) |
|---|---|---|---|---|---|---|---|
| (i) sem o OceanWaves (jar removido) | 6 | — | | | — | — | — |
| (i) sem o OceanWaves | 8 | — | | | — | — | — |
| (i) sem o OceanWaves | 6 | BSL | | | — | — | — |
| (ii) OceanWaves atual (`c56f672`) | 6 | — | | | | | |
| (ii) OceanWaves atual | 8 | — | | | | | |
| (ii) OceanWaves atual | 6 | BSL | | | | | |
| (ii) atual com `/ondas desligar` | 6 | — | | | | | |
| (iii) versão nova: ___ (commit) | 6 | — | | | | | |
| (iii) versão nova: ___ | 8 | — | | | | | |
| (iii) versão nova: ___ | 6 | BSL | | | | | |

---

## 4. Prioridades e arquitetura

### 4.1 Tabela de prioridades

Escalas:

* **Impacto**: A = destrava o objetivo ou elimina falha grave; M = melhora clara; B = acabamento.
* **Risco**: chance de quebrar algo ou de pedir decisão do dono.
* **Esforço**: P = horas; M = dias; G = semanas.

| Ordem | Item | Achados | Impacto | Risco | Esforço | Repositório |
|---|---|---|---|---|---|---|
| 1 | Detectar o OceanWaves sem tipos do mod (`Class.forName` + `API_VERSION` numa classe neutra), guardar num booleano lido em `:191` e `:2511`; teste que carrega `ExpeditionsCommand` **sem** o jar no classpath | EXP-1 | A | B | P | Expeditions (branch) |
| 2 | Falha do renderizador publica `rendererFailed` volátil; o `ClientSea.tick` (não o `catch`) chama `loadRenderers`; capturar também `LinkageError`; F3 mostra "desligado por erro" | VIS-4 | A | B | P | OceanWaves |
| 3 | Mudança de `seaLevel` invalida tudo (`invalidateAll` + `loadRenderers` sob `active`) | VIS-5 | A | B | P | OceanWaves |
| 4 | Cortes de FPS baratos: costa uniforme (`uniform()`, arredondar para 1,0f, sentinela `OPEN_SEA` e nunca `null`); orçamento e coalescência das costas (refazer em grupo, ou medir a fresta transitória); manutenção por tick; limpeza de alocações e de `glGet`; com shaderpack, `espacamento` 2 e sombra desligada por padrão | PERF-3, 4, 5, 7, 8 (O4, O5, O6, O11, O7) | M (0,7 a 1,4 ms, mais os picos) | B | P a M | OceanWaves |
| 5 | Mesclar o branch `n82b79` (avanço direto sobre a `main`) depois do item 1; publicar o Expeditions atual (com a fragata) no servidor; instalar o OceanWaves nos dois lados; o Kunftig do servidor só sobe para ≥ 16 se forem usados leme, posturas ou zoom | EXP-5, ENV-1, 2, 3 | A | M | M | Expeditions, implantação |
| 6 | `SeaSampler` e `SeaProbe` no core: ζ, ∇ζ, ∂ζ/∂t, normal; costa e região dentro; partida quente; nada alocado. Cinemática u, v, w, aceleração e p_din com Wheeler na fase lagrangiana, com a referência hidrostática declarada (η, não ζ). `surfaceY` delega a ele (com a mesma folga de costa do cliente) | WAVE-1, 2, 10; BOAT-6 | A | B | M | OceanWaves core + API |
| 7 | Mesma superfície nos dois lados: a folga de costa com o mesmo teto (decidir: limitar a configuração, ou o cliente sem o teto de 32); relógio que congela em vez de voltar; dt da física do cliente = avanço do relógio do mar; cópias defensivas em `waves()`; o preset de 80 componentes respeitado ou documentado | WAVE-7, 9, 11, 12; VIS-17 | M | M | P a M | OceanWaves |
| 8 | `PressurePatch` sobre o **retângulo girado** da pegada, com 3 a 4 camadas lagrangianas; dá p/ρg **e ∇ₕp**; reaproveita a grade de t₁ como t₀; camadas fundas a espaçamento 2 | WAVE-3, EXP-2, 3, 8, 10 | A | M | M | OceanWaves core + API |
| 9 | `Seakeeping6` no core do Expeditions: um corpo de 6 graus com as forças verticais **e horizontais** das pressões, com braço até o CG (troca o `FloatingBody` e metade do `ShipMotion`); amortecimento sobre a velocidade relativa à água; testes de RAO e do limite de onda longa (balanço/declive → 1); orçamento < 0,15 ms por tick | EXP-2, 3, 6, 15 | A | M | G | Expeditions core |
| 10 | Gerador: colunas iguais às da ficha (ou a ficha calculada das colunas), costado real, inércias das peças, a_ij e b_ij por faixas na frequência natural; `ShipYamlTest` com GM, GML e Awp ±3% | EXP-4, 7, 9, 6 | M | B | M | Expeditions shipgen |
| 11 | Kunftig, etapa 0, no plugin: prever **g+1** ticks (calibrar contra o `OceanClock` em jogo); glide 3 com cadência 2 como colchão contra jitter; tempo do mundo | KUN-1, 2, 3, 4 | M | B | P | Expeditions |
| 12 | Kunftig API 17: `setPose` atômico e `MOVE2` com `worldTick`, velocidade linear e angular; extrapolação no cliente e correção diluída; servidor e cliente atualizados juntos | KUN-1, 2, 4, 11 | M | M | M | Kunftig |
| 13 | Barco do Et Futurum: `FloatingHull` no core (colunas, empuxo e momentos, massa adicional, g = 9,81, sem alocação); mixins tardios `@Pseudo` com `require=0` no `EntityNewBoat` | BOAT-1 a 7 | A | M (mixin tardio a verificar) | M | OceanWaves |
| 14 | Neo API 5: SPI `SeaSurface` por coluna (`surfaceY`, `velocity`, `crestMargin`) e `surfaceAt(IBlockAccess,double,double)`; `ModernWater` e câmera por ponto, com histerese molhado/seco; `EntityXPOrb`. OceanWaves: provedor com `SeaSampler` por thread, registro no `init`, opção sincronizada; teste "mesma ζ do desenho" | VIS-1, 2; NEO-1, 2, 4, 5, 6, 11; ENV-5; KUN-6 | A | M (três consumidores e o ThePatcher) | M a G | Neo States + OceanWaves |
| 15 | Desenho alinhado à física perto dos navios: sem EdgeFade em volta das pegadas; máscara do casco (pegadas do servidor); casco antes da folha ou corte aceito | WAVE-5, EXP-11, VIS-13 | M | M | M | OceanWaves (+ Kunftig) |
| 16 | FPS estrutural: fork-join de avaliação, cor e emissão (com t exato), com instantâneo das colunas e instâncias por thread; LOD temporal só nos chunks distantes | PERF-1, 10 (O2, O3) | A (−3 a −4,5 ms) | M | M | OceanWaves |
| 17 | Envio: **decisão do dono** entre o O1 (buffer próprio de streaming, índices dependentes da máscara de costa, nova ADR, testado sob o Angelica numa GPU) e o O8 (dentro do `Tessellator`: sem `setNormal` quando `own`, emissão direta); medir antes o laço de `addVertex` sob o Angelica | PERF-2, 6 | A ou M | A | M | OceanWaves |
| 18 | Visual: lado de baixo (decisão por fragmento pelo sinal bruto de `l·v`), mipmap, lightmap, profundidade na cor, clima, espuma, sons, placa plana enquanto o chunk é lido; casos novos no harness (câmera na crista e no vale, noite, chuva, entidades, nível trocado, exceção forçada) | VIS-3, 6 a 12, 15 a 19 | M a B | B | M | OceanWaves |
| 19 | Grade compartilhada por anel de LOD; `detalhe` 0,03 depois de avaliação visual | PERF-9 (O10, O9) | M | M | M | OceanWaves |
| 20 | Profundidade finita por mundo no `SeaState` (sobe `MessageSeaState.VERSION`; muda o resumo fixado) | WAVE-8 | M | M (protocolo) | M | OceanWaves |

Por que essa ordem:

* 1 a 3 são falhas graves de custo baixo.
* 4 corre em paralelo e não depende de decisão.
* 5 põe as peças no mesmo lugar.
* 6 a 10 são o núcleo do pedido: o navio movido pela pressão do mesmo mar.
* 11 e 12 fazem o que se vê casar com o que se calcula.
* 13 e 14 estendem o mesmo amostrador ao barco e às entidades.
* 16 e 17 fecham a meta de FPS. Sem o 17 a meta parece inalcançável com distância ≥ 6 (PERF §6), mas o custo do envio sob o Angelica precisa ser medido antes.

### 4.2 Arquitetura recomendada: o mar dirige barcos e a fragata

**Princípio**:

* uma função do mar (componentes, Gerstner, costa, região, profundidade) e um dono do tempo (servidor:
  `totalWorldTime/20` do tick; cliente: relógio do mar no início do tick);
* **cada corpo tem uma autoridade**;
* a física lê **pressão**, não só altura.

```
OceanWaves core (Java 8 puro, testado; StrictMath na síntese)
├─ SeaSampler + SeaProbe ........ ζ, ∇ζ, ∂ζ/∂t, normal, atenuação; u, v, w, a, p_din/ρg (Wheeler, fase lagrangiana)
├─ PressurePatch ............... camadas b = 0, −1, −2, −4 m sobre o retângulo girado; p/ρg e ∇ₕp por consulta
├─ FloatingHull ................ corpo de 3 graus verticais (+ arrasto horizontal relativo) para barcos pequenos
└─ testes ...................... "mesma ζ do desenho", limite de onda longa, nada alocado, folga de costa igual

OceanWaves mod
├─ servidor: ServerSea (ShoreField por corpo, folga = a do cliente), API 4:
│    sampler(dim), pressurePatch(dim, centro, guinada, L, B, s), time(dim), restLevel(dim); WaveSet só por cópia
├─ cliente: ClientSea + acessor attenuationAt(x,z) sem alocação no OceanChunks; relógio que não volta
├─ mixins tardios (@Pseudo, require=0) no EntityNewBoat: getBoatStatus (nunca UNDER_WATER nem IN_AIR por bloco no mar
│    com ondas), checkInWater/getWaterLevelAbove/getUnderwaterStatus, updateMotion (HEAD pula o reposicionamento
│    IN_AIR→IN_WATER; TAIL troca motionY), updatePassenger, NewBoatRenderer.setupRotation (após glRotatef ordinal 0)
├─ mixin no EntityBoat vanilla (antes de moveEntity) e RenderBoat.doRender (pivô próprio); integração com o futura3d
└─ provedor SeaSurface do Neo (registrado no init, opção sincronizada)

Neo States (API 5, aditiva)
└─ SeaSurface SPI; ModernWater por coluna; câmera por ponto, com histerese; velocity no lugar de getFlowVector; surfaceAt

Expeditions core (Java 21)                         Expeditions shipgen (offline)
├─ HullModel (colunas = ficha)                     ├─ inércias das peças → kg, lcg, kxx, kyy, kzz, kxz
├─ Seakeeping6: M·ν̇ = F_p(vert+horiz, braço CG)   ├─ faixas (Lewis) → a_ij, b_ij → navios.yml
│   + F_dif + F_rad + F_visc + vento + amarra      └─ colunas com o costado real
└─ testes: RAO, onda longa, decaimento, GM

Expeditions plugin (servidor, autoridade da fragata)
└─ a cada 2 ticks: PressurePatch(t₁ = time + (g+1)/20), 4 subpassos, pose prevista g+1 ticks à frente

Kunftig (cliente desenha e leva quem está a bordo)
├─ etapa 0: nada muda; etapa 1 (API 17): setPose atômico + MOVE2 (worldTick, velocidades), extrapolação
└─ em paralelo: estado de água do convés (inWater falso a bordo), histórico de pose para jogadores remotos
```

**Onde roda cada parte.**

* **Fragata**:
  * a dinâmica autoritativa fica no **servidor** (plugin, Java 21), porque o navio é visto por muitos e leva vários a bordo;
  * o cliente só desenha. O erro de interpolação a 10 Hz é desprezível (2,5 mm, 0,011°), então não há ganho visual em mover a física para o cliente;
  * o que falta para casar com o mar desenhado é tempo (prever g+1; depois `MOVE2` com `worldTick`), não física no cliente;
  * a via "o cliente calcula" fica como opção futura: o `BODY`/`FLOAT` do KUN §8, ou a resposta linear (RAO), sem estado. Ela exige a física compartilhada em Java 8, a superfície por interface no Kunftig e a versão nova nos dois lados.
* **Barco do Et Futurum**:
  * a autoridade é o **cliente do piloto**, que já é como o Et Futurum funciona; barcos vazios ficam no servidor;
  * o y e o caturro seguem na `BoatMoveMessage`, como já vão;
  * o balanço não tem campo de rede: ou ganha um, ou é integrado localmente por quem vê. Integrar localmente é física de verdade (as mesmas equações, o mesmo ζ, um sistema amortecido que converge), mas contraria o princípio de uma autoridade por corpo da frente WAVE. Fica como pergunta (seção 5).
* **Entidades** (jogador, itens, orbes, anzol): o Neo decide "dentro" e o empurrão pelo provedor do OceanWaves; cada lado usa o seu tempo.

**O que fica no core puro**: `SeaSampler`, `PressurePatch` e `FloatingHull` (OceanWaves); `Seakeeping6` e `HullModel`
(Expeditions). Tudo testado sem Minecraft e sem alocar.

**O que vai para a API do OceanWaves (API 4)**:

* `sampler(dim)` por thread ou corpo;
* `pressurePatch(...)`;
* `time` e `restLevel`;
* `WaveSet` só por cópia;
* `surfaceY` delegando ao amostrador.

O jar continua `compileOnly` no plugin, com detecção sem tipos do mod.

**Mudanças no Expeditions**:

* itens 1, 5 e 9 a 11;
* o `ShipMotion` aplica a força horizontal com momento até que o `Seakeeping6` o substitua;
* trocar `Math.*` por `StrictMath` só se a física for para o cliente.

**Mudanças no Kunftig**:

* etapa 1: API 17, servidor e cliente juntos, operações aditivas com canal de registro novo;
* estado de água a bordo;
* histórico de pose dos remotos;
* nenhuma dependência direta do OceanWaves; se um dia houver, por interface registrada, como faz o GeckoLib.

**Escala**: o mar do preset é uma maquete de Froude 0,5, e o navio está em 1 bloco = 1 m. A dinâmica é coerente
porque os dois usam o mesmo g, mas o navio se comporta como um de 2× o tamanho num mar real. É escolha de projeto e
deve ficar escrita.

---

## 5. Perguntas em aberto

**Decisões do dono**

1. Aceitar um envio fora do `Tessellator` (buffer próprio, nova ADR) no modo sem shaderpack? Antes disso: quanto custa
   o laço de `addVertex` sob o Angelica numa GPU real?
2. Estender o Neo States (API 5, `SeaSurface`) ou manter o Neo intocado e usar um `FluidStage` por célula como solução
   parcial? O `FluidStage` só cobre os vales e as cristas até +0,11 m.
3. Quem está dentro de um casco conta como na água quando ζ passa do piso? O amostrador deve ignorar as colunas sob o
   casco?
4. Barco do Et Futurum: o balanço visto por outros clientes ganha campo de rede ou é integrado localmente?
5. A física dos corpos usa o espectro completo, um prefixo pelo tamanho do corpo, ou o peso por λ da malha? Este
   último depende do `minSpacing` do cliente, que o servidor não conhece.
6. A escala de Froude 0,5 do mar, com o navio a 1 bloco = 1 m, é intencional?
7. Profundidade: uma por mundo (medida como o nível do mar) ou por coluna? Por coluna exige refração, hoje fora do
   modelo.
8. Até onde ir na radiação (coeficientes na frequência natural, ou Cummins)? Emborcar e alagar entram no jogo?
9. Folga da costa: limitar a configuração ao teto do cliente (32) ou tirar o teto do cliente?
10. O EdgeFade (que existe por causa do Mirai/Distant Horizons) pode ser suspenso em volta dos navios? Com que alcance?

**Verificações de ambiente e de jogo**

11. O mixin tardio do UniMixins (`ILateMixinLoader`/`@LateMixin`) funciona na build GTNH do OceanWaves com o
    `OceanMixinPlugin` atual?
12. O `EntityJoinWorldEvent` do Forge 1.7.10 também dispara para barcos carregados de chunks (o que decide se ainda
    sobram `EntityBoat` vanilla no mundo)? Qual o alcance e a frequência do rastreador do `EntityNewBoat`?
13. O `S03PacketTimeUpdate` sai a cada 20 ticks no Crucible? `World#getFullTime()` devolve o total do mundo, como
    `OceanWaves.time(dim)`? Quanto o `OceanClock` difere do `worldTime` do cliente e do relógio do servidor?
14. Com rede real: qual a distribuição do intervalo entre `MOVE`s, quantas paradas por minuto, e quanto um jogador
    remoto se afasta do piso com mar de 2 a 3 m?
15. O jogador no convés num cavado fica com `inWater` verdadeiro? Algum anticheat do TEOF reclama de jogador parado no
    ar sobre um convés que se move a ~0,1 m/tick?
16. O passe 1 (`sortAndRender`) roda mais de uma vez por quadro no TEOF? O `GL_TRIANGLE_STRIP` passa intacto pelo
    `TessellatorStreamingDrawer`? A animação da textura da água continua com `animate_only_visible_textures=true`?
17. Quais os números no Java 8 dos clientes? Não há JDK 8 aqui, então o resumo da síntese só foi conferido no Java 21.
18. O servidor de produção tem o `Akashic/Spawn` com ou sem `@` no `oceanwaves.cfg`? O inventário não tem
    `server.properties` nem pastas de mundo.
19. Com shaderpack: o navio do Kunftig vem antes ou depois do composite do Iris? O que o BSL e o Complementary fazem
    com o lado de baixo da folha? De onde vêm as linhas tracejadas nas capturas?
20. O ThePatcher (módulo `neostates`) fica inerte com o Angelica? A ordem real dos dois mixins de
    `shouldSideBeRendered`, observada em jogo?

---

## Apêndice: Descartados e correções

Nenhum achado inteiro foi refutado pelos revisores. Foram descartadas ou corrigidas as afirmações parciais abaixo.

| Origem | Afirmação descartada | Motivo |
|---|---|---|
| BOAT-1 | "a troca acontece no cliente e no servidor" | `entityAdded` retorna se `isRemote`: a troca é só no servidor |
| NEO-3 (revisão, item d) | "`replaceOldBoats` só atua no item; não há troca de entidade" | contradito pelo bytecode de `ServerEventHandler.entityAdded` (offsets 131 a 208, `new EntityNewBoat`), reconferido nesta síntese; a troca do item também existe |
| BOAT-4 | "o problema começa com cavados de mais de 1 m" | o limiar é cerca de 0,2 m abaixo do repouso (topo do AABB contra a água), e o afundamento é irreversível |
| PERF-2 | severidade "crítica"; "o Angelica encarece cada `addVertex`"; "9 a 11 ms no Angelica" como medida | os hooks são testes de campo na thread de desenho; o número só aparece em prosa e pode ser a coluna "GL"; rebaixado para "alta" com medição pendente; o ganho é 71,4%, não 72%, e só em células inteiras |
| PERF-3 | `Attenuation.java:79-81` | o arquivo tem 24 linhas; o padrão está em `:21-23` |
| PERF-5 | "a sombra repete a releitura e as costas" | os flags `dirty` e `shoreDirty` já foram zerados na primeira passagem; repete-se só a verificação, a ordenação e o rodízio |
| PERF-1 | "6,5 ms medido em r 12"; "o custo cresce com o FPS" | r 12 só no bench; o custo por quadro é constante, o que cresce é a CPU por segundo |
| WAVE-3 | "força 1,3 a 1,7× maior; 3× nas ondas curtas" | com as colunas reais e a fase: 1,32× no moderado, 1,16 a 1,21× na tempestade, cerca de 1,9× na brisa; severidade média |
| WAVE-4 | "a integração documentada não existe" | existe em `origin` (`b50c9cf`); falta buscar, mesclar e implantar |
| WAVE-2 | "p = 0 na superfície" sem ressalva; "+2 µs por ponto" como custo total | a pressão zera em ζ − c conforme a referência; o ponto completo custa 5,4 a 7,3 µs (Java 21) |
| VIS-1 | "a névoa pisca com a onda" como sintoma atual | hoje o estado não depende da onda; o pisca-pisca é risco da correção e pede histerese |
| VIS-1 | "um `FluidStage` corrige tudo de uma vez" | o estágio é limitado à célula com água e a [0,1], sem tempo; é preciso mudar o `ModernWater` e a câmera no Neo |
| VIS-2 | evidência `ModernBobber.java:16` | é javadoc; o ponto real é `EntityFishHookMixin.java:273` |
| VIS-3 | "cobertura azul-céu uniforme"; "ndv sempre 1e-3" | é um gradiente de céu dependente da visada; em encostas, `l·v > 0` |
| VIS-4 | "chamar `loadRenderers` na falha"; "nem água vanilla" | `loadRenderers` no tick, não no `catch`; somem só as faces de cima |
| EXP-2 | "2,4 a 3,3 vezes" | no mar irregular, 2,1 a 2,8 (o 3,3 é em λ = 28 m, onde o balanço é pequeno) |
| EXP-3 | caturro "1,2 a 1,3"; "no mar real" | 1,2 a 1,35, contra um modelo de Froude-Krylov sem difração nem radiação |
| EXP-4 | "BM/GM +32%" | só o GM: +32%; o BM: +9% |
| EXP-5 | "atualizar o Kunftig antes da física" | o branch exige só a API 9; as APIs 14 a 16 são opcionais |
| KUN-1 | "o mar é desenhado em (totalWorldTime+parcial)/20" | é a reserva; o caminho normal é `OceanClock.secondsAt(nanoTime)` |
| KUN-5 | "7 usos de `Math.*`"; "o Kunftig não conhece o horário do mundo" | são 17 ocorrências em 8 linhas; o Kunftig já lê `getTotalWorldTime` fora das exibições |
| NEO-1 | "o ADR-0003 prevê tempo e posição contínua" | prevê só `flow`, `cornerHeight` e prioridade, também por inteiros |
| NEO-2 | "acima do repouso" como limiar | o limiar é y + 8/9 do bloco-fonte |
| ENV-1 | "218 e 226 jars" | 217 e 227 jars recursivos; 218 e 226 são entradas de `ls` |
| ENV-2 | severidade "alta" | o Expeditions implantado só usa as APIs 12 e 13: média |
| ENV-5 | "alimentar `waterHeight` com ζ" | `waterHeight` é profundidade de submersão (saída); o encaixe é `ModernWater.surface()` ou o provedor |
