# Auditoria PERF: por que o FPS cai com o OceanWaves ligado

Frente PERF da auditoria (briefing em `/home/user/work/briefing.md`). Nada nos repositórios foi alterado. Os benches
que escrevi ficam em `/home/user/work/tmp/perf/bench/` (`PerfBench.java`, `LoopBench.java`); a saída do
`:core:bench` está em `/home/user/work/tmp/perf/bench2.log`.

**Nenhum número de FPS aqui foi medido em jogo.** Os tempos vêm de três fontes, sempre identificadas:
(a) **medido agora** neste contêiner (Xeon 2,3 GHz, 4 núcleos, sem GPU), fora do Minecraft;
(b) **dos documentos do repositório** (medições anteriores com o harness, GL por software);
(c) **estimativa**, com a conta mostrada.

---

## 1. Resumo

* O mar é **refeito do zero em todo quadro, na thread de desenho**: para cada chunk visível, a soma de ~45 a 53
  componentes em cada vértice da grade (mais uma borda), a cor de cada vértice, a duplicação em quads (3,5 vértices
  emitidos por vértice da grade), um buffer intermediário e depois, vértice a vértice, o `Tessellator`
  (`OceanRenderer.draw`, `OceanRenderer.java:157-289`; `submit`, `:300-363`). Não há cache entre quadros, nem
  trabalho em outra thread.
* Custo por quadro com distância 8, mar "moderado", sem shaderpack (78 chunks, 17.550 vértices de grade, 15.360
  quads = 61.440 vértices no `Tessellator`):
  * avaliação da grade: **2,3 a 3,0 ms** (medido agora), dos quais ~0,4 ms são a atenuação de costa pedida vértice a
    vértice mesmo em mar aberto;
  * cor (`SurfaceShader`): **~1,1 a 1,3 ms** (medido agora, réplica da conta);
  * costura + emissão no `FloatBuffer`: **~0,4 a 0,6 ms** (medido agora);
  * envio pelo `Tessellator`: 0,8 a 1,2 ms só para empacotar os bytes (emulado agora), **3,5 ms no `Tessellator`
    vanilla e 9 a 11 ms no do Angelica** (docs: `PLANO-DE-TESTES.md:41`, `TESTES-AMBIENTE-REAL.md:60-62`);
  * manutenção do cache de chunks por quadro: estimada em 0,3 a 0,8 ms (releitura de um chunk inteiro por quadro,
    com `colorMultiplier`) e **picos** de 0,3 ms por costa refeita, sem orçamento.
* Total na thread principal: **~8 a 16 ms por quadro** (bate com os 8 a 10 ms do harness de desenvolvimento e os
  6 a 20 ms do cliente TEOF real nos docs). Com shaderpack o envio fica dezenas de vezes mais caro
  (`ADR-0004:43`: ~160 a 230 ms por 18 mil quads em GL por software) e a passagem de sombra repete tudo.
* **A meta de ≤ 10% de queda** exige ~1 a 1,8 ms por quadro (seção 6). Nenhuma otimização local chega lá sozinha.
  O caminho realista combina: tirar a avaliação e a cor da thread principal (threads de trabalho), **sair do
  `Tessellator` no modo sem shaderpack** (buffer próprio indexado: 4× menos vértices e nenhuma chamada por vértice;
  isso esbarra numa regra do `CLAUDE.md` e na ADR-0003, ver seção 7), e cortes baratos (costa uniforme, manutenção
  por tick, orçamentos, LOD temporal). Só o `Tessellator` do Angelica já custa 5 a 10 vezes o orçamento.

---

## 2. O caminho por quadro e por tick (lido)

### 2.1 Por tick (20 Hz), thread principal

* `ClientProxy.onClientTick` → `ClientSea.tick` (`ClientSea.java:108-149`): troca de mundo, mensagem do servidor,
  liga/desliga. Barato. Quando `active` ou a região mudam: `mc.renderGlobal.loadRenderers()`
  (`ClientSea.java:145-147`, `:158-160`) remonta **todos** os chunks do terreno: pico único de segundos com 215 mods
  (estimativa; acontece ao entrar no mundo e ao trocar de região).
* Nada do mar é feito por tick: tudo o que poderia ser por tick (releitura de chunks, luz) roda por quadro.

### 2.2 Por quadro, thread principal (`RenderGlobal.sortAndRender` passe 1 → `OceanRenderer.render`)

`RenderGlobalOceanPassMixin.java:73-79` chama `OceanRenderer.render` no RETURN do passe 1. Em `draw`
(`OceanRenderer.java:157-289`):

