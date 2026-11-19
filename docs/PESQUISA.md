# Pesquisa: o ambiente, o Physics Mod e o que dá para superar

Feita antes e durante a implementação (novembro de 2026). Cada afirmação traz de onde veio; o que não foi conferido
em código ou em jogo está marcado **[a verificar]**.

## 1. O ambiente alvo

| Peça | O que é | Fonte |
|---|---|---|
| Minecraft 1.7.10, Forge 10.13.4.1614 | o modpack TEOF / Forbidden Series, ~215 mods | `gradle.properties` do Kunftig e do Neo States; `docs/TESTES.md` do Neo States |
| Cliente | Java 8 (o `jre-legacy` 8u51 do Technic; também SKLauncher e jogadores no Android) | `docs/HISTORY.md`, `ARCHITECTURE.md` do AkashicThePatcher |
| Servidor | Crucible (híbrido Forge + Bukkit 1.7.10) em Java 21 | `CLAUDE.md` do AkashicFishing e do Expeditions |
| Angelica | 2.2.40 a 2.2.43 (os dois repositórios divergem): Celeritas (renderizador de chunks estilo Sodium) e Iris (shaders), contexto OpenGL 3.3 core | `docs/HANDOFF.md` do ThePatcher; `TESTES.md` do Neo States |
| Modo de compatibilidade do launcher | "PC fraco (OpenGL 3.1)": tira o Angelica e o Mirai; aí o DragonAPI reescreve `WorldRenderer.updateRenderer` | checkpoint beta.53 do ThePatcher |
| Shaders do pack | BSL 8.2.09 e Complementary Unbound r5.9.3 (com programas `dh_terrain`) | `HANDOFF.md` do ThePatcher |
| LOD distante | "Akashic: Horizons of The Mirai", um fork do Distant Horizons: água distante só pelo passe translúcido adiado | checkpoint beta.42 do ThePatcher |
| Mixins | UniMixins 0.3.1 (Mixin 0.8.5-GTNH, MixinExtras) | checkpoint beta.53; `mixins.kunftig.json` |
| Água | Neo States: textura, cor e névoa do 1.21.1; física unificada de altura (`surfaceY`); waterlogging | README e `docs/` do Neo States |

**Conferido neste ambiente:** o build do Kunftig e o deste mod com a convenção GTNH (RetroFuturaGradle, Gradle
9.7.1, Jabel para bytecode Java 8). O Maven Central responde 429 de vez em quando: repetir com `--max-workers=1`.

### 1.1 Os ganchos do 1.7.10 que o mod usa (lidos no código decompilado pelo RFG)

* **A face de cima da água**: `RenderBlocks.renderBlockLiquid` pergunta
  `block.shouldSideBeRendered(world, x, y + 1, z, 1)` (`RenderBlocks.java:4145`), e
  `BlockLiquid.shouldSideBeRendered` devolve verdadeiro para o lado 1 se o bloco de cima não é do mesmo material
  (`BlockLiquid.java:167`). O Angelica 2.2.43 monta os chunks pelo mesmo caminho
  (`AngelicaChunkBuilderMeshingTask` → `renderBlockByRenderType` → `renderBlockLiquid`, com um `IBlockAccess` próprio
  e em várias threads), segundo a ADR-0002 do Neo States. Sem o Angelica, o DragonAPI reescreve a escolha do passe
  mas não a pergunta pela face. **[a verificar em jogo com Angelica e com DragonAPI.]**
* **O passe translúcido**: `EntityRenderer.renderWorld` chama `RenderGlobal.sortAndRender(entity, 1, partial)` (três
  pontos, `EntityRenderer.java:1392-1405`), depois as entidades do passe 1 e o `RenderWorldLastEvent`. Com shaders,
  o Angelica adia o translúcido (`setDeferTransparentRendering`) e o Mirai pendura a água distante num `@Inject` em
  `sortAndRender` passe 1 (checkpoint beta.42): o mod pendura o mar no mesmo lugar.
* **O tempo**: o servidor vanilla manda `S03PacketTimeUpdate(totalWorldTime, worldTime, …)` a cada 20 ticks
  (`MinecraftServer.java:683-686`) e ao entrar (`ServerConfigurationManager.java:1006`); o cliente aplica em
  `NetHandlerPlayClient.handleTimeUpdate` (`:891`). O tempo total é monotônico (`/time set` mexe no outro).
* **A água do mar**: o gerador do 1.7.10 enche até y = 62 com água parada (nível do mar 63); a face de cima de uma
  fonte fica em 8/9 do bloco (o Neo States mantém 8/9: `FluidMath`).

### 1.2 O ecossistema

