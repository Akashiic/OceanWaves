# Auditoria VIS: visual e imersão do OceanWaves

Escopo: o que um jogador veria de errado com o mar do OceanWaves (cliente TEOF 6.3, servidor Crucible do Forbidden).
Lido: `client/*`, `world/OceanColumns`, `mixin/*`, `core/OceanMesher|SpectralLod|WaveSet|OceanClock`, `OceanConfig`,
`docs/` (ARQUITETURA, TESTES-AMBIENTE-REAL, PLANO-DE-TESTES, ADRs, `img/` e `img/real/`), o harness, mais Neo States
(fonte, `/home/user/Neo-States`), Kunftig (fonte), e os jars do cliente real (`/home/user/ext/teof/x`: `neostates`,
`angelica-2.2.43`, `DynamicSurroundings`, `minecraft.jar`, `options.txt`, `config/`). Nada foi alterado nos repositórios.

## Como ler as marcas de evidência

* **[VISTO]**: aparece numa captura de `docs/img` ou num texto de teste do repositório (a captura é citada).
* **[LIDO]**: verificado por leitura do código ou do bytecode (`arquivo:linha` ou classe/método); o efeito no jogo
  **não foi visto** por ninguém, é consequência do que o código faz.
* **[DEDUÇÃO]**: depende de comportamento vanilla/de outro mod que lembro mas não reli aqui; precisa de confirmação em
  jogo. Cada uma traz o roteiro de verificação no fim.

Números do mar vêm de uma execução real do núcleo (`Synthesis.build` dos presets, compilado e rodado em
`/home/user/work/tmp/vis/Probe.java`): moderado Hs 1,16 m (crista máxima possível 2,51 m), fresco Hs 1,87 m (3,88 m),
tempestade Hs 2,97 m (6,75 m, L = 0,62). Nenhum número de FPS é citado neste relatório.

## Resumo executivo

A superfície **desenhada** é boa (sem frestas, costura de LOD correta, costa sem degrau, integração com BSL e
Complementary vista). O problema de imersão é o oposto: **tudo o que o jogo trata como "água" continua sendo o plano
plano a y + 8/9**, e a onda só existe como uma folha de vértices por cima dele. Os cinco que mais pesam:

1. **VIS-1 (crítico)**: câmera, olhos, névoa submersa, sobreposição azul e o `isEyeInWater` dos shaderpacks viram no
   plano, não na onda. Com Hs de 1,2 m a 3 m, quem nada na superfície atravessa a folha de água sem nenhuma troca de
   estado, e fica "molhado" (névoa azul) com a folha abaixo dele, ao ar livre.
2. **VIS-2 (alto)**: corpo do jogador, itens, anzol, bolhas e o som de respingo seguem o plano. Nada reage à onda.
3. **VIS-3 (alto)**: visto de baixo, a folha é um espelho do céu quase opaco (93%), sem janela de Snell.
4. **VIS-4 (alto)**: se o renderizador falha, o mixin continua escondendo a face de cima da água vanilla: o oceano
   inteiro some (sem água nenhuma), em vez de voltar à água vanilla.
5. **VIS-5 (alto)**: quando o servidor mede o nível do mar depois de o cliente já ter montado chunks, nada é
   invalidado: ficam chunks com a água vanilla plana e sem ondas (ou buracos), até algo remontar.

O resto é de impacto médio (buracos transitórios, textura sem mipmap, luz que ignora gamma/relâmpago/visão noturna,
clima desligado do estado do mar, sem profundidade na cor, sem espuma de costa nem de casco, sem sons nem partículas)
e baixo. A lacuna estrutural é que o **harness nunca testou nada com a câmera dentro da onda, de noite, com chuva ou
com entidades na água** (VIS-15): o item 9 do plano de testes ("embaixo d'água") ficou pendente.

---

## Achados

### VIS-1 (crítico): o jogador, a câmera, a névoa e os shaders decidem "molhado" pelo plano 8/9, não pela onda

* **Sintoma esperado**: quem nada na superfície de um mar de Hs 1,2 m vê a folha ondulada passar por cima da cabeça
  sem que a névoa azul, a sobreposição de água e o tom do pack acompanhem; e, num vale, fica com a folha 0,5 m abaixo
  e a tela toda azul (molhado) olhando para o céu aberto. Quanto maior o mar, maior a janela de erro (±Hs/2 típico).