| Etapa | Linha | Custo |
|---|---|---|
| `chunks.attach`, versão do mar, `prune` (itera o mapa) | 164-179 | desprezível |
| `new Frustrum()`; espiral do raio; por chunk: `chunks.get` (HashMap<Long> com `computeIfAbsent`: boxing + lambda por chunk), `arrived` (busca no mapa de chunks), `AxisAlignedBB.getBoundingBox` (novo objeto no 1.7.10), teste de frustum ou cone | 180-228 | pequeno em tempo, ~10 KB de lixo por quadro (estimativa: 213 chunks × ~50 B) |
| releitura de até `rebuildsPerFrame` = 6 chunks sujos, sem orçamento para os 6 primeiros e para os 3×3 da câmera | 203-210 | 256 colunas × (≈24 + profundidade) chamadas ao mundo por chunk; ver 4.4 |
| `visible.sort` (lambda capturando a câmera) | 230 | desprezível (a espiral já vem ordenada) |
| **costa refeita para todo chunk visível com `shoreDirty`, sem orçamento** | 231-235 | 0,23 a 0,48 ms por chunk (medido); ver 4.3 |
| **releitura de 1 chunk inteiro + luz de 2 chunks, em todo quadro** | 237-247 | estimativa 0,3 a 0,8 ms; ver 4.4 |
| `new SpectralLod`, `beginFrame` (fasores do quadro: 64 × (1 + 2×⌈w/16⌉) senos/cossenos) | 251-253 | ~0,05 ms (estimativa: ~320 pares sen+cos) |
| `shader.frame` (céu, névoa, sol do mundo; aloca `Vec3`) e `edge.frame` | 254-255 | desprezível |
| `new int[visible.size()+1]`; por chunk: 5 × `spacingFor`, `shader.chunk`, `edge.around`, `mesher.mesh` | 257-270 | **o grosso: avaliação + cor + emissão** (seção 3) |
| `submit`: estado GL (3 `glGet*`/`glIsEnabled`), textura, por vértice `setColorRGBA`, `setBrightness` (só com shaderpack), `setNormal` (sempre), `addVertexWithUV`; `glGetInteger(GL_CURRENT_PROGRAM)` e `IrisBridge.phase()` (reflexão) em todo quadro; `tess.draw()` | 300-363 | **3,5 a 11 ms** (docs) |

Com shaderpack e `desenho.sombraDaAgua = true` (o padrão, `OceanConfig.java:65`), `render` roda de novo na passagem
de sombra (`OceanRenderer.java:128-136`) com células ≥ 4 blocos e **sem recorte** (`:220-221`): repete também a
manutenção (releitura, costa, luz, ordenação) e o `beginFrame`. O campo `frame`/`newFrame()`
(`OceanRenderer.java:149-155`) é incrementado e **nunca lido**: se o passe 1 for chamado mais de uma vez no mesmo
quadro (outro mod, anáglifo), tudo é recalculado **[a verificar no TEOF]**.

### 2.3 Threads de montagem de chunks (Angelica/Celeritas)

`BlockLiquidTopFaceMixin.java:21-27` → `ClientSea.hideTopFace` (`ClientSea.java:98-100`): só para `side == 1`;
`active` (volátil), comparação de y, região, `OceanColumns.isOpenSea` (3 leituras do `IBlockAccess` recebido,
`OceanColumns.java:176-186`). Custo desprezível diante da montagem; não toca no mundo nem aloca. Efeito colateral bom:
a face de cima da água some e a montagem translúcida do Celeritas fica menor. O único custo real ligado a ele é o
`loadRenderers()` de 2.1.

---

## 3. Medições (sem jogo)

### 3.1 `./gradlew :core:bench` (rodado por mim, saída em `/home/user/work/tmp/perf/bench2.log`)

JVM do daemon: Java 25 (`gradle/gradle-daemon-jvm.properties`); não há JDK 8 neste contêiner (o
`~/.gradle/jdks/temurin-8-*` citado no `CLAUDE.md` não existe), então **nada foi medido no Java 8 dos clientes**.
Preset "fresco", 64 componentes:

| Rodada | grade 17×17 (`evaluate`, 8 somas) | 33×33 | consulta pontual fria / quente | cena de 441 chunks, raio 12, 28.505 vértices (`evaluateFast` + tabelas do quadro) |
|---|---|---|---|---|
| 1 (JIT frio) | 88,9 µs | 241,7 µs | 5,44 / 3,66 µs | 168,7 ms |
| 2 | 56,1 µs | 195,5 µs | 5,27 / 3,62 µs | **13,1 ms** |
| 3 | 54,8 µs | 191,5 µs | 5,18 / 3,52 µs | **18,6 ms** |

Os docs (`ARQUITETURA.md:135`) dão ~11 ms para a mesma cena. A cena do bench não recorta pelo frustum (é o pior
caso: F5, ou passagem de sombra) e mede só a avaliação, sem cor nem envio.

### 3.2 `PerfBench` (meu; reproduz `OceanRenderer.draw` sem o Minecraft)

Usa as classes do `core` compiladas, a mesma ordem de chamadas do renderizador (espaçamento por `spacingFor`,
vizinhos para a costura, `mesh` com `Shade`), uma réplica da conta do `SurfaceShader` (modo `own`), costa como o
renderizador a recebe (um `ShoreField` por chunk, folga = `influenceRadius`) e um `Tessellator` emulado (8 ints por
vértice, como o vanilla, sem os mixins do Angelica). "frustum" = chunks num leque de ±62° mais os 3×3 da câmera.
Java 21, 400 quadros, média dos 200 últimos. **Validação**: com distância 8 dá 78 chunks e 15.360 quads, e o harness
do repositório mediu 75 a 98 chunks e 15.850 a 20.722 quads (`PLANO-DE-TESTES.md:41-50`); "malha completa" dá 4,3 a
4,8 ms contra "Malha + cor" 4,7 a 6,6 ms no harness. A reprodução é fiel.