* **Expeditions** (plugin, navios como exibições do Kunftig): já tem a física de flutuação em `core/physics`
  (`FloatingBody`, colunas do casco, empuxo integrado contra `WaveField.height(x, z, t)`), e deixou o `WaveField`
  (quatro senoides) como "encaixe" para as ondas de verdade: "as ondas serão outro projeto". É este. O plugin conta
  o tempo do mar com um contador próprio (ticks desde que o plugin ligou), não com o tempo do mundo.
* **Kunftig**: desenha as exibições no `RenderWorldLastEvent`, liga o mapa de luz em volta, compila os ossos parados
  num VBO; as poses chegam do servidor e deslizam em N ticks (`DisplayMotion.glide`), com um relógio de ticks do
  cliente. **Não há tempo sincronizado com o servidor** no Kunftig. A ponte com o Neo States lê `surfaceY` por
  reflexão para a boia da pesca.
* **Neo States**: a API `NeoStates.surfaceY` e o `FluidPipeline` (`HeightKind` LOGICAL/INTERACTION/RENDER) foram
  pensados para receber ondas ("o algoritmo de ondas… não decide", ADR-0003), com a regra de que o que empurra
  entidades tem de ser função de `totalWorldTime` + posição, igual no servidor e no cliente. Nenhum `FluidStage`
  está registrado ainda. Barcos ficaram fora ("outro projeto").

## 2. O oceano do Physics Mod

Fontes: o site e a página Pro, o repositório público `haubna/PhysicsMod` (README, `OCEAN.md`, `LEGACY_OCEAN.md`, o
`oceans.glsl` legado e as issues), páginas de terceiros. Patreon e alguns sites recusaram acesso (403).

### 2.1 O que ele faz (verificado)

