# Auditoria ENV: inventário do ambiente real (OceanWaves / Expeditions)

Escopo: só leitura. Nenhum arquivo de repositório foi alterado; escrita apenas em `/home/user/work/`.
Método: `ls` e `unzip -l` nos jars, `unzip -p` em manifests e `plugin.yml`, `javap -p` em classes, leitura de configs.
Rótulos: **verificado** = lido ou executado agora; **hipótese** = inferência a partir de nome ou de contexto.

Caminhos-base: cliente `/home/user/ext/teof/x` (TEOF 6.3), servidor `/home/user/ext/server/x` (Crucible).

---

## 1. Mods relevantes

| Família | Cliente (`teof/x/mods`) | Servidor (`server/x/mods`) | Observação |
|---|---|---|---|
| Angelica (renderer + shaders, Celeritas embutido) | `angelica-2.2.43.jar` | ausente | **verificado**. Só no cliente. Trata-se do pipeline de render; o shader ativo é a config `AngelicaShaders` |
| Et Futurum Requiem | `etfuturum-2.6.64-GTNH-git.1+77e9997e09.jar` | mesma versão | **verificado** nos dois lados |
| Neo States | `NeoStates-0.1.0-alpha.10.jar` | mesma versão | **verificado** nos dois lados |
| Kunftig | `kunftig-2bb00b3.jar` (API **16**) | `kunftig-0.1.0-dev-8349814.jar` (API **13**) | **verificado** via `KunftigApi.API_VERSION` (javap). Versões de API **diferentes** (ver seção 5) |
| Akashic ThePatcher | `!Akashic-ThePatcher-1.1.0-beta.53-NeoStatesDragonApiWaterPass.jar` | ausente | **verificado**. Contém `module/neostates/NeoStatesWaterPassHooks` e `NeoStatesWaterPassTransformer` |
| GeckoLib Unofficial | `geckolib-unofficial-1.7.10-1.0.4.jar` | mesma versão | **verificado** |
| UniMixins | `+unimixins-all-1.7.10-0.3.1.jar` | mesma versão | **verificado** |
| GTNHMixins | não encontrado | não encontrado | **verificado** (nenhum jar com este nome) |
| Hodgepodge | `hodgepodge-2.7.219.jar` | `hodgepodge-2.7.219.jar` | **verificado**. Obs.: o `CLAUDE.md` do AkashicFishing cita 2.7.189, divergindo do servidor real |
| ArchaicFix | `archaicfix-0.8.0.jar` | ausente | **verificado** |
| Celeritas / Embeddium / Rubidium | não encontrado | não encontrado | **verificado**. A função de Celeritas está dentro do Angelica (`quality.use_celeritas_smooth_lighting`) |
| OptiFine | não encontrado | não encontrado | **verificado** |
| FastCraft | não encontrado | não encontrado | **verificado** |
| Iris (separado) | não encontrado | não encontrado | **verificado**. Os shaders passam pelo Angelica (`AngelicaShaders`, `config/shaders.properties`) |
| futura3d (animações de personagem) | `futura3d-90c7159.jar` | `futura3d-0.1.0-dev-1c242cb.jar` | **verificado**. Versões diferentes. Traz `RenderBoatMixin` e `anim/physics` (mola de animação, não física de água) |
| FoamFix | ausente | `FoamFix-1.7.10-universal-1.0.4.jar` | **verificado** (só servidor) |
| lwjgl3ify | ausente | `lwjgl3ify-3.0.31.jar` | **verificado** (só servidor; não é mod de cliente) |
| DynamicSurroundings | `DynamicSurroundings-1.7.10-1.0.6.4.jar` | mesma versão | **verificado**. Sons de ambiente |
| ChromatiCraft, Mekanism, IC2, etc. | presentes | presentes | não relevantes à água, mas usam `Water`/`Liquid` em jar (contagem de classes abaixo) |
| Mods de barco/navio/física | nenhum por nome | nenhum por nome | **verificado** |
| **OceanWaves** | **não instalado** | **não instalado** | **verificado**: nenhum jar contém `oceanwaves` na listagem; nenhum `config/*ocean*` em nenhum dos dois lados |