| Caso | chunks | verts de grade | comp. médias/chunk | quads | avaliação com costa | sem costa | malha completa (aval + costura + cor + emissão) | malha sem cor |
|---|---|---|---|---|---|---|---|---|
| moderado, r 6 | 46 | 13.294 | 52,6 | 11.776 | 2,1 a 2,2 ms | 1,8 | 3,3 a 3,4 | 2,35 |
| **moderado, r 8** | 78 | 17.550 | 49,8 | 15.360 | **2,7 a 3,0** | 2,3 a 2,5 | **4,3 a 4,8** | 3,2 a 3,5 |
| moderado, r 12 | 162 | 24.354 | 45,1 | 20.736 | 3,9 | 3,3 a 3,4 | 6,4 a 6,5 | 4,9 |
| moderado, r 16 | 282 | 28.922 | 41,4 | 24.000 | 5,0 | 4,2 | 7,6 | — |
| moderado, r 8, **sem recorte** (F5 / sombra) | 213 | 47.413 | 49,4 | 41.472 | 7,6 | 6,5 | 12,8 | — |
| tempestade (80 comp.), r 8 | 78 | 17.550 | 64,6 | 15.360 | 3,6 | 3,2 | 5,6 | — |
| calmo, r 8 | 78 | 17.550 | 31,0 | 15.360 | 2,6 | 2,2 | 4,2 | — |
| moderado, r 8, `espacamento` 0,5 | 78 | 34.350 | 51,4 | 31.488 | 5,1 | 4,5 | 8,6 | — |
| moderado, r 8, `detalhe` 0,03 | 78 | 12.142 | 45,5 | 10.368 | 2,0 | 1,7 | 3,0 | 2,2 |
| moderado, r 8, `detalhe` 0,04 | 78 | 9.342 | 42,4 | 7.872 | 1,8 | 1,6 | 2,7 | — |
| moderado, r 8, `espacamento` 2 | 78 | 6.318 | 45,7 | 4.992 | 1,1 | 1,0 | 1,7 | 1,2 |
| sombra: r 8, células ≥ 4, sem recorte | 213 | 5.325 | 37,0 | 3.408 | 1,4 | 1,2 | 1,9 | 1,5 |

Outras medidas:
* **`Tessellator` emulado** (só empacotar 8 ints por vértice): 0,8 a 1,2 ms para 47 a 83 mil vértices na primeira
  série; numa segunda série, com outro buffer de 2,4 MB disputando a cache, 1,6 a 6,4 ms. Os 2,4 MB por quadro do
  `FloatBuffer` intermediário (61.440 vértices × 10 floats) mais o `rawBuffer` do `Tessellator` não cabem na cache.
  O real é mais caro: 3,5 ms no vanilla e 9 a 11 ms no Angelica (docs). Ver 4.6.
* **`ShoreField.build`** de um chunk (janela 38 × 38, folga 11 do "moderado"; 50 × 50 na tempestade): **0,23 a
  0,48 ms**, mais 5 vetores alocados (~30 KB).
* **Custo do laço interno da soma**: 1,6 ns por (vértice × componente) (`LoopBench`: 28,5 µs para 19 × 19 × 50).
  Desligar a vetorização (`-XX:-UseSuperWord`) piora só ~20%: o laço quase não é vetorizado. Uma versão com 4
  componentes por passada ficou **mais lenta** (33 µs; acesso com passo), e o laço já está perto do limite escalar.
  O ganho não está em reescrever o laço, e sim em fazer menos somas, menos vezes, ou em outra thread.
* `-XX:TieredStopAtLevel=1` (só C1): a avaliação vai de 2,7 a 4,4 ms. Um cliente com JIT frio ou pouca memória
  sente isso.

### 3.3 Modelo de custo (conferido contra as medidas)

Avaliação ≈ Σ_chunks (n+2)² × C_lod × 1,6 ns + custo fixo por chunk (fasor de cada componente, consulta de tabela).
Com r 8: 78 × 361 × 49,8 × 1,6 ns ≈ 2,2 ms (medido 2,3 a 2,5 sem costa). A borda de um vértice de cada lado custa
(19/17)² − 1 = **25%** a mais com células de 1 bloco, e (7/5)² − 1 = 96% com células de 4.
Vértices no `Tessellator` ≈ 4 × quads ≈ 3,5 × vértices de grade (cada vértice interior sai em 4 quads).
Sen/cos por quadro: só no `beginFrame` (~320 pares) e na criação de tabelas (uma vez por espaçamento). **A
trigonometria não é o problema**; não há `StrictMath` no caminho de desenho (só em `Synthesis`, `Spectrum`,
`Spreading`, `Dispersion`, que rodam ao trocar o mar).

---

## 4. Achados