* **Causa**:
  * O mod não toca em nenhuma entidade nem na câmera: só 4 mixins de cliente e **nenhum** de servidor/comum
    (`src/main/resources/mixins.oceanwaves.json:10-16`: `BlockLiquidTopFaceMixin`, `RenderGlobalOceanPassMixin`,
    `NetHandlerPlayClientTimeMixin`, `TextureAtlasSpriteAccessor`). Fora o mixin da face e o do passe de desenho, o mar é
    só uma folha de vértices.
  * O que decide "olho na água" no pack real é o Neo States, que usa a altura da **célula** (8/9 para uma fonte sob
    ar): `MixinEntity_ModernWater.java:72-78` (`isInsideOfMaterial` → `ModernWater.submerged`),
    `ModernWater.java:34-56` (`surface()` = `FluidPipeline.height(..., INTERACTION)`),
    `MixinActiveRenderInfo_Water.java:26-47` (câmera: `camera.yCoord < y + surface`), e
    `docs/adr/ADR-0003` do Neo States ("altura de interação: corpo e olhos, anzol, barco, câmera").
  * A névoa e a visão adaptativa do Neo States ligam e desligam com `isInsideOfMaterial(water)`
    (`UnderwaterFog.java:48`, `:101-123`). O ganho de visão sobe 1 por tick molhado e cai 10 por tick seco
    (`UnderwaterMath.tickVision`, `UnderwaterMath.java:21-25`) e o fim da névoa é `96·max(0,25, visão)`
    (`UnderwaterMath.java:40-44`): uma onda que alterna molhado/seco a cada poucos segundos deixa a névoa quase
    sempre curta (24 m) e piscando.
  * O Iris do Angelica lê o mesmo método: `CommonUniforms.isEyeInWater()` chama
    `EntityClientPlayerMP.isInsideOfMaterial(Material.water)` (bytecode de `angelica-2.2.43.jar`,
    `net/coderbot/iris/uniforms/CommonUniforms.isEyeInWater`, offsets 30-33). Logo o BSL e o Complementary ligam a
    água de baixo, as cáusticas e o tom submerso também pelo plano.
  * Sem o Neo States valeria o vanilla/Forge: `isInsideOfMaterial` por célula inteira e a câmera em `1 - decay/9`
    (= 1 para fonte), ver `Neo-States/docs/VANILLA-1.7.10.md` seção 3. Também plano.