Mods de barco/água/física por conteúdo (`unzip -l`, nomes de classe):
- `etfuturum` (107 entradas com boat/liquid): `EntityNewBoat`, `EntityNewBoatWithChest`, `EntityNewBoatSeat`, `ItemNewBoat`, `NewBoatRenderer`, `BoatMoveHandler`; mixins `liquidphysics/MixinEntity`, `MixinEntityItem`, `MixinWorld`, `MixinBlockLiquid` (**verificado**).
- `NeoStates` (112 entradas com water): `physics/ModernWater`, `WaterPhysics`, `WaterBody`, `ItemMotion`; mixins `MixinEntity_ModernWater`, `MixinEntityLivingBase_ModernWater`, `MixinEntity_Water` (**verificado** via javap).
  - `WaterBody` expõe `neostates$waterHeight()` e o setter (**verificado**). Este é o único ponto de altura da superfície de entidade no NeoStates. Hipótese: é o ponto de encaixe natural para o OceanWaves.
- `futura3d`: `RenderBoatMixin`, `anim/physics/{FirstOrder,SecondOrder,Physics,PhysicsManager}`, `boat.animation.json`, `boat.json` (**verificado**). Não é física de água.
- `kunftig` (cliente): `fishing/OpenWater` (detecção de água aberta para pesca; **verificado** por nome).
- `immersivepetroleum`, `industrialcraft`, `ThaumicHorizons`, `MineFactoryReloaded`, `BiblioCraft`: têm entradas com water/liquid por nome, sem relação com ondas (**verificado** só por nome).
- `Expeditions-0.1.0.jar` (plugin, ver seção 4): contém `physics/{WaveField,FloatingBody,HullColumns,Frame,Loading}` (**verificado**).

## 2. Versões do Forge/Crucible e Java

| Item | Cliente | Servidor |
|---|---|---|
| Minecraft | 1.7.10 | 1.7.10 |
| Forge/FML | `1.7.10-Forge10.13.4.1614-1.7.10` (`bin/version.json` id) | FML `7.99.4.1614` (`fmlbuild.*` no `server.jar`, build 1614, mcpversion 9.05) |
| Crucible / Thermos | não se aplica | `Implementation-Version: 1.7.10-staging-67b53034` (manifest do `server.jar`) |
| Launcher / bin | `bin/1.7.10.json`, `bin/modpack.jar` (manifest: `FMLTweaker`), `bin/version` = `GIT-0.1.X` | `server.jar` com `TweakClass: FMLTweaker`, `Main-Class` do launchwrapper |
| Java alvo de runtime | `javaVersion.majorVersion = 8` (`bin/1.7.10.json`, `jre-legacy`) | Java 21 (`java9args.txt` com `--add-opens`, `-Dcrucible.weAreJava9=true`); ambiente de análise tem OpenJDK 21.0.13 |

Obs.: o `bin/modpack.jar.bak-startup-20260923-234134` existe ao lado do `modpack.jar` de 24/09 (**verificado**, só registro).

## 3. Configs relevantes

### 3.1 Angelica (cliente)
- `config/angelica-options.json`, `config/angelica.cfg`, `config/angelica-modules.cfg` (**verificado**).
  - `quality.use_celeritas_smooth_lighting = true`, `biome_blend_mode = FANCY`, `mipmap_generation = MODERN_VANILLA`.
  - `advanced.vsync_mode = ON`, `multi_draw_mode = DIRECT`, `enable_deferred_batching = true`.
  - `performance.use_occlusion_culling = true`, `use_entity_culling = true`, `async_occlusion_mode = EVERYTHING`, `chunk_builder_threads = 0` (automático), `always_defer_chunk_updates = true`, `cpu_render_ahead_limit = 3`.
  - `reducer.enabled = true`, `unfocused_fps_limit = 10`, `idle_fps_limit = 30`.