### 4.1 PERF-1: o mar é refeito inteiro, todo quadro, na thread de desenho (crítico)
`OceanRenderer.java:251-270`: `beginFrame` + `mesh` de todos os chunks visíveis em todo `render`. São 4,3 a 6,5 ms
de CPU por quadro com distância 8 a 12 (medido), proporcionais ao FPS: um monitor de 144 Hz paga 2,4× o de 60.
O documento já planeja threads e LOD temporal (`ARQUITETURA.md:143-149`), mas não estão feitos. As entradas que um
trabalhador precisaria (`open`, `shore`, `WaveSet`, câmera, t) são imutáveis no quadro, **exceto** `Column.color` e
`Column.light`, que a releitura do quadro (`OceanChunks.refresh`/`refreshLight`) altera na thread principal: um
trabalhador precisaria de cópia ou de troca atômica desses vetores.

### 4.2 PERF-2: envio vértice a vértice pelo `Tessellator`, com 3,5× de duplicação (crítico)
`OceanRenderer.java:321-345` e `OceanMesher.java:113-121`: cada célula sai como quad de 4 vértices; 17.550 vértices
de grade viram 61.440 no `Tessellator` (r 8). Cada um custa 3 a 4 chamadas (`setColorRGBA`, `setNormal`,
`addVertexWithUV`, e `setBrightness` com shaderpack). No Angelica 2.2.43 o `addVertex` ganha um `@Inject` no RETURN
(`MixinTessellator#addElementState`, que adiciona a um `IntArrayList` por vértice) e um `@ModifyExpressionValue` na
cor (`MixinTessellator#angelica$blendVertexColor`) (bytecode lido); o `draw` converte quads em triângulos
(`TessellatorStreamingDrawer#drawWithQuadConversion`). Isso explica os 9 a 11 ms contra 3,5 ms do vanilla
(`TESTES-AMBIENTE-REAL.md:60-62`). Com shaderpack o formato estendido do Iris torna cada vértice ainda mais caro
(`ADR-0004:42-46`). É o maior custo isolado no cliente real. `setNormal` é chamado sempre (`:337`), mas sem shaderpack
a luz é feita na cor (`SurfaceShader`) e a normal não é usada: um atributo a mais por vértice à toa
(hipótese: no FFP do Angelica sem `GL_LIGHTING` a normal não entra na conta).

### 4.3 PERF-3: costas refeitas sem orçamento e em cascata, e pedidas vértice a vértice em mar aberto (alto)
* `OceanRenderer.java:231-235` refaz **todas** as costas sujas dos chunks visíveis no mesmo quadro. Cada leitura de
  chunk marca 5 × 5 vizinhos (`OceanChunks.markShores`, `:188-197`; chamada em `read` `:181-183` e em `refresh` se
  algo mudou `:136-139`). Durante a chegada de chunks (entrada no mundo, teleporte, navegação rápida), um chunk
  que chega custa até 25 × 0,23 a 0,48 ms ≈ **6 a 12 ms** de costa (medido × contagem); vários por quadro fazem
  picos de dezenas de ms, e o mesmo chunk é refeito de novo a cada vizinho que chega (cada vizinho desconhecido
  "conta como mar", `:279-282`).
* `rebuildShore` (`OceanChunks.java:262-302`) aloca 2 vetores de 38² a 66² (e `ShoreField.build` mais 3 e os do
  EDT, `ShoreField.java:61-62, 88, 184-185`) e faz `map.get(key(...))` **por coluna** da janela (`:277`): 1.444 a
  4.356 buscas com boxing de `Long` por costa.
* `ShoreField` não sobrescreve `uniform()` (`ShoreField.java:19`; o padrão é `this == OPEN_SEA`,
  `Attenuation.java:79-81`), então todo chunk, mesmo em alto-mar com todos os cantos = 1, vai pelo caminho não
  uniforme de `evaluateFast` (`GridEvaluator.java:379-388`): uma interpolação bilinear por vértice da grade com borda.
  Medido: **+0,4 a 0,6 ms por quadro** (r 8 a 12), ~15% da avaliação.
* O `EdgeFade` faz o mesmo (com `sqrt`) nos chunks da faixa de borda (`EdgeFade.java:217-247`): com distância 6 a
  faixa começa a ~56 blocos, então boa parte dos chunks visíveis (custo pequeno, sem medida própria).

### 4.4 PERF-4: releitura de um chunk inteiro em todo quadro, com cor de bioma (médio, estimado)
`OceanRenderer.java:237-247`: em **todo quadro** (não por tick), `refresh` de 1 chunk e `refreshLight` de 2.
`refresh` → `read(c, false)` (`OceanChunks.java:147-185`): por coluna aberta, `isOpenSea` (3 leituras), `depth` (desce
até 64 blocos: ~20 a 25 leituras num oceano comum, `OceanColumns.java:189-197`), `Blocks.water.colorMultiplier`
(no 1.7.10, 3 × 3 biomas e um `BiomeEvent.GetWaterColor` postado por bioma; o Neo States ouve esse evento,
`WaterColorHandler#onWaterColor`, bytecode lido) e a luz. Estimativa: ~256 × (24 + 20) ≈ 11 mil chamadas ao mundo e
~2.300 eventos alocados por quadro, **~0,3 a 0,8 ms** (estimativa de 20 a 40 ns por leitura e 100 a 200 ns por
evento; não medido). A cor do bioma e a profundidade não mudam de um quadro para outro; a luz muda devagar. O código
diz "a luz muda devagar: alguns chunks por quadro", mas faz isso por quadro e não por tempo.