* **Só na versão Pro** (Patreon/Ko-Fi), do 1.18.2 ao 26.2; a Lite não tem oceano
  ([pro](https://minecraftphysicsmod.com/pro), [feed-the-mods](https://www.feed-the-mods.com/physics-mod/)).
* **Só do cliente.** O autor: "Ocean physics work fine on servers except when servers do funky stuff with water"
  ([issue #1094](https://github.com/haubna/PhysicsMod/issues/1094)). O servidor continua vendo água plana; nada
  indica que as ondas sejam as mesmas entre jogadores **[a verificar]**: o tempo do shader é um tempo do cliente
  que "pode andar mais rápido conforme o tempo (clima)" (`oceans.glsl`).
* **Entidades seguem a onda** pelo cliente: "the overall shape must stay consistent because it is also computed on
  the CPU side to offset entities" (`oceans.glsl`): uma cópia em CPU da função da GPU.
* **Configurável**: altura (base 13 × ajuste), multiplicador de tempo pelo clima, escala horizontal, detalhe de
  superfície (13 a 48 iterações), espuma, textura de ondulações (esteiras), e uma textura de "waviness" que diz quão
  rasa ou perto da costa a água está: é assim que as ondas morrem na costa.
* **Shaders**: só com Iris; uma API para shaderpacks (`physics_oceanVertex`, `physics_oceanFragment`) na V3, e
  injeção automática nos packs que não a usam ([OCEAN.md](https://github.com/haubna/PhysicsMod/blob/main/OCEAN.md)).

### 2.2 Como ele faz

* **Legado (v1/v2, verificado no GLSL publicado)**: não é Gerstner nem FFT. É a soma de ondas do Shadertoy de
  afl_ext ([Xdlczl](https://www.shadertoy.com/view/Xdlczl)): N ondas com direções (sen i, cos i) a cada 12 rad, perfil
  exp(sen x − 1) (crista aguda), a posição amostrada empurrada pela inclinação das anteriores (um "choppy" barato),
  peso ×0,8, frequência ×1,18 e velocidade ×1,07 por onda. 13 iterações no vértice, 13 a 48 na normal do fragmento.
  Só desloca a altura. Sem base espectral: os números são estéticos.
* **V3**: "suporta as duas técnicas (legado e FFT)"; estágios de tesselação gerados dinamicamente (README). Logo,
  **OpenGL 4.0 com tesselação** na GPU **[inferência]**. Espectro, tamanho da FFT e o LOD não são públicos.

### 2.3 Problemas conhecidos (issues públicas)

Quebras com shaders (#651, #1006) e OptiFine (#652); oceano chato com shaders (#583); invisível com PBR (#1174);
desenhado junto com a água plana do Sodium (#1191, corrigido); artefatos olhando ao longo de ±z (#1172, aberto);
incompatível com Voxy (#1177, #1178, #1194), FartherViewDistance (#713), Bobby (#495). **Costura na borda da
distância de renderização**: o oceano "passa um pouco dos chunks reais" e o Distant Horizons não desenha água além,
deixando uma faixa escura (há um mod de terceiros só para isso,
[Ocean Seam Fix](https://www.curseforge.com/minecraft/mc-mods/ocean-seam-fix-physics-mod-ocean-distant-horizons)).
As notas da v135 recomendavam no máximo 12 chunks de distância por causa de picos de lag **[trecho de busca]**.

### 2.4 Outras referências do Minecraft

* **Sablewaves** (NeoForge 1.21.1, para o Create: Aeronautics): 8 componentes de Gerstner, "um campo de ondas
  determinístico, sem dessincronia, sem rede"; a física amostra o casco numa grade de colunas; desenha por cima da
  água vanilla, então os vales ainda mostram a superfície plana
  ([página](https://www.curseforge.com/minecraft/mc-mods/sablewaves)).
* **Realistic Waves** (1.20.1 a 26.2): desenha a própria superfície; sem o mod no servidor, "o barco só é desenhado
  surfando; o servidor ainda vê água plana" ([página](https://www.curseforge.com/minecraft/mc-mods/realistic-waves)).
* **No 1.7.10**: nenhum mod que desloque a geometria da água foi encontrado.

## 3. O estado da arte fora do Minecraft

| Referência | O que tiramos dela |
|---|---|
| Tessendorf (2001) | o modelo trocoidal, o deslocamento "choppy", o jacobiano como máscara de dobra e de espuma |
| Horvath (2015) | JONSWAP + TMA + Donelan-Banner com as constantes; o marulho; aleatoriedade por semente que não muda com a resolução |
| Atlas (GDC 2019) | JONSWAP duplo (vento + marulho); "a simulação só depende do tempo absoluto e dos parâmetros do espectro: servidor e clientes calculam sozinhos"; plano ajustado às amostras para corpos (forças ponto a ponto ficam ruidosas abaixo de 10 Hz); iteração de ponto fixo para a altura |
| Crest (Unity) | anéis de malha centrados na câmera com vértices presos à grade do mundo; consulta de altura por 4 iterações de ponto fixo (`QueryDisplacements.compute`) |
| GPU Gems cap. 1 (Finch) | Gerstner com Qᵢ; "se Σ QᵢwᵢAᵢ > 1 a onda dá a volta sobre si" |
| Unreal Water | Gerstner na CPU para a física e atenuação por profundidade na costa |

## 4. Decisões: onde igualamos e onde superamos

| Aspecto | Physics Mod | OceanWaves | Por quê |
|---|---|---|---|
| Base física | soma estética de exp(sen) | espectro direcional JONSWAP + TMA + Donelan-Banner, mar de vento e marulho, Hs preservado | as ondas respondem ao vento como o mar real (e o vento é o mesmo das velas do Expeditions) |
| Perfil | crista aguda por exp(sen) | trocoidal: crista aguda, vale largo, assimetria de Stokes de 2ª ordem exata, nível médio conservado | `OceanFieldTest` |
| Dobra da malha | sem garantia | **impossível por construção** (Lipschitz < 1, inclusive na costa) | §3.1 e §5.1 de MATEMATICA.md |
| Multijogador | só cliente, servidor vê água plana, fases por cliente | **o mesmo mar no servidor e em todos os clientes**: estado de 140 bytes, síntese idêntica bit a bit entre Java 8 e 21, tempo total do mundo | a física dos navios roda no servidor |
| Relógio | tempo do cliente | tempo do servidor, filtrado (monotônico, segue servidor a 15 TPS), funciona até com servidor vanilla | `ClockAndFitTest` |
| Altura num ponto | ? | Newton com o jacobiano analítico (quadrático; o Crest e o Atlas usam ponto fixo, linear) | §4 |
| Costa | textura de "waviness" | transformada de distância exata sobre os blocos reais + limite de quebra pela profundidade, igual no servidor | §5 |
| Borda da malha | "passa um pouco dos chunks" (costura com o DH) | a superfície cobre exatamente as colunas de mar dos chunks carregados, e a face vanilla só some nelas | `OceanMesherTest` |
| LOD | tesselação na GPU | LOD espectral contínuo, sem frestas entre resoluções e sem estalos | §6.2 |
| GPU | OpenGL 4.0 (tesselação), Iris obrigatório para shaders | CPU + `Tessellator`: roda no OpenGL 2.1 do modo de compatibilidade, com e sem Angelica | o pack tem jogadores com Intel HD 2000 e no Android |
| Superfície fina (ondulações por pixel) | normal por fragmento com 13-48 iterações | **não** (o 1.7.10 sem shader não tem iluminação por pixel); a textura animada da água dá o detalhe, e com shaderpack o pack põe o dele | é onde o PM ganha hoje |
| Reflexos | do shaderpack | sem shader: Fresnel de Schlick com a cor do céu + brilho do sol por vértice; com shaderpack: os do pack **[a verificar]** | |

**O que fica para depois** (ver ARQUITETURA.md, "Próximas fases"): camada em GPU (o mesmo campo num vertex shader,
quando não há shaderpack), a física de barcos vanilla e de entidades (o núcleo já tem `SurfaceFit`), a névoa
submersa coerente com a onda, esteiras, e a ponte com o `FluidPipeline` do Neo States.