- `config/shaders.properties` (AngelicaShaders, **verificado**): `enableShaders=true`, **`shaderPack=` (vazio)**, `allowUnknownShaders=false`, `maxShadowRenderDistance=0`.
  - Resultado: **nenhum shaderpack selecionado**. O shaderpack padrão é "nenhum" (renderização vanilla/Angelica sem pack).
  - Shaderpacks disponíveis em `shaderpacks/`: `BSL_v8.2.09.zip`, `ComplementaryUnbound_r5.9.3.zip` (não selecionados).
  - `options.txt` não tem chave `shaders`. Só as `key_iris.keybind.*` (**verificado**).
- Obs.: `config/mcpatcherforge.cfg` existe sem o jar MCPatcher correspondente (hipótese: resíduo).

### 3.2 options.txt (cliente, **verificado**)

| Chave | Valor |
|---|---|
| renderDistance | 6 (chunks) |
| maxFps | 60 |
| enableVsync | true |
| fancyGraphics | true |
| fboEnable | true |
| advancedOpengl | true |
| ao | 2 |
| clouds | false |
| particles | 0 (todas) |
| fullscreen | false |
| guiScale | 2 |
| mipmapLevels | 0 |
| shaders | ausente (ver 3.1) |

### 3.3 Et Futurum (barco), cliente e servidor idênticos (**verificado**)
- `enableNewBoats=true`, `replaceOldBoats=true` (o barco vanilla é trocado pelo novo), `newBoatSpeed=1.0`, `newBoatMaxLandSpeed=0.986`, `newBoatPassengerSeat=true`, `stopBoatRotationLock=false`.
- `adjustedLiquidPhysics=true`, `liquidItemFloat=true` (`config/etfuturum/mixins.cfg`).
- O `CLAUDE.md` do OceanWaves cita "barco de teste (Et Futurum Requiem / vanilla EntityBoat)". Com `replaceOldBoats=true`, o barco de teste é `EntityNewBoat`, não o `EntityBoat` vanilla. Isto importa para o alvo 2.

### 3.4 NeoStates (cliente e servidor, **verificado**)
- `physics.modernWaterPhysics=true` (estar na água pela superfície real, correnteza, afundar devagar), `floatingItems=true`, `modernAirRecovery=true`, `modernWaterLight=true` (luz da água 1 por bloco), `relightOldChunks=true`, `relightMillisPerTick=2`.
- `state.waterlogging=true`.
- `visual.modernWaterColor=true`, `visual.modernWaterFog=true`.
- O conteúdo é idêntico entre cliente e servidor (diferença só de fim de linha CRLF no cliente).

### 3.5 Kunftig (**verificado**)
- Cliente `config/kunftig.cfg`: `fishing.profile=VANILLA_1_7_10`, `fishing.lava=false`, `nametag.enabled=true`, `general.debug=false`.
- Servidor: `config/kunftig.cfg` existe (conteúdo não relevante ao mar).

### 3.6 ThePatcher e UniMixins
- `config/akashic-thepatcher.properties`: `module.rgbTextBackport=true`, `module.necroTempusBossbar=true`, etc. Não há chave de `neostates` nem de ondas (**verificado**).
- `config/unimixins.properties`: `enableIntegrityChecks=true`, `enableRemapper=true` (**verificado**).

### 3.7 Hodgepodge
- `config/hodgepodge.cfg`: `useLighterWater=false`, `preventLoadingChunksWhenLiquidsFlow=true`, `asyncBatchedNBTParsing=true` (**verificado**). A entrada `useLighterWater=false` é relevante a tudo que mexe com cor da água.

### 3.8 Futura3d
- `config/futura3d-client.properties`: `model=builtin:wine_fox/06_hanfu` (**verificado**). Não há configuração de barco.

## 4. Plugins do servidor