### 4.5 PERF-5: com shaderpack, envio caríssimo e a passagem de sombra repete tudo (alto quando há shaderpack)
* `ADR-0004:42-46` mediu ~160 a 230 ms por ~18 mil quads com shaderpack (GL por software). Mesmo que numa GPU seja
  uma fração, o custo é por vértice na CPU (formato estendido do Iris montado em Java) **[a verificar numa GPU]**.
  `minSpacing` é o mesmo com e sem shaderpack (`OceanRenderer.java:249`).
* Passagem de sombra (padrão ligada, `OceanConfig.java:65`): 213 chunks sem recorte em r 8, 3.408 quads (medido),
  ~1,9 ms de malha mais o envio no formato do Iris, **e** de novo a releitura de 1+2 chunks, as costas, a ordenação, o
  `beginFrame` e o `shader.frame` (`OceanRenderer.java:164-255` não distinguem a sombra).
* `ShaderPacks.renderingShadowPass()` e `IrisBridge.begin/end` usam `Method.invoke` por quadro: desprezível.
* O TEOF vem **sem** shaderpack ligado (`config/shaders.properties`: `shaderPack=` vazio; BSL e Complementary estão
  em `shaderpacks/`): o caso comum é o sem shader.

### 4.6 PERF-6: buffer intermediário de 2,4 MB por quadro (médio)
`OceanMesher.vertex/lerp` (`OceanMesher.java:196-244`) escreve 10 floats por vértice **emitido** (já duplicado 3,5×)
no `FloatBuffer`, que `submit` relê para o `Tessellator`. Com r 8 são 2,4 MB escritos e lidos por quadro, mais o
`rawBuffer` do `Tessellator`: tráfego de memória que não cabe na cache (a variação de 0,8 para 1,6 a 6,4 ms do envio
emulado quando outro buffer disputa a cache mostra a sensibilidade). Mesclar malha e envio por chunk (ou usar índices)
corta isso.

### 4.7 PERF-7: alocações por quadro no caminho quente (baixo em ms, viola a regra do `CLAUDE.md`)
Lixo por quadro (estimativa total de 15 a 50 KB, mais em picos de costa):
* `new Frustrum()` (`OceanRenderer.java:180`), `AxisAlignedBB` por chunk testado (`:215-216`; no 1.7.10 não há
  pool), lambda do `sort` (`:230`), `new SpectralLod` (`:251`), `new int[]` (`:257`);
* `OceanChunks.get`: `computeIfAbsent` com `Long` encaixotado e lambda capturando `cx, cz` por chunk da espiral
  (`OceanChunks.java:79-81`); `map.get(key(...))` com boxing em `markShores` e em `rebuildShore` (por coluna!);
* `GridEvaluator.table`: chave `Long` encaixotada por chamada de `evaluateFast` (`GridEvaluator.java:143-144`);
* `refresh`: ~2.300 `GetWaterColor` por quadro (4.4);
* `WaterTexture.luminance` aloca `float[n]` + `int[n]` a cada quadro da animação da água
  (`WaterTexture.java:271, 284, 303`), ~10 vezes por segundo;
* `IrisBridge.phase()` em todo quadro (`OceanRenderer.java:344`, só diagnóstico): reflexão; **sem o Angelica**
  (`Class.forName` falha) lança e engole uma `ClassNotFoundException` por quadro (`IrisBridge.java:78-85`, porque
  `currentPhase` fica nulo).
O coletor do 1.7.10 com 215 mods já gera muito mais lixo que isso: o ganho em ms é pequeno, mas a regra "nada
alocado nos laços quentes" não é cumprida.

### 4.8 PERF-8: consultas de estado GL por quadro (baixo; médio sem Angelica)
`OceanRenderer.java:303-305` (`glGetBoolean(DEPTH_WRITEMASK)`, `glIsEnabled` × 2) e `:343`
(`glGetInteger(GL_CURRENT_PROGRAM)`, diagnóstico). O Angelica tem versões em cache
(`GLSMEnable#glIsEnabled`, `GLSMQueries#glGetBoolean/glGetInteger`, bytecode lido), então ali é barato (hipótese de que
cobrem esses enums). Sem o Angelica (o modo de compatibilidade da ADR-0003), `glGet*` pode sincronizar a fila do
driver (alguns drivers com thread própria esperam a GPU): de décimos a alguns ms **[hipótese]**.