* **Status**: causa **[LIDO]**; o sintoma **não foi visto**: o harness mantém a câmera sempre acima da folha
  (`OceanHarness.java:44-52`: a mais baixa, `moderado-rente`, y = 64,2 contra o plano 62,89), e o item 9 do plano
  de testes (`PLANO-DE-TESTES.md:88-89`) nunca foi feito. O próprio `ARQUITETURA.md:158` lista "névoa submersa
  coerente (câmera dentro da crista = debaixo d'água; no vale, fora)" como fase futura.
* **Correção**: uma única definição de superfície, que o Neo States já prevê (`FluidPipeline` + `FluidStage`,
  "uma onda ... vira altura de interação e precisa ser função do `totalWorldTime` e da posição",
  `Neo-States/docs/adr/ADR-0003-pipeline-de-fluidos.md` seção 2). Registrar um estágio determinístico do OceanWaves
  corrige de uma vez olhos, câmera, névoa, `isEyeInWater`, corpo, anzol e itens. **Limite a resolver**: o estágio
  devolve uma fração 0..1 **dentro da célula** (`FluidStage.height`, "0..1"), e a onda sai da célula (a crista de
  +0,6 m cai na célula de ar de cima, que `ModernWater.surface` responde como "sem água"). Precisa de uma consulta
  de superfície absoluta por coluna (x, z, t), com a atenuação da costa de `OceanChunks`/`ShoreField`, no lugar da
  leitura por célula. No cliente, o tempo da câmera é o quadro (`ClientSea.time(partial)`); na física das entidades,
  o tick (`totalWorldTime`).

### VIS-2 (alto): corpo, itens, anzol, bolhas e respingos seguem o plano

* **Sintoma esperado**: o jogador "afunda" e flutua no plano; numa crista de 1 m ele está dentro da folha sem estar
  "na água" (cai, sem arrasto, sem nado); num vale, nada com a folha abaixo dos pés. Itens e orbes (Neo States
  `floatingItems=true`, `config/neostates.cfg`) pairam sobre o vale ou somem sob a crista. O anzol do AkashicFishing
  boia no plano. Respingo e som de entrada acontecem no cruzamento do plano.
* **Causa**:
  * `ModernWater.update` testa `top = y + surface(...)` por célula (`ModernWater.java:86-131`) e é o que a
    `handleWaterMovement` chama (`MixinEntity_ModernWater.java:58-69`).
  * O anzol: `ModernBobber.java:16` ("flutua onde a água é desenhada": 8/9) e `EntityFishHookMixin.java:273`
    (`NeoStatesBridge.waterSurface`). Onde "a água é desenhada" agora é a folha, não 8/9.
  * Bolhas e respingo de chuva [DEDUÇÃO]: `EntityBubbleFX` do vanilla só morre quando o bloco da célula deixa de ser
    água; a chuva do Dynamic Surroundings usa `World.getPrecipitationHeight` (bytecode
    `StormSplashRenderer.getPrecipitationHeight` → `func_72874_g`; `addRainParticles` chama `getPrecipitationHeight`),
    ou seja, altura de bloco, não de onda.
* **Status**: causa **[LIDO]**; efeitos no jogo **[DEDUÇÃO]** (nenhuma captura tem jogador, item ou anzol na água
  com ondas). É a mesma raiz de VIS-1 e do que a frente BOAT trata para barcos.
* **Correção**: a do VIS-1 (um só estágio de superfície consultado por corpo, olhos, anzol e itens). Lado visual:
  partículas e som de respingo disparados pela passagem da onda, não do plano (VIS-12).

### VIS-3 (alto): vista de baixo da folha = espelho do céu quase opaco

* **Sintoma esperado**: com a câmera debaixo da folha (por exemplo na crista, onde VIS-1 não troca o estado), olhar
  para cima mostra uma cobertura azul-céu uniforme, sem ver o céu, o sol, nuvens ou o casco do navio acima; não há
  janela de Snell nem reflexão interna: sempre o espelho.
* **Causa** (`SurfaceShader.java:103-177`, caminho sem shaderpack): a normal da folha nunca é virada para quem olha
  de baixo. Com a câmera abaixo, `vy < 0`, `ndv = max(1e-3, n·v)` fica em 1e-3 (`:125`), `m ≈ 0,999` e
  `fres = 0,02 + 0,98·m⁵ ≈ 0,995` (`:127-128`): a cor vira quase só o reflexo do céu (`:129-141`), e
  `a = a + (1 − a)·0,7·fres` (`:149`) dá opacidade ≈ 0,78 + 0,22·0,7·0,995 ≈ 0,93. O especular ainda soma
  (`:150-160`). Os dois lados são desenhados (`GL_CULL_FACE` desligado, `OceanRenderer.java:313`) com a mesma cor.
* **Status**: **[LIDO]** (conta feita sobre o código; não visto). Com shaderpack o comportamento do lado de baixo é do
  pack e **[a verificar]** (vem a normal do mod, sem virar).
* **Correção**: se a câmera está abaixo da folha, virar a normal e calcular transmissão (refração do céu pela janela
  de Snell, ~48°) + reflexão interna total fora dela, com a cor da névoa submersa; é só uma ramificação do
  `SurfaceShader.shade` com o sinal de `n·v`.

### VIS-4 (alto): falha do renderizador deixa o oceano sem água

* **Sintoma**: depois de qualquer `RuntimeException` no desenho, o mar some por completo (sem folha e sem água
  vanilla) até trocar de mundo.
* **Causa**: `OceanRenderer.render` captura a exceção, põe `failed = true` e o desenho para
  (`OceanRenderer.java:136-141`; só `onWorldChange` limpa, `:365-368`). Mas o mixin que esconde a face de cima
  continua valendo: `ClientSea.hideTopFace` só olha `active` (`ClientSea.java:98-100`), e `active` não depende de
  `failed` (`ClientSea.java:141-148`). As faces vanilla já montadas nos chunks não voltam.
* **Status**: **[LIDO]**. A regra "o renderizador desliga a si mesmo com um log" (`CLAUDE.md`) está cumprida, mas o
  efeito para o jogador é pior que desligar o mod. Não testado.
* **Correção**: publicar o `failed` em `ClientSea` (volátil), fazer `hideTopFace` devolver falso e chamar
  `mc.renderGlobal.loadRenderers()` quando ele for ligado, como `setRegion` já faz (`ClientSea.java:152-161`).

### VIS-5 (alto): mudança do nível do mar não invalida o cliente (nível "medido")

* **Sintoma esperado**: com o nível medido pelo servidor (config `Akashic/Spawn` sem `@`, o caso do servidor de
  produção descrito em `TESTES-AMBIENTE-REAL.md`, "Defeitos" 6: o servidor começa em 63 e mede 31 depois), o cliente
  que já montou chunks com o nível 63 fica com a água vanilla plana nos chunks já montados (a regra `y == seaLevel-1`
  só vale na montagem) e, nos remontados, um buraco: o cache guarda "sem mar" para eles.
* **Causa**:
  * `ClientSea.tick` aplica `seaLevel = m.seaLevel` (`ClientSea.java:120-130`); `setState` e `setRegion` voltam sem
    fazer nada se são iguais (`:152-170`), então `version` não sobe e `loadRenderers()` não roda.
  * O renderizador só invalida o cache quando `version` muda (`OceanRenderer.java:166-170`). Um chunk lido no nível
    antigo fica com `c.any = false`, e `if (!c.any) continue` (`OceanRenderer.java:211-213`) o tira de `visible`; o
    rodízio `refresh` só roda sobre `visible` (`:237-247`). Nunca mais é relido, a não ser que um bloco mude perto.
  * O servidor reenvia a todos ao medir (`ServerSea.java:402-416`).
* **Status**: **[LIDO]** com o caminho completo traçado; não reproduzido. Com `Akashic/Spawn@31` na configuração
  (padrão novo) não acontece, mas o `.cfg` já gravado do servidor não é reescrito pelo Forge.
* **Correção**: tratar mudança de `seaLevel` como mudança de região: `version++`, `loadRenderers()` e
  `invalidateAll()`; e o rodízio de `refresh` passar a cobrir chunks com `c.any == false`.

### VIS-6 (médio): buracos sem água enquanto o cache lê os chunks

* **Sintoma**: polígonos com a cor do céu no mar (sem folha e sem face vanilla), e grandes áreas só com o fundo.
* **Status**: **[VISTO]** em `docs/img/real/teof-mp-moderado.png` (buracos de céu no horizonte e manchas escuras de
  fundo; é a captura da época do defeito 2, e o commit `1e6fe8c` a inclui) e descrito em
  `TESTES-AMBIENTE-REAL.md` ("Defeitos" 2). A correção (orçamento de tempo) reduz, não zera.
* **Causa residual**: a face vanilla some em **toda** coluna de mar (`BlockLiquidTopFaceMixin.java:21-27`) antes de
  o cache ter lido o chunk; a leitura tem o limite `rebuilds >= rebuildsPerFrame && dist² > 4 && agora > orçamento`
  (`OceanRenderer.java:203-210`, 3 ms); `rebuildShore` de todos os chunks sujos roda **sem orçamento** logo depois
  (`:231-235`, cada um uma grade de até 80×80 com transformada de distância), então um teleporte ou uma troca de
  mundo pode dar um soluço longo (visual, além do custo).
* **Correção**: preencher o buraco com uma placa plana no nível de repouso (mesma cor) para chunk ainda não lido, e
  dar orçamento ao `rebuildShore` (usar atenuação nula/plana até a costa chegar).

### VIS-7 (médio): textura da água sem filtro nem mipmap

* **Sintoma esperado**: a 40–90 blocos o padrão de 16 px por bloco ("grade") brilha/treme quando a câmera ou a onda
  anda (a UV segue a posição de repouso, que oscila com a órbita); nas capturas o padrão de grade é visível a
  distância (`docs/img/ondas-de-cima.png`: pontilhado regular; `ondas-costa.png`).
* **Causa**: `WaterTexture.bind` sobe a textura com `TextureUtil.allocateTexture(id, w, h)` e
  `TextureUtil.uploadTexture(id, dados, w, h)` (`WaterTexture.java:91-95`). Bytecode de `minecraft.jar` do pack
  (classe `bqi` = `TextureUtil`): `uploadTexture(int,int[],int,int)` chama o envio interno com `blur = false`,
  `mipmap = false`, `clamp = false`, e esses valores dão `GL_TEXTURE_MIN_FILTER = 9728 (GL_NEAREST)`,
  `MAG_FILTER = 9728`, `WRAP = 10497 (GL_REPEAT)`. Nenhum nível de mipmap, e `options.txt` do pack tem
  `mipmapLevels:0` e `anisotropicFiltering:1`.
* **Status**: o filtro **[LIDO]** (bytecode); a cintilação em movimento **[DEDUÇÃO]**.
* **Correção**: gerar mipmaps da textura cinza (ela é pequena) e `GL_LINEAR_MIPMAP_LINEAR`; o estilo "pixel" de perto
  pode ficar com `MAG_FILTER = NEAREST`.

### VIS-8 (médio): a luz própria ignora o mapa de luz do jogo

* **Sintoma esperado**: sem shaderpack, o mar não acompanha o gamma, a poção de visão noturna, o clarão do
  relâmpago (o mundo todo acende, o mar não) nem a cor/tremulação das tochas; a luz de tocha é cinza.
* **Causa**: `SurfaceShader.frame` e `shade` montam a luz com `getSunBrightness`, o `skylight` e o `blocklight` da
  coluna (`SurfaceShader.java:83`, `:112-114`); o mapa de luz fica desligado de propósito
  (`own` → sem `setBrightness`, `OceanRenderer.java:332-336`). O mapa de luz vanilla soma gamma, visão noturna,
  clarão de relâmpago (`lastLightningBolt`) e tremulação de tocha. Também a Lua tem sempre brilho 0,22
  (`SurfaceShader.java:56-65`) sem considerar a fase.
* **Status**: **[LIDO]** quanto ao mod (nenhuma leitura de gamma, `lastLightningBolt` ou visão noturna: busca vazia);
  o comportamento do mapa de luz vanilla é de memória. `options.txt` do pack tem `gamma:0.06`, então o efeito do
  gamma no pack real é pequeno por padrão.
* **Correção**: amostrar o mesmo mapa de luz (um accessor mixin do `lightmapColors` do `EntityRenderer`) na cor do
  vértice, no lugar de recalcular `lit`.

### VIS-9 (médio): o clima não mexe no mar, e o mar nunca muda sozinho

* **Sintoma**: tempestade com raios e chuva sobre um mar "calmo" ou "moderado"; o mar fica em um estado único para
  sempre (sem rajadas, ciclo ou transição).
* **Causa**: o estado do mar só muda por comando/configuração; busca por `rain|weather|thunder|evol|gradual|transi`
  em `server/`, `api/`, `net/` não acha nada. A chuva só entra para apagar o brilho do sol em 85%
  (`SurfaceShader.java:67`). A mudança de estado é instantânea no cliente (`ClientSea.setState`,
  `ClientSea.java:163-170`: `waves` trocado de uma vez, sem interpolar).
* **Status**: **[LIDO]**.
* **Correção**: acoplar o vento (`SeaState.windSpeed`) ao clima do mundo no servidor com transição gradual (as
  componentes dependem do estado de forma determinística; a transição precisa ser função do tempo do mundo nos dois
  lados, para a física concordar). Um morph de componentes (amplitude), não troca instantânea.

### VIS-10 (médio): cor e opacidade não dependem da profundidade (caminho sem shaderpack)

* **Sintoma**: oceano fundo e poça rasa com a mesma cor e opacidade (0,78 por padrão, `OceanConfig.java:59`); o fundo
  aparece "por trás" da folha a qualquer profundidade. Visto: `docs/img/real/compl-costa.png` e
  `docs/img/real/bsl-costa.png` mostram o fundo em degraus por baixo (nesses o pack aplica a profundidade dele).
* **Causa**: `OceanChunks.Column.depth` é calculado (`OceanChunks.java:172`) e só alimenta o `ShoreField`
  (`:290-301`); `SurfaceShader.shade` nunca o lê.
* **Status**: **[LIDO]**.
* **Correção**: Beer-Lambert simples: opacidade e escurecimento por `1 − exp(−k·profundidade)`, com `depth` por coluna
  (já disponível por vértice via o índice de coluna).

### VIS-11 (médio): as ondas morrem muito perto no alcance do pack real

* **Sintoma**: com `renderDistance:6` (`options.txt` do TEOF), a faixa de atenuação da borda vai de 56 a 88 blocos
  da câmera (largura 32): as ondas só têm amplitude cheia nos 3,5 chunks em volta do jogador; o horizonte é plano
  (rest level) a partir de ~88 blocos. Ondas com comprimento até 151 m (moderado) mal aparecem inteiras.
* **Causa**: `EdgeFade.frame` (`EdgeFade.java:22-32`): `end = raio − 8`, `start = end − max(32, ...)` com o raio em
  blocos = `renderDistanceChunks·16`. Calculado para os presets (execução do `Probe`): rd 6 → 56–88 (tempestade 52–88);
  rd 8 → 88–120; rd 12 → 152–184.
* **Status**: **[LIDO]** + conta executada; é uma escolha documentada (junção sem degrau com o Mirai/Distant
  Horizons, `DistantHorizons.toml` está no pack), mas não foi avaliada em jogo com `rd = 6`.
* **Correção**: opções: faixa mais curta (o limite é o gradiente `1,5·M/(0,5·(1−L))`, ver o comentário da classe),
  ou desenhar a malha além do raio de chunks carregados (a folha não precisa dos blocos longe se a região é "tudo"
  e o Mirai desenha o fundo), com o mesmo recorte do `ViewCone`.

### VIS-12 (médio): nenhum efeito além da folha (espuma de costa, contato, respingos, sons, partículas)

* **Sintoma**: o mar morre mansamente na areia (lago calmo, sem arrebentação), não há espuma ao redor de pilar,
  casco, ilha; nenhum respingo, spray, esteira, som de onda batendo ou de quebra.
* **Status**: **[VISTO]**: `docs/img/ondas-costa.png` e `real/teof-sp-costa.png` (a folha encosta na areia sem
  espuma, num anel liso), `real/teof-mp-spawn-navio.png` e `real/teof-spawn-real-navio.png` (o casco corta a folha
  sem espuma ou esteira), e o pilar em `ondas-moderado-alto.png`. **[LIDO]**: a espuma vem só do jacobiano das
  cristas (`GridEvaluator.java:394-454`, `foamStart/foamFull`), o `ShoreField` só diminui amplitude
  (`ShoreField.java:1-20`), e o mod não gera partículas nem sons: `OceanChunks` registra `IWorldAccess` com
  `playSound`/`spawnParticle` vazios (`OceanChunks.java:338-344`), e a busca por `particle|sound|splash|wake` em
  `src/` só acha isso. `ARQUITETURA.md:159` e `README.md:49` listam esteiras e respingos como fase futura.
* **Correção**: campo de espuma de costa e de obstáculo no `ShoreField` (distância já é calculada);
  partículas/sons no cliente quando a folha cruza uma entidade ou a costa; esteira de casco pelo Expeditions.

### VIS-13 (médio): navio (Kunftig) e mar não se tocam visualmente

* **Sintoma**: o casco submerso é cortado por completo pela folha (sem ser visto através dos 22% de transparência),
  sem espuma, sem esteira, sem onda de proa; a folha atravessa o casco como um plano. Em `teof-spawn-real-navio.png`
  o fundo do casco e a quilha desaparecem na folha.
* **Causa**: o Kunftig desenha os navios em `RenderWorldLastEvent` (`DisplayClient.java:397`), depois do passe 1 em que
  o mar escreve profundidade (`OceanRenderer.java:312`, `glDepthMask(true)`); o que está atrás da folha falha no
  teste de profundidade e some. Não há máscara de "casco seco" por dentro: a folha continua dentro do volume do
  casco (o `CLAUDE.md` do Expeditions já trata o piso do navio como "dentro d'água se abaixo da linha d'água").
  Com shaderpack, a ordem em relação ao pós-processamento **[a verificar]**.
* **Status**: o corte do casco **[VISTO]** na captura (não dá para dizer se é "bom" ou "ruim"); o resto
  **[LIDO]**/**[DEDUÇÃO]**.
* **Correção**: desenhar o casco antes da folha (como as entidades vanilla, vistas através dela) ou aceitar o corte;
  espuma de contato via VIS-12.

### VIS-14 (médio): shaderpacks, o que está visto e o que ainda não

* **Visto** (`TESTES-AMBIENTE-REAL.md`, "Shaderpacks"; `real/bsl-*.png`, `real/compl-*.png`): fase
  `TERRAIN_TRANSLUCENT` forçada, 1 desenho por quadro, reflexo do céu, brilho do sol, refração, sombra do pilar.
* **Não visto / risco**:
  * `isEyeInWater` e a água de baixo do pack são por plano (VIS-1).
  * Lado de baixo da folha com pack (VIS-3).
  * `real/teof-bsl-depois.png`: com o BSL, quase não há mar (só fiapos azuis perto do horizonte); a documentação
    atribui a carga do contêiner (18 chunks carregados) e diz que "a imagem com o BSL não mudou". Não reproduzido.
  * Linhas tracejadas ao longe, à direita do horizonte em `real/compl-por-do-sol.png` e `real/bsl-tempestade.png`
    (pontos/traços claros e escuros). Causa não determinada (borda do mundo carregado no harness, costura de chunk
    ou borda do Mirai são as hipóteses).
  * Passagem de sombra usa uma malha grossa própria (células ≥ 4 blocos, `OceanRenderer.java:249`) e a principal usa
    1 bloco: a diferença de altura entre as duas pode dar listras de auto-sombra na própria água **[a verificar]**.
  * Custo do `Tessellator` do Angelica com pack é do PERF, mas afeta a imersão (quadros lentos aumentam os buracos
    de VIS-6).
  * Tudo medido em GL por software; **nenhuma captura é de GPU**.

### VIS-15 (médio): lacunas de cobertura do harness e dos testes

O harness (`OceanHarness.java:44-57`) cobre 10 câmeras, todas acima da folha, de dia (6000 ticks; um pôr do sol em
12600), sem chuva, sem entidades, sem jogador na água, sem navio. **Nunca testados**: câmera dentro da onda, nado,
noite fechada, chuva/relâmpago, itens e anzol na água, bolhas, `Neo States + OceanWaves` com a névoa submersa,
BSL/Complementary de baixo, chunks que chegam com nível trocado (VIS-5), falha do renderizador (VIS-4). O item 9 do
`PLANO-DE-TESTES.md:88-89` ficou como "(ainda a vanilla)".

### VIS-16 (baixo): água vanilla plana convivendo com a folha

* A regra de mar aberto é só "fonte de água parada com ar em cima no nível do mar" (`OceanColumns.java:24-34`).
  A água destes casos fica **fora da folha** e continua sendo a face plana vanilla a 8/9: água de laje/escada/cerca encharcada do Neo States
  (`MixinRenderBlocks_Water`: desenhada pelo `renderBlockLiquid` vanilla, e a face de cima só some se a célula é
  `Blocks.water`), água corrente (`meta != 0`, foz de rio), coluna de bolhas do Et Futurum
  (`etfuturum/blocksitems.cfg:122 enableBubbleColumns=true`), e qualquer coisa que cubra a água (vitória-régia).
  A atenuação da costa achata o mar em volta (10 blocos), então o degrau é pequeno, mas o *aspecto* difere (a água
  vanilla não tem Fresnel nem reflexo do céu) **[a verificar]** no cais do spawn.
* A mesma regra pega **qualquer** água parada em `y = seaLevel − 1`: rios e lagos no nível do mar e piscinas de
  base ganham a marulhada do oceano (reduzida só pela distância à costa e pela profundidade). Só importa fora do
  `Akashic/Spawn` (a configuração padrão limita a ondas a esse mundo).

### VIS-17 (baixo): relógio do mar no jogo solo com pausa

`OceanClock.ticksAt` extrapola sem limite sem novos pacotes (`OceanClock.java:76-84`); na pausa do jogo solo o
servidor integrado não manda `S03`, as ondas continuam andando com o mundo parado, e ao voltar `observe` vê erro
grande e salta para trás (`:39-41`, `snap`). Visível como um "salto" no mar depois de uma pausa longa. **[LIDO]**;
no pack real (multijogador) não ocorre.

### VIS-18 (baixo): especular facetado e relevo exagerado

O brilho Blinn-Phong tem expoente 128 (`SurfaceShader.java:154-156`) e é calculado por vértice (malha de 1 bloco) e
interpolado em Gouraud; a mancha de sol sai como um retângulo/trapézio de pixels em vez de um caminho de brilhos
(**[VISTO]**: `ondas-moderado-alto.png` embaixo à esquerda, `real/teof-spawn-real-navio.png` embaixo). E a normal da
iluminação tem a inclinação multiplicada por `relief = 2,0` (`OceanConfig.java:63`, `SurfaceShader.java:120`): decisão
estética declarada, só para o caminho sem shader.

### VIS-19 (baixo): borda do Mirai e do recorte

* O horizonte do Mirai (água distante) é uma faixa de cor e brilho diferentes da água perto, mesmo sem o mod
  (**[VISTO]** em `real/teof-sp-agua-neostates.png`, a faixa escura do horizonte); a junção do OceanWaves com ela é
  só por altura, não por cor.
* A caixa de recorte do chunk tem 4 blocos de folga (`OceanRenderer.java:215-216`) e o deslocamento horizontal
  máximo da tempestade é 4,56 m (conta do `Probe`): uma fatia de 0,56 m nas bordas da tela pode ser recortada cedo.
  **[DEDUÇÃO]**, imperceptível a olho.

---

## O que está bem (verificado)

* A face de cima vanilla some na montagem do Angelica/Celeritas, sem plano chato nos vales (**[VISTO]**
  `PLANO-DE-TESTES.md`, `TESTES-AMBIENTE-REAL.md`, `teof-sp-tempestade.png`).
* Frestas: a de borda de chunk (folga da costa) foi achada no ambiente real e corrigida
  (`teof-mp-fresta-antes.png` → `teof-mp-tempestade.png`); a das junções em T tem teste
  (`neighboursOfDifferentResolutionShareTheirEdge`, `stitchingClosesTJunctions`).
* LOD: o peso espectral é função só de posição/câmera, e com `lodFactor 0,02` e `renderDistance 6` o espaçamento
  de grade é 1 bloco em todo o alcance (`OceanMesher.spacingFor`: `want = 0,02·96 = 1,92`, `s = 1`), então não há
  salto de LOD visível; o custo disso é do PERF.
* Costa: a folha encosta na areia no nível de repouso, sem fresta (**[VISTO]** `ondas-costa.png`,
  `teof-sp-costa.png`).
* Cor do Neo States: a textura cinza dele e a cor de bioma (`WaterTexture.stillIcon`, `GrayWater.icon`) corrigiram a
  água "azul ao quadrado" (**[VISTO]** `neostates-mar-tres.jpg`, com as médias de pixel no texto).
* Compatibilidade de mixin com o Neo States: o do OceanWaves devolve falso no início de `shouldSideBeRendered`
  (`BlockLiquidTopFaceMixin.java:21-27`) e o do Neo States é `@ModifyReturnValue` que respeita `!original`
  (`MixinBlockLiquid_WaterFaces.java:26-34`): sem conflito.
* Shaderpack: fase e material da água por reflexão (`IrisBridge.java:89-109`), `ViewCone`, passagem de sombra.

## Dados do ambiente real que importam para a imersão

* `options.txt` (cliente TEOF): `renderDistance:6`, `mipmapLevels:0`, `anisotropicFiltering:1`, `particles:0` (todas),
  `gamma:0.06`, `fancyGraphics:true`, `clouds:false`, `ao:2`.
* Mods que tocam água, névoa e som no mesmo espaço: Neo States alpha.10 (`modernWaterFog`, `modernWaterPhysics`,
  `waterlogging`, `floatingItems` todos ligados), Dynamic Surroundings 1.0.6.4 (`FogEffectHandler`, respingo de
  chuva, sons submersos), Et Futurum (colunas de bolhas, `heavyWaterSplashing`), Mirai/Distant Horizons, Angelica
  2.2.43, `Akashic-ThePatcher` (`NeoStatesWaterPassHooks`).
* Shaderpacks no pack: BSL v8.2.09 e Complementary Unbound r5.9.3.

## Roteiro para confirmar as deduções em jogo (com GPU)

1. VIS-1/3: nadar na superfície de `/ondas estado moderado` e `tempestade`; filmar a tela por 30 s; anotar quando a
   névoa e a sobreposição ligam em relação à folha. F3 `[Ondas]` + coordenadas dos olhos contra `/ondas aqui` (y da
   folha). Repetir com BSL e Complementary (`isEyeInWater`).
2. VIS-2: jogar um item e o anzol (AkashicFishing) no mar de Hs ≥ 1,2 m, observar o mergulho/pairada; ouvir o
   respingo ao entrar.
3. VIS-4: forçar uma exceção no desenho (um `throw` de teste atrás de uma propriedade) e ver o mar.
4. VIS-5: servidor com `Akashic/Spawn` sem `@`, jogador entrando antes de os chunks do spawn carregarem.
5. VIS-7: câmera em movimento a 60 blocos, comparando com mipmap ligado.
6. VIS-8: `/weather thunder` e `/effect @p night_vision`, de noite.

## Perguntas em aberto

* A sobreposição azul da tela (`ItemRenderer.renderOverlays`) usa mesmo `isInsideOfMaterial(water)`? O
  `VANILLA-1.7.10.md` do Neo States a deixa como "a verificar".
* O Kunftig e o Angelica com shaderpack: o navio é desenhado antes ou depois do composite? Isso decide se o casco e a
  água dos packs se encontram bem (VIS-13/14).
* As linhas tracejadas ao longe nas capturas dos packs: borda do harness ou defeito (VIS-14)?
* O estágio do Neo States (VIS-1) precisa de uma consulta absoluta por coluna: quem implementa, o Neo States ou o
  OceanWaves, e como a física do servidor e o cliente concordam no tempo (tick x quadro)?