- `plugins/Expeditions-0.1.0.jar` (data 12/nov, **verificado**):
  - `plugin.yml`: `name: Expeditions`, `version: 0.1.0`, `main: com.akashic.expeditions.plugin.ExpeditionsPlugin`.
  - Comando `/expeditions` (alias `exp`), permissão `expeditions.admin`.
  - `usage` lista apenas `navio <chalupa|brigue|galeao>`, sem `fragata`. Obs.: o `CLAUDE.md` do Expeditions descreve a fragata como o navio em andamento, então o jar implantado é anterior à fragata (**hipótese** pela ausência de `fragata` no `navios.yml` do jar: `grep -c fragata` = 0 e só chaves `chalupa`, `brigue`, `galeao`).
  - Pacotes: `com/akashic/expeditions/{physics (5 classes), plugin (10), ship (8)}`.
  - `physics/WaveField` (**verificado** por javap): `calm()`, `swell(double,double)`, `height(double,double,double)`. É o encaixe do mar atual do Expeditions (o `CLAUDE.md` diz que o mar nasce plano).
  - O jar **não** contém classe `com/akashic/oceanwaves` nem qualquer lib de oceano.
  - O jar não traz `kunftig-api` (é `compileOnly`, correto).
- Os outros plugins com nomes que sugerem mar: `Multiverse-Core` (mundos), `WorldBorder`, `ForbiddenChunkLoaders`. Nenhum é de física de água.
- `config`/`plugins` do servidor: nenhum `server.properties` em `server/x` (**verificado**: arquivo ausente). `allow-flight` não pode ser confirmado daqui. O `CLAUDE.md` do Expeditions exige `allow-flight=true`.
- Não há pasta de mundo (`world*`) em `server/x`. A verificação de `level-name` e dos mundos do Multiverse (`Akashic/Spawn`) não é possível neste inventário.

## 5. Divergências e pontos de atenção (para as outras frentes)

1. **API do Kunftig diferente entre cliente e servidor**: cliente API **16**, servidor API **13** (**verificado**). Itens 14 (estações), 15 (posturas) e 16 (zoom) não existem no servidor. O `CLAUDE.md` do Kunftig avisa que cliente e servidor de ramos diferentes devem ser atualizados juntos.
2. **Expeditions implantado é pré-fragata**: jar de 12/nov sem `fragata` no `navios.yml`, e `usage` sem `fragata` (**verificado**).
3. **OceanWaves ausente dos dois lados** (**verificado**).
4. **Shader**: nenhum pack ativo (`shaderPack=`). Qualquer medição de FPS com shader precisa selecionar um pack explicitamente.
5. **Barco de teste**: com `replaceOldBoats=true`, o barco é `EntityNewBoat` (etfuturum), não `EntityBoat` vanilla.
6. **Água de entidade**: a altura da superfície para entidades vem do NeoStates (`WaterBody.neostates$waterHeight`, `MixinEntity_ModernWater`), não de OceanWaves. Hipótese: é o ponto de integração a estudar.
7. **Hodgepodge**: 2.7.219 no servidor e no cliente; o `CLAUDE.md` do AkashicFishing cita 2.7.189 (divergência de documento).
8. **Forge**: cliente e servidor usam Forge 10.13.4.1614 (FML 7.99.4.1614). Compatíveis (**verificado**).
9. **Java**: cliente Java 8 (`javaVersion` 8), servidor Java 21 (**verificado** no `java9args.txt`; versão exata do processo não observada).
10. **Versões de futura3d diferentes** entre cliente (`90c7159`) e servidor (`0.1.0-dev-1c242cb`) (**verificado**).
11. **ThePatcher só no cliente** (**verificado**).

## 6. Lacunas (não verificado)

- Versão exata do Java em execução no servidor (só o `java9args.txt` e o alvo 21 do `CLAUDE.md`).
- `allow-flight`, `level-name` e mundos do servidor: não há `server.properties` nem pastas de mundo em `server/x`.
- `server.tar.gz` em `/home/user/ext/server/` não foi aberto (fora do escopo; só leitura de jars e configs).
- Conteúdo de `Angelica` além da config (ex.: versão do shader Iris interno) não foi aberto em bytecode.
- Nenhum teste em jogo nem medição de FPS. Nenhum número de desempenho foi produzido aqui.