### 4.9 PERF-9: borda e arestas avaliadas em dobro (médio)
`evaluateFast` avalia (n+2)² vértices para dar n² (`GridEvaluator.java:300`), e chunks vizinhos do mesmo espaçamento
avaliam a aresta comum duas vezes. Com células de 1 bloco: 19² = 361 por chunk contra 16² = 256 únicos, ou seja
**41% de somas a mais** que o necessário (29% do total, ~0,7 ms de 2,3 em r 8). Uma grade alinhada ao mundo por anel de LOD (estilo clipmap),
avaliada uma vez e lida pelos chunks, tira isso. Ela precisa manter o peso do LOD bilinear por chunk, como hoje, para
não abrir fresta (`neighbouringChunksAgreeOnTheirEdge`, `neighboursOfDifferentResolutionShareTheirEdge`).

### 4.10 PERF-10: cor calculada por vértice na CPU em todo quadro (médio)
`SurfaceShader.shade` (`SurfaceShader.java:103-177`): 3 a 4 `sqrt` e ~80 operações por vértice de grade, ~65 ns
(1,1 a 1,3 ms por 17.550 vértices, medido pela diferença com e sem cor). A cor depende da câmera e do sol, mas pouco
de um quadro para o próximo: pode ir junto com a avaliação para um trabalhador ou para o LOD temporal.

### 4.11 PERF-11: `loadRenderers` ao ligar ou trocar de região (médio, pico único)
`ClientSea.java:141-148, 152-161`: remonta todos os chunks do terreno. Necessário para esconder ou mostrar a face
vanilla, mas acontece em toda entrada no mundo e mudança de região. Com 215 mods e o Celeritas é um engasgo visível
de segundos (estimativa). Dá para restringir a remontagem aos chunks da região antiga ∪ nova, ou adiar até a
primeira mensagem do servidor (evita dois `loadRenderers` seguidos: um pelo `active` e outro pela região).

### 4.12 PERF-12: física (TPS, não FPS): `HeightPatch` paga normais e espuma que não usa (baixo)
`HeightPatch.update` (`HeightPatch.java:42-73`) chama `evaluateFast`, que calcula normais e espuma por diferenças
centrais (`GridEvaluator.java:394-413`) e a borda de 25%, e usa só x, y e z. Também chama `phasor` sem `beginFrame`
(um sen+cos por componente e ladrilho). Medido nos docs: 1,7 ms por casco por tick no servidor com JIT frio
(`TESTES-AMBIENTE-REAL.md:42`). Uma variante "só posições" e o reuso da grade entre subpassos ajudam quando houver
vários navios. Fica para a frente de física; aqui só registro.

---

## 5. O que não é problema (verificado)

* Trigonometria: nenhum sen/cos por vértice nem por chunk no caminho de desenho (tabelas por espaçamento, fasores do
  quadro, `GridEvaluator.java:44-125, 142-163`); o resto de `phasor` só calcula seno quando θ ≠ 0, e na origem de um
  chunk o resto é zero.
* `StrictMath` só na síntese; o desenho usa `Math`.
* Um único `tess.draw()` por quadro (uma chamada de desenho para todo o mar).
* O mixin da face de cima nas threads do Celeritas: barato e sem alocação.
* `prune`, `arrived`, `spacingFor`, a contagem de colunas em `mesh`: desprezíveis.
* O laço interno da soma está perto do limite escalar (3.2): reescrevê-lo rende pouco.

---

## 6. A meta de ≤ 10% de queda, em números

Queda de FPS ≤ 10% ⇔ custo extra por quadro ≤ T/9, com T o tempo de quadro sem o mar.

| FPS de base (sem o mar) | T | orçamento do mar |
|---|---|---|
| 144 | 6,9 ms | 0,77 ms |
| 100 | 10 ms | 1,1 ms |
| 60 | 16,7 ms | 1,85 ms |
| 40 | 25 ms | 2,8 ms |

Hoje (distância 8, sem shaderpack): ~4,5 ms de malha e cor + 3,5 a 11 ms de envio + 0,3 a 0,8 ms de manutenção ≈
**8 a 16 ms**, ou seja 4 a 9 vezes o orçamento a 60 FPS. O TEOF vem com `maxFps:60` e `enableVsync:true`
(`options.txt:12, 28`) e distância 6 (`options.txt:6`). Com vsync a queda só aparece quando o quadro passa de
16,7 ms; para medir a meta é preciso desligar o vsync e o limite.

Para caber em ~1 a 1,8 ms na thread principal, as contas indicam:
1. a malha e a cor fora da thread principal (≈ 0 na thread principal);
2. o envio fora do `Tessellator` no modo sem shaderpack: um buffer indexado com 17,5 mil vértices × ~24 bytes ≈
   420 KB por quadro copiados de uma vez, em vez de 61 mil vértices em chamadas: ≈ 0,1 a 0,4 ms (estimativa);
3. a manutenção por tick e com orçamento (≈ 0,05 ms por quadro).

**Mantendo o `Tessellator`** (regra atual), o envio sozinho no Angelica (9 a 11 ms nos docs, com GL por software)
passa do orçamento; mesmo cortado ~40 a 55% (faixas de triângulos, sem normal) fica em ~5 ms. **A meta não é
alcançável com o envio pelo `Tessellator` em distância ≥ 6** (inferência das medidas dos docs; numa GPU o custo de
CPU do `Tessellator` deve continuar perto disso, porque é CPU: **[a verificar]**). Com shaderpack, só reduzindo muito
os vértices (`espacamento` 2 ou 4) e desligando a sombra da água.

---

## 7. Otimizações, por ganho estimado e risco

Ganhos para distância 8, "moderado", sem shaderpack, salvo indicação.

| # | Otimização | Ganho estimado na thread principal | Risco | Observações |
|---|---|---|---|---|
| O1 | **Envio por buffer próprio indexado** (VBO com só posição, cor e UV; índices fixos por resolução de grade; quads de costa num pedaço à parte) no modo sem shaderpack; `Tessellator` mantido com shaderpack | **−3 a −10 ms** (de 3,5 a 11 ms para ~0,2 a 0,4) e −72% de vértices (289 por chunk em vez de 1.024) | alto (GL sob o Angelica; contraria a regra "sempre pelo `Tessellator`" do `CLAUDE.md` e a ADR-0003) | Precedente no ecossistema: o Kunftig desenha a malha compilada por VBO GL15 + `glInterleavedArrays` + `glDrawArrays(GL_QUADS)` sob o Angelica (`Kunftig/.../DisplayRenderer.java:335-337, 441-446`), mas o próprio Kunftig marca isso "a verificar" numa GPU sob o Angelica. Precisa de decisão do dono (nova ADR). Guardar e devolver o estado de cliente (arrays, buffer ligado) |
| O2 | **Threads de trabalho para avaliação + cor + emissão** (uma instância de `GridEvaluator`/`OceanMesher`/`SurfaceShader` por thread, como manda o `CLAUDE.md`): ou dividir os chunks do quadro entre 2 ou 3 trabalhadores e esperar (fork-join), ou calcular o quadro seguinte no t previsto (pipeline) | fork-join com 3 threads: −3 ms (4,5 → ~1,5); pipeline: −4,5 ms (→ ~0) | médio (sincronização; `Column.color/light` precisam de cópia ou troca atômica; disputa com as threads de montagem do Celeritas) | O plano está nos docs (`ARQUITETURA.md:143-145`). O t previsto no pipeline vem do mesmo relógio (tempo total do mundo / 20): não fere o determinismo |
| O3 | **LOD temporal**: chunks com espaçamento ≥ 2 (ou todos além de ~48 blocos) refeitos a cada 2 ou 3 quadros, ou a 20 Hz com interpolação no tempo dos vértices guardados | −30 a −60% da malha dos chunks afetados (−1 a −2,5 ms) | baixo a médio (memória para 2 conjuntos por chunk; troca de espaçamento força refazer) | Erro da interpolação linear em 50 ms para T = 1 s: ~(ωΔt)²/8 ≈ 1,2% da amplitude (estimativa) |
| O4 | **Costa uniforme**: `ShoreField` com todos os cantos = 1 devolve `uniform() == true`; e quando a janela inteira é mar aberto com profundidade ≥ Hs/γ, nem calcular (usar `null`/`OPEN_SEA`) | −0,4 a −0,6 ms por quadro (medido) e fim da maior parte dos picos de costa em alto-mar | baixo | Para a borda entre um chunk uniforme e um calculado continuar exata, arredondar para 1,0f os valores ≥ 1 − 1e-6 em `ShoreField.build` (o par log/exp pode dar 0,99999994). Rodar `neighbouringChunksAgreeOnTheirEdge` e `shoreNeverFoldsTheSurface` |
| O5 | **Orçamento e coalescência das costas**: refazer do mais perto para o mais longe com orçamento de tempo; não refazer enquanto vizinhos da folga estão sujos ou não chegaram (exceto os 3×3 da câmera); ler a coluna vizinha por chunk e não por coluna (`OceanChunks.java:277`); reutilizar os vetores | elimina picos de 6 a 12 ms por chunk que chega (contagem × medida) | baixo | Também tira o boxing por coluna |
| O6 | **Manutenção por tick**: `refresh`/`refreshLight` no tick do cliente (ou por tempo), e `refresh` sem `colorMultiplier`/`depth` quando a máscara `open` não mudou | −0,3 a −0,8 ms por quadro (estimativa, 4.4) | baixo | Coerente com "O cache confere por conta própria se um chunk chegou e relê em rodízio" (`CLAUDE.md`): o rodízio continua, só que a 20 Hz |
| O7 | **Configuração com shaderpack**: `espacamento` efetivo 2 com shaderpack (o pack põe o detalhe por pixel) e `sombraDaAgua` desligada por padrão (ou células ≥ 8 na sombra), e sem a manutenção na passagem de sombra | malha: 4,5 → 1,7 ms (medido, `espacamento` 2); vértices no `Tessellator` do Iris: −68%; sombra: −100% ou −75% | baixo (visual) | `ADR-0004:44-46` já aponta "células maiores perto da câmera com shaderpack" |
| O8 | **Dentro do `Tessellator`** (se O1 for recusado): não chamar `setNormal` sem shaderpack; `GL_TRIANGLE_STRIP` por linha da grade sem shaderpack (574 vértices por chunk em vez de 1.024, −44%); emitir direto da grade para o `Tessellator`, sem o `FloatBuffer` | −40 a −55% do envio (de 9 a 11 para ~5 ms no Angelica, estimativa) e −2,4 MB de tráfego por quadro | médio (as faixas dependem de como o Angelica trata outros modos de desenho além de quads: `TessellatorStreamingDrawer` converte quads; faixas **[a verificar]**; com shaderpack manter quads) | Junções em T e costa continuam como estão (os quads de costa saem à parte) |
| O9 | **`detalhe` (lodFactor) 0,03** em vez de 0,02 | malha 4,5 → 3,0 ms (−33%), quads −32% (medido); com 0,04: −40%/−49% | baixo (visual: células de 2 blocos a partir de ~67 blocos em vez de 100) | Conferir de perto e de longe com capturas antes de mudar o padrão |
| O10 | **Grade compartilhada por anel de LOD** (sem borda e aresta repetidas) | até −29% das somas com células de 1 bloco (−0,7 ms em r 8) | médio (manter o peso do LOD bilinear por chunk; rodar os testes de fresta) | Combina bem com O2/O3 |
| O11 | **Limpeza de alocações e de estado GL**: frustrum e caixa reaproveitados (`Frustrum#isBoxInFrustum` com doubles), mapa de chunks com chave `long` primitiva ou anel indexado pelo deslocamento, comparador fixo, `SpectralLod`/`starts` reaproveitados, chave primitiva em `GridEvaluator.table`, buffers de `WaterTexture` reaproveitados, `glGetInteger(CURRENT_PROGRAM)`/`IrisBridge.phase()` só com o harness, e `IrisBridge.phase` sem lançar exceção quando não há Iris | < 0,1 ms; fim do lixo por quadro | baixo | Cumprir a regra do `CLAUDE.md` |
| O12 | Reaproveitar a malha quando o passe 1 roda de novo no mesmo quadro (usar o `frame` que já existe e não é lido) | 100% do custo de cada chamada repetida | baixo | Só vale se o TEOF chamar o passe 1 mais de uma vez por quadro **[a verificar]** |
| O13 | `loadRenderers` só nos chunks afetados (região antiga ∪ nova) e um só por troca | pico único de segundos → fração | médio | `markBlockRangeForRenderUpdate` não chega com o Celeritas (`CLAUDE.md`): o caminho de remontagem parcial precisa ser visto no Angelica |
| O14 | Camada GPU (vertex shader com as mesmas componentes) sem shaderpack | todo o custo de CPU por quadro (~0) | alto (proibido hoje pelo `CLAUDE.md` e pela ADR-0003: "nada de programas próprios") | Só para registro: é o único caminho cujo custo não cresce com a distância |

**Ordem sugerida** (ganho alto, risco baixo primeiro, sem esperar decisões): O4, O5, O6, O11, O7 → O3 → O2 → (decisão
do dono) O1, ou O8 se O1 for recusado → O10. O9 depende de avaliação visual. Somadas, O4+O5+O6 tiram ~0,7 a 1,4 ms
por quadro e os picos; O2+O3 tiram a maior parte dos ~4,5 ms; só O1 (ou, parcialmente, O8) mexe nos 3,5 a 11 ms do
envio.

---

## 8. Como medir em jogo (para quem fechar a meta)

* F3 já mostra "malha" (`lastMeshNanos`: avaliação + cor + emissão + manutenção do quadro) e "envio" (`submit`,
  incluindo `tess.draw()`) (`ClientProxy.java:349-359`). Comparar com `/ondas desligar` e com `desenho.ligado=false`,
  **vsync e limite de FPS desligados**, distâncias 6, 8 e 12, sem e com shaderpack (BSL), numa máquina com GPU.
* Separar no F3 a manutenção (costa, releitura) da malha, e registrar o pior quadro (p99) além da média: os picos de
  costa (4.3) não aparecem na média exponencial (`OceanRenderer.java:286-288`).

---

## 9. Perguntas em aberto

1. O dono aceita um caminho de envio fora do `Tessellator` (VBO próprio) no modo sem shaderpack? Sem isso a meta de
   10% parece inalcançável (seção 6).
2. Quanto custa o `Tessellator` do Angelica numa GPU real? Os 9 a 11 ms são de GL por software, mas o custo é de CPU.
3. O passe 1 (`sortAndRender`) é chamado mais de uma vez por quadro no TEOF (Celeritas, Mirai/DH, anáglifo)?
4. Custo real de `colorMultiplier` (eventos `GetWaterColor`) e da descida de `depth` no cliente TEOF (4.4 é
   estimativa).
5. Números no Java 8 dos clientes: não há JDK 8 neste contêiner.
6. As faixas de triângulos (`GL_TRIANGLE_STRIP`) passam intactas pelo `TessellatorStreamingDrawer` do Angelica 2.2.43?
7. Com `animate_only_visible_textures=true` no Angelica e a face de cima da água escondida, o sprite da água continua
   animando? Se não, `WaterTexture` fica parado (visual, não desempenho).
