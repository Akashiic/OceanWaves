# Auditoria NEO: Neo States, OceanWaves e as embarcações

Frente NEO da auditoria. Escopo: o que o Neo States (alpha.10) faz com a água, as entidades e os barcos; onde um
gancho do OceanWaves (altura da onda no lugar do topo do bloco) precisa entrar para valer com o Neo States presente;
conflitos de mixin; convivência de textura e cor; conferência dos jars alpha.10 do pack e do servidor contra o
repositório. Nenhum arquivo dos repositórios foi alterado. Material de trabalho (jars extraídos) em
`/home/user/work/tmp/neo/`.

Convenção: **[V]** verificado (código lido ou bytecode lido com `javap`); **[H]** hipótese ou conhecimento que não
está no repositório. Caminhos de código: `NS` = `/home/user/Neo-States/src/main/java/com/akashic/neostates`,
`OW` = `/home/user/OceanWaves/src/main/java/com/akashic/oceanwaves`.

---

## 0. Resumo

1. O Neo States é a água do 1.21.1 em **blocos**: altura lógica em nonos, 8/9 na fonte. Ele já tem **um encaixe para
   ondas** (`FluidPipeline` + `FluidStage`, ADR-0003) e **nenhum estágio registrado**. **[V]**
2. O encaixe existente **não basta** para ondas literais: o estágio recebe só `(x, y, z)` inteiros e devolve `[0, 1]` por
   célula, sem tempo, sem posição contínua, sem velocidade. Pior: o Neo só lê a superfície **dentro de células que têm
   água**, então uma crista acima do topo do bloco (célula de ar) nunca é vista pelo corpo, pelos olhos, pela câmera nem
   pelo `surfaceY`. **[V]** Um gancho de verdade precisa de um amostrador **por ponto (x, z)**, por coluna, no Neo.
3. O Neo States **não tem física de barco**. O barco do pack é o `EntityNewBoat` do Et Futurum (`replaceOldBoats=true`),
   que lê blocos e metadados direto; o barco vanilla lê `World.isAABBInMaterial`. Nenhum estágio do pipeline os alcança.
   **[V]**
4. Mixins: os dois mods compartilham **um** alvo, `BlockLiquid.shouldSideBeRendered`, e os resultados são compatíveis.
   O risco real está nos ganchos **futuros** do OceanWaves em `Entity.handleWaterMovement`,
   `moveEntityWithHeading` e `World.isAABBInMaterial` (seção 6).
5. Textura e cor convivem: o OceanWaves já usa `GrayWater.icon(1)` por reflexão e a cor vem de
   `Blocks.water.colorMultiplier` (evento do bioma do Neo). **[V]**
6. Jars alpha.10 (pack e servidor): **idênticos** entre si (mesmo SHA-256) e **coerentes com o repositório** no que
   `javap` mostra (conjunto de classes, lista de mixins, constantes da física, JSONs). Não foi feito rebuild. **[V]**

---

## 1. O que foi conferido (alpha.10 contra o repositório)

| Verificação | Resultado | Evidência |
|---|---|---|
| Pack e servidor têm o mesmo jar | **idênticos**, SHA-256 `02832cee...2618` | `sha256sum` dos dois `NeoStates-0.1.0-alpha.10.jar` |
| Et Futurum do pack e do servidor | idênticos, SHA-256 `7a30eb66...c3c9` | idem |
| Classes do jar x fontes | mesmo conjunto; só falta nas fontes a `Tags` (gerada pelo Gradle) | `diff` de `src.lst` e `jar.lst` (81 classes mais a `Tags`) |
| `mixins.neostates*.json`, `mcmod.info` | JSONs idênticos aos de `src/main/resources`; `mcmod.info` só troca os tokens `${modVersion}` | `diff` |
| Lista de mixins carregados | a de `NeoStatesCore.getMixins` do jar é a das fontes (17 comuns, 3 do cliente, mais `WorldRenderer_WaterPass` só sem Angelica, `RenderBlocks_Water`, `BlockLiquid_WaterFaces`) | `javap -c NeoStatesCore` |
| Constantes da física | `PUSH 0.014`, `SHALLOW 0.4`, `MIN_PUSH 0.0045`, `GRAVITY 0.005`, `FLOAT_LIFT 5.0E-4`, `FLOAT_MAX 0.06`, `FLOAT_DRAG 0.99`, `ARROW_WATER_INERTIA 0.6` | `javap -constants WaterPhysics` |
| `ModernWater.update` | usa `0.001` (6x), `0.4`, `HeightKind.INTERACTION`, `Blocks.flowing_water` para o fluxo | `javap -c ModernWater` |
| Pipeline e API | `FluidStage.height(IBlockAccess,int,int,int,int,HeightKind,float)` + `deterministic()`; `NeoStates` com `surfaceY(IBlockAccess,int,int,int)`, `isWaterAt`, `canHoldWater`, `isSourceAt`... (API 4) | `javap` |
| `GrayWater.icon(int)` | `public static`, existe | `javap GrayWater` |
| Alvos tardios no Angelica 2.2.43 do pack | existem: `compat.ExtendedBlockStorageExt#copyFrom(Chunk,ExtendedBlockStorage)`, `rendering.celeritas.world.WorldSlice` com `sections/baseX/baseY/baseZ/volume/getLocalSectionIndex` | `javap` no `angelica-2.2.43.jar` |
| Mixins do Et Futurum 2.6.64 (o do pack) contra o que o `COMPATIBILIDADE.md` descreve (leu o 2.6.59) | batem: `MixinWorld.handleMaterialAcceleration` com `@ModifyConstant 0.014 ordinal 0` e `@WrapOperation Vec3.normalize`; `MixinEntityItem.onUpdate` `priority=1002` `@Inject HEAD`; `MixinBlockLiquid.velocityToAddToEntity` `@ModifyVariable STORE ordinal 1`; `MixinEntity.onEntityUpdate` | `javap -v` das classes e `mixins.etfuturum.refmap.json` |
| `EntityLivingBase.moveEntityWithHeading` no `minecraft.jar` do pack (`sv.e(FF)V`) | três `LDC 0.8D` (arraste da água), depois o **primeiro** `0.02D` (ramo da água) e o segundo (lava): bate com `@ModifyConstant ordinal = 0` do Neo e com a contagem de três do Player Backport | `javap -c sv` |

Limites: sem build local (não há `build/` no repositório) não dá para provar igualdade de bytecode classe a classe; a
conferência é superficial, como pedido. O repositório está em `288b929` (merge do PR #1, 2026-11-11) e a branch de
trabalho não tem alterações pendentes (`git status` limpo).

Divergências de documentação encontradas (não do código):

* `docs/adr/ADR-0003-pipeline-de-fluidos.md` §3 e §4 especificam `FluidStage.flow(...)`, `cornerHeight`, o mixin de
  `getLiquidHeight` apontando para o pipeline e `register(prioridade, estágio, lados)`. O código tem só `height(...)` e
  `deterministic()`, `register(FluidStage)` sem prioridade, e **`HeightKind.RENDER` não tem nenhum chamador** (`grep`).
  `MixinRenderBlocks_Water#getLiquidHeight` só trata água hospedada (`NS/mixin/early/client/MixinRenderBlocks_Water.java`).
* `docs/COMPATIBILIDADE.md` diz que o Player Backport precisa de mudança para usar a API do Neo "a combinar". O jar
  `akashicplayer-0.1.0-alpha.30-push-rc1.jar` do pack **já** tem `compat.NeoStatesBridge` e `WaterPoseConditions` /
  `SwimmingVerticalHooks` chamam `isWaterAt` e `surfaceY` por reflexão. **[V]** (`javap`: `invokestatic NeoStatesBridge.isWaterAt` e `.surfaceY`)
* O Javadoc de `NeoStates.API_VERSION` descreve até a API 3 e o de `isWaterlogged` está colado em cima do de
  `isSourceAt` (`NS/api/NeoStates.java`). Cosmético.

---

## 2. O que o Neo States muda na água, nas entidades e nos barcos

### 2.1 Cadeia de leitura que uma entidade usa (o que o gancho precisa interceptar)

```
Entity.onEntityUpdate
  -> Entity.handleWaterMovement()            [vanilla: sa.N(); expand(0,-0.4,0).contract(0.001) -> World.handleMaterialAcceleration(box, water, this)]
       Neo: @WrapOperation no INVOKE de handleMaterialAcceleration
            (MixinEntity_ModernWater.java:58-69)  -> ModernWater.update(entity)   (ignora a caixa recortada do vanilla)
  -> EntityItem.handleWaterMovement()        [próprio: caixa inteira]    mesmo desvio (MixinEntityItem_Float.java:69-80)
  -> EntityXPOrb.handleWaterMovement()       [próprio: sq.N()]           NÃO é desviado (ver NEO-5)
  -> Entity.isInsideOfMaterial(water)        Neo: @Inject HEAD cancelável -> ModernWater.submerged(olhos)
                                             (MixinEntity_ModernWater.java:72-79) ; MixinEntity_Water embrulha o getBlock (água hospedada)
  -> ActiveRenderInfo.getBlockAtEntityViewpoint   Neo: @ModifyReturnValue (câmera na água) (MixinActiveRenderInfo_Water.java)
```

`ModernWater.update` (`NS/physics/ModernWater.java:63-133`) é o `updateFluidHeightAndDoFluidPushing` do 1.21.1:
caixa inteira menos 0,001; para cada célula `(x, y, z)` com `y` de `floor(minY+0.001)` a `ceil(maxY-0.001)`,
`surface(x,y,z)` (altura de interação do pipeline, 0..1, ou -1 sem água); `top = y + surface`; se `top >= minY`: dentro,
`height = max(top - minY, height)`; se a entidade é empurrada por água, soma o `getFlowVector` da célula (escalado pela
profundidade se `height < 0,4`); no fim `WaterPhysics.push` (média por célula, normaliza se não é jogador, `x 0.014`,
empurrão mínimo 0,0045) e grava `neostates$waterHeight` (a profundidade da água sobre os pés).

### 2.2 Tabela de mudanças

| Ponto vanilla | O que o Neo faz | Evidência |
|---|---|---|
| `Entity.handleWaterMovement` / "na água" / `isInWater()` | O acessor `isInWater()` não muda (devolve o campo `inWater`). Muda **como o campo é calculado**: superfície real por célula, caixa inteira (sem o corte de 0,4), empurrão fiel ao 1.21.1. Respingo, queda e fogo do método vanilla continuam | `MixinEntity_ModernWater.java:58-69`, `ModernWater.java:63-133` |
| `World.handleMaterialAcceleration` | **Não é alterado** (regra do projeto: o Et Futurum e o BelowCore ancoram ali). O Neo só embrulha os `getBlock`/`getBlockMetadata`/`velocityToAddToEntity` dele para água hospedada. Para a atualização da própria entidade o método **nem roda** (o `@WrapOperation` não chama `original`), então o `0.014`/`normalize` do Et Futurum ficam sem efeito na água; outros chamadores continuam com eles | `MixinWorld_Water.java:69-103`; `COMPATIBILIDADE.md` regras 5 e 6 |
| `Entity.isInsideOfMaterial(water)` | `HEAD` cancelável: olhos abaixo da superfície **da célula** (`ModernWater.submerged`). Com `modernWaterPhysics` ligado o wrap de `getBlock` de `MixinEntity_Water` fica inalcançado (o `HEAD` retorna antes); com a opção desligada ele é quem trata a água hospedada | `MixinEntity_ModernWater.java:72-79`, `MixinEntity_Water.java` |
| Empuxo / flutuação | **Não há empuxo físico.** Há (a) gravidade na água `0.02 -> 0.005` (zero correndo) por `@ModifyConstant ordinal 0` em `moveEntityWithHeading`; (b) item: sem gravidade, sustentação constante de 0,0005/tick até 0,06 quando a água passa de 0,1 sobre os pés, arraste horizontal 0,99; (c) orbe: igual, com "olhos" a 0,425; (d) flecha: inércia 0,6 em vez de 0,8. Nada depende da profundidade nem da aceleração da superfície | `MixinEntityLivingBase_ModernWater.java:29-31`, `MixinEntityItem_Float.java:59-115`, `MixinEntityXPOrb_Float.java:26-34`, `MixinEntityArrow_Water.java:16-19` |
| Nado | O Neo **não implementa pose de nado** (é do Player Backport, três `0.8 DMUL`). Acrescenta a regra do pulo no raso (`@ModifyExpressionValue` em `isInWater()` dentro de `onLivingUpdate`), descer agachado (evento do cliente, `-0,04`) e a volta do ar (+4 por tick) | `MixinEntityLivingBase_ModernWater.java:38-50`, `SneakDescend.java`, `MixinEntityLivingBase_Air.java` |
| Barco | **Nada.** Sem classe, mixin nem teste (`grep -i boat` só acha documentação). `DELTAS.md` I-7 e `ENTREGA.md` o põem "fora do escopo / outro projeto"; `MODERN-1.21.1.md:147,245` deixa o `Boat` do 1.21.1 como **[A VERIFICAR]** | `grep` em `Neo-States` |
| Câmera e névoa | `getBlockAtEntityViewpoint` corrigido pela mesma superfície; a névoa usa os eventos `FogColors`/`FogDensity` do Forge (sem injeção nos métodos de névoa) e `isInsideOfMaterial(water)` para "olhos na água" | `MixinActiveRenderInfo_Water.java`, `UnderwaterFog.java` |
| Altura da água | Tabela inalterada (`(8 - decay)/9`, fonte 8/9). `FluidPipeline.height(LOGICAL)` ignora estágios; `INTERACTION` só aplica estágios com `deterministic() == true`; `RENDER` aplica todos | `FluidMath.java`, `FluidPipeline.java:42-58` |
| Configuração | `physics.modernWaterPhysics` (e as outras duas de física) valem pelo **servidor**; o cliente em conexão remota assume tudo desligado até o servidor falar | `NeoConfig.java:41-57` |

Configuração real do pack (`/home/user/ext/teof/x/config/neostates.cfg`): tudo ligado. Et Futurum
(`config/etfuturum/mixins.cfg`): `adjustedLiquidPhysics=true`, `liquidItemFloat=true` (o Neo os neutraliza na água);
`blocksitems.cfg`: `enableNewBoats=true`, `replaceOldBoats=true`.

### 2.3 Barcos: o Neo tem física de flutuação? Como o barco lê a água?

**O Neo States não tem.** Quem flutua barcos hoje, e como lê a água:

| Barco | Como lê a água | Passa pelo pipeline do Neo? | Evidência |
|---|---|---|---|
| **Et Futurum `EntityNewBoat`** (o barco do pack: `replaceOldBoats=true`, ou seja, o barco vanilla antigo vira este) | `checkInWater`: laços `x`, `y`, `z` sobre a caixa; `World.getBlock(...).getMaterial() == Material.water`; `getLiquidHeight(world,x,y,z) = y + getBlockLiquidHeight`, e `getBlockLiquidHeight = (meta % 8 == 0 e água em cima) ? 1 : 1 - BlockLiquid.getLiquidHeightPercent(meta)` (8/9 na fonte); `waterLevel = max(...)`; "dentro" se `minY < f`. O laço vertical cobre só as camadas de `floor(minY)` a `ceil(minY+0.001)` | **Não.** Lê `World` direto, não vê água hospedada nem estágio | `javap -c EntityNewBoat` (`checkInWater`, `getBlockLiquidHeight`, `getLiquidHeight`, `getWaterLevelAbove`) **[V]** |
| **Barco vanilla `EntityBoat`** (`xi.h()`) | Cinco fatias da caixa (`-0,125`), cada uma com `World.isAABBInMaterial(fatia, water)` | **Não** (só a água hospedada, pelo embrulho do Neo) | `javap -c xi` **[V]** |
| `World.isAABBInMaterial` (`ahb.b(azt,awt)`) | por célula de água: `d = y + 1`; se `meta < 8`, `d = y + 1 - meta/8.0`; verdadeiro se `d >= aabb.minY`. **Uma fonte conta como superfície em y+1, não em y+8/9** (a fatia do barco vê a água 1/9 mais alta que a do desenho) | n/a | `javap -c ahb` **[V]** |

O `Boat` do 1.21.1 tem flutuação própria (estados `IN_WATER`/`UNDER_WATER`, `getWaterLevelAbove`, força proporcional
à profundidade). **[H]**: isso não está no repositório (o `MODERN-1.21.1.md` o marca como a verificar); o
`EntityNewBoat` do Et Futurum é um porte dessa lógica de estados, com a altura já igual à do Neo (8/9).
Consequência para o projeto: o Neo não entrega flutuação de barco, e **nenhum estágio de `FluidPipeline` chega ao barco**.
O barco de teste e a fragata precisam de ganchos próprios (frente BOAT/WAVE), lendo o mesmo amostrador de ondas.

---

## 3. Onde o gancho do OceanWaves precisa entrar

### 3.1 Estado atual do encaixe

`OW/docs/ARQUITETURA.md` (fase 3) prevê "registrar um `FluidStage` determinístico no `FluidPipeline`". É viável (e
deve ser feito cedo, ver NEO-4), mas é um encaixe de **altura por célula**:

```java
interface FluidStage { float height(IBlockAccess a, int x, int y, int z, int view, HeightKind k, float previous);
                       boolean deterministic(); }                  // NS/fluid/FluidStage.java
```

Quem o consome (todos com `INTERACTION`): `ModernWater.surface` (corpo, olhos, câmera, itens, orbes) e
`NeoStates.surfaceY` (Kunftig, Player Backport). **[V]** (`grep FluidPipeline`)

### 3.2 Por que o encaixe, sozinho, não dá ondas literais

| # | Limite | Efeito com ondas | Evidência |
|---|---|---|---|
| L1 | O estágio vê **célula inteira** `(x,y,z)`, não `(x,z)` contínuo | ζ(x,z) varia dentro da célula; amostrar no centro erra até meio bloco x inclinação local (a malha do OW limita o gradiente, `maxSteepness` < 1); uma entidade que anda não teria superfície contínua, e sim degraus de 1 bloco | `FluidStage.java`, `ModernWater.java:86-123` |
| L2 | Sem tempo na assinatura | só dá para pegar o tempo do `IBlockAccess` se for um `World`. No servidor serve (`getTotalWorldTime()/20`). No **cliente não serve**: o total do mundo só salta a cada pacote (1 s) e o OW usa `OceanClock` (estimativa suave, re-ancorada) para as ondas não tremerem; o estágio teria de ler `ClientSea.CLOCK` | `OW/client/ClientSea.java:24,85-91`, `OceanClock` |
| L3 | Altura limitada a `[0,1]` **dentro da célula de água** | a célula acima do último bloco de água é ar: `FluidResolver.resolve` devolve `EMPTY`, `surface()` devolve -1 e o estágio **nem é chamado**. Uma crista de +0,5 acima do topo (8/9) deixa o corpo, os olhos e a câmera "fora d'água" enquanto a malha desenhada está acima deles | `FluidResolver.java:18-31`, `ModernWater.java:34-47,86-123`, `NeoStates.java:88-93` |
| L4 | O laço vertical de `ModernWater.update` é `y0 = floor(minY+0.001)` a `y1 = ceil(maxY-0.001)` | entidade com `minY` na célula de ar acima do repouso nunca visita a célula de água de baixo, ainda que ζ esteja acima dos pés | `ModernWater.java:71-72` |
| L5 | Só altura: não há velocidade do fluido | o empurrão vem de `getFlowVector` (diferença de nível entre vizinhos), que no mar aberto é 0 (fontes vizinhas de mesmo nível). A **velocidade orbital** da onda (a "dinâmica" que o pedido quer ver em itens e nadadores) não existe no pipeline. O `flow()` previsto no ADR não foi escrito | `ModernWater.java:102-121`, ADR-0003 §3 |
| L6 | Atenuação da costa, deslocamento horizontal | ζ no mundo é o **Euleriano** (inversa de Gerstner, `OceanField.eulerian`, Newton) com a atenuação do `ShoreField`, que depende dos blocos reais ao redor. `OceanWaves.surfaceY(dim,x,z)` (a API) é "em mar aberto, sem a costa" (`OW/api/OceanWaves.java:142-153`) e não serve perto da praia | `OW/api/OceanWaves.java` |
| L7 | `RENDER` é aplicado a **todos** os estágios | hoje ninguém chama `RENDER`. Se o Neo um dia passar a malha pelo pipeline (ADR §4), um estágio do OW registrado para todos os tipos deformaria a face de cima vanilla que o OW **esconde** (`BlockLiquidTopFaceMixin`) e que outras colunas (não mar aberto) ainda desenham, e rodaria nas threads de montagem do Angelica. O estágio tem de devolver `previous` quando `kind == RENDER` | `FluidPipeline.java:53` |

### 3.3 Proposta: um amostrador de superfície por coluna, no Neo (API 5)

O mar do OW precisa responder, **sem alocar** e **igual no servidor e no cliente**:

```java
public interface SeaSurface {                       // SPI do Neo States, registrado no init (deterministico)
    /** Y absoluto da superfície em (x,z) agora, ou NaN se a coluna não é mar com ondas. Euleriano, com costa. */
    double surfaceY(World w, double x, double z);
    /** Velocidade da água (blocos/tick) em (x,y,z); orbital: derivada de ζ e do deslocamento horizontal. */
    void velocity(World w, double x, double y, double z, double[] out);
    /** Quanto a crista passa do repouso (limite para o laço de colunas). */
    double crestMargin(World w);
}
```

No Neo:

1. `ModernWater.update/submerged/surface` passam a consultar o provedor primeiro. Para cada **coluna** `(x,z)` da caixa que o
   provedor reclama, a superfície é `ζ` no ponto da coluna mais próximo do centro da entidade (contínuo no movimento,
   sem degrau de célula); "dentro" é `ζ >= minY` (como no 1.21.1: `topo >= box.minY`), `height = max(ζ - minY, height)`;
   o laço de células e a exigência de "célula com água" saem desse caso (resolve L3 e L4). A coluna continua sendo
   mar só se a regra única do OW (`OceanColumns.isOpenSea`) disser; senão cai no caminho atual.
2. O empurrão da coluna reclamada é `velocity(...)` no lugar de `getFlowVector` (resolve L5). Itens, orbes e jogadores
   passam a ser levados pela mesma água que a malha desenha.
3. `NeoStates.surfaceAt(IBlockAccess, double x, double z)` (API 5) para Kunftig (boia), Player Backport (pose) e quem mais
   precisar; `surfaceY` por célula continua e, nas células reclamadas, devolve ζ no centro da coluna (degraus, mas sem
   quebrar os chamadores atuais, que usam inteiros).
4. A câmera (`MixinActiveRenderInfo_Water`) usa `surfaceAt(câmera.x, câmera.z)`: câmera abaixo da crista, névoa e
   sobreposição submersa; no vale, fora. É a "névoa coerente" do `ARQUITETURA.md`.
5. Os ganchos ficam atrás de uma opção própria do OW, sincronizada pelo servidor como as do Neo (o `modernWaterPhysics`
   do Neo desliga **todos** os seus desvios, o do provedor incluído, e é o do servidor que vale).

No OW (implementação do provedor), seguindo o `CLAUDE.md`: `OceanField.eulerian(x, z, t, count, sample, warm)` com um
`WaveSample` e um `OceanField` **por thread**; a atenuação pelo mesmo `ShoreField` do desenho (servidor: o de
`ServerSea`; cliente: o do cache `OceanChunks`), nunca `OceanWaves.surfaceY`; tempo: `world.getTotalWorldTime()/20` no
servidor e `ClientSea.CLOCK` no cliente (a regra "tempo do mar = total do mundo / 20" é cumprida pelo relógio do
cliente, que o estima; os dois lados diferem pela meia-latência, uns 0,1 s: com ω·A de ordem 0,1 bloco/s isso dá
centímetros, **[H]** não medido).

**Parada intermediária (se não se quiser tocar o Neo ainda):** um `FluidStage` com `deterministic() = true` que, nas
células de água do topo do mar aberto, devolve `ζ`-no-centro-da-célula convertida para fração da célula. Resolve só a
parte "o corpo percebe que a água sobe e desce" e só onde a onda fica **dentro** da célula (vales, e cristas até o
fim da célula). Limites L1 a L5 permanecem. Deve devolver `previous` para `RENDER`, para colunas que não são
mar aberto, e não pode usar o tempo do mundo no cliente.

### 3.4 Onde NÃO pôr o gancho

* Em `World.handleMaterialAcceleration` (o `0.014D` e o `normalize()` são do Et Futurum; as duas `minY < 0.0` da
  `isAnyLiquid` e `checkBlockCollision` são do BelowCore com `require = 2, allow = 2`).
* Em um segundo `@WrapOperation` do mesmo `INVOKE` de `Entity.handleWaterMovement`: o do Neo **não chama `original`**
  quando `modernWaterPhysics` está ligado, então quem estiver por dentro na cadeia nunca roda e quem estiver por fora
  decide sozinho; o resultado depende da ordem de aplicação, que não é fixa (duas implementações da mesma água).
* Em `moveEntityWithHeading`: o Neo já usa o primeiro `0.02D`; o Player Backport exige exatamente três `0.8D DMUL`;
  o Et Futurum e o Hodgepodge também têm gancho ali.

### 3.5 Barcos e fragata (fora do alcance do pipeline do Neo)

* `EntityNewBoat`: pontos de entrada candidatos, ambos de classe do Et Futurum (mixin tardio `@Pseudo` por
  `loadedMods.contains("etfuturum")`, no padrão de `NeoStatesLateMixins`): `public static getBlockLiquidHeight(World,x,y,z)`
  e `getLiquidHeight`, ou melhor o resultado de `checkInWater`/`getWaterLevelAbove` (que fixam `waterLevel`). Ambos são
  **por célula**; para flutuação literal o `waterLevel` do barco tem de vir do amostrador por ponto (o barco do 1.12
  usa um nível único para a caixa). **[H]**
* Barco vanilla: `World.isAABBInMaterial` (uma `@Inject` ou `@ModifyExpressionValue` na variável `d`, com `x,y,z` locais).
  O Kunftig ancora na **única** chamada de `isAABBInMaterial` de `EntityFishHook.onUpdate` e o BelowCore em `isAnyLiquid`:
  nada disso é afetado por um desvio dentro de `isAABBInMaterial`, mas não acrescente outra chamada nem mude a de `isAnyLiquid`.
* Fragata (Expeditions + Kunftig): não usa o Neo para flutuar; usa o `WaveField`/OW da API do OceanWaves. O Neo entra
  só no que está **a bordo** (ver NEO-6 e a nota da seção 8).

---

## 4. Conflitos de mixin

### 4.1 Hoje (OceanWaves x Neo States)

| Alvo | OceanWaves | Neo States | Conflito? |
|---|---|---|---|
| `BlockLiquid.shouldSideBeRendered` | `BlockLiquidTopFaceMixin`: `@Inject HEAD` cancelável, devolve `false` para `side == 1` em coluna de mar aberto (`OW/mixin/mixins/client/BlockLiquidTopFaceMixin.java:21-27`) | `MixinBlockLiquid_WaterFaces`: `@ModifyReturnValue` no `RETURN`, só troca `true` por `false` perto de água hospedada (`NS/.../MixinBlockLiquid_WaterFaces.java:27-35`) | **Compatível.** O OW devolve `false`; o Neo, se enxergar esse retorno, devolve `original` (`!original` retorna cedo). Se o `RETURN` do Neo alcança o retorno inserido pelo cancelamento do OW depende da ordem de aplicação; o resultado final é igual. **[H]** quanto à ordem; não observado em tempo de execução |
| `RenderGlobal.sortAndRender` | `@Inject RETURN` (pass 1) | nenhum | não |
| `NetHandlerPlayClient.handleTimeUpdate` | `@Inject HEAD` | nenhum | não |
| `TextureAtlasSprite` | `@Accessor frameCounter` | `GrayWater.Sprite` é **subclasse** (não mixin) | não: o acessor vale nas instâncias da subclasse |
| `Block.canRenderInPass`, `RenderBlocks.renderBlockByRenderType/getLiquidHeight`, `ActiveRenderInfo` | nenhum | Neo | não |

O OW usa `@Inject` simples (sem MixinExtras e sem `defaultRequire`); o Neo usa `injectors.defaultRequire = 1` e
MixinExtras 0.5.0 (UniMixins 0.3.1 está no pack). **[V]** (`mixins.oceanwaves.json`, `mixins.neostates.early.json`)

### 4.2 Prováveis, quando o OW ganhar física de entidades

| Alvo | Quem já mexe | Regra para o OW |
|---|---|---|
| `Entity.handleWaterMovement` (a chamada de `handleMaterialAcceleration`) | Neo `@WrapOperation` (sem `original`); `EntityItem` tem o próprio, também desviado | não pôr outro wrap; entrar pelo provedor do Neo (3.3) |
| `EntityXPOrb.handleWaterMovement` (`sq.N()`, próprio) | ninguém (Neo não desvia) | se o OW quiser empurrar orbes, é aqui (NEO-5) |
| `Entity.isInsideOfMaterial` | Neo `HEAD` cancelável + `@WrapOperation getBlock` | não pôr um terceiro; usar `submerged` do Neo |
| `EntityLivingBase.moveEntityWithHeading` | Neo `@ModifyConstant 0.02 ordinal 0`; Player Backport ASM (3 x `0.8 DMUL`); Et Futurum (`backlytra`, `isLadderFix`, `flyspeed`); Hodgepodge (`GravityFix`) | nenhuma constante nem DMUL novo |
| `World.isAABBInMaterial` | Neo (`getBlock`/`getBlockMetadata`, hospedada); Kunftig (a chamada em `EntityFishHook.onUpdate`) | `@Inject`/`@ModifyExpressionValue` dentro dele serve ao barco vanilla |
| `World.isAnyLiquid`, `checkBlockCollision` | BelowCore (`require = 2, allow = 2` em `minY < 0.0`); Neo (`getBlock`) | não tocar nas duas `minY < 0.0` |
| `ActiveRenderInfo.getBlockAtEntityViewpoint` | Neo `@ModifyReturnValue` | corrigir no Neo (3.3, item 4) |
| `EntityNewBoat` (Et Futurum) | ninguém | mixin tardio `@Pseudo` (3.5); respeitar `priority` e não usar `@Redirect` |
| `EntityItem.onUpdate` | Et Futurum `priority 1002 @Inject HEAD`; Neo `1000` e `1100` (par que desfaz o do EF); Galacticraft `@ModifyConstant 0.04` | um gancho com prioridade entre 1000 e 1100 no mesmo ponto é desfeito junto (doc do Neo) |

Mixins do Neo que usam `defaultRequire = 1` **derrubam o jogo** se o alvo mudar; um mixin tardio do OW em classe de
outro mod deve usar `@Pseudo` e `require = 0` com telemetria, para uma atualização do Et Futurum não impedir o cliente
de subir. **[H]** (política; o OW hoje não tem mixin em classe de mod).

---

## 5. Textura e cor da água do Neo States com o mar do OceanWaves

* **Textura.** O Neo troca o ícone da água pelos sprites cinza (`neostates:water_still_gray`, `water_flow_gray`), criados
  no `TextureStitchEvent.Pre` do atlas de blocos, mas **só enquanto um chunk é montado**
  (`ForgeHooksClient.getWorldRenderPass() >= 0`, `MixinBlockLiquid_GrayIcon.java:32`); fora disso `Blocks.water.getIcon`
  devolve o azul vanilla. O mar do OW é desenhado fora da montagem; por isso `WaterTexture.stillIcon` chama
  `GrayWater.icon(1)` por `MethodHandle` (`OW/client/WaterTexture.java:48-72`) e cai em `Blocks.water.getIcon(1,0)` sem o
  Neo. `GrayWater.icon(int)` existe e é público no jar alpha.10. **[V]** O quadro da animação vem do acessor
  `frameCounter` (válido para a subclasse `GrayWater.Sprite`, que carrega `loadSprite` com a animação do pack).
* **Cor.** O OW pinta cada coluna com `Blocks.water.colorMultiplier(w, x, y, z)` (`OW/client/OceanChunks.java:173,251`):
  passa pelo `BiomeEvent.GetWaterColor`, que o Neo responde com a tabela do 1.21.1 **quando** `GrayWater.active()`
  (sprite carregado). Cinza x cor do bioma dá a cor inteira; o OW mediu 13 x 13 px em `Akashic/Spawn`: com o Neo, antes
  (12,24,97) e depois (36,63,116), contra (31,52,94) sem ondas (`OW/docs/TESTES-AMBIENTE-REAL.md`, achado 5).
* **Névoa e sobreposição submersa.** São do Neo (eventos `FogColors`/`FogDensity`, `isInsideOfMaterial`), e dependem da
  superfície **plana** por célula: câmera dentro de uma crista vê a malha do OW por baixo, sem névoa (NEO-11).
* **Luz.** `modernWaterLight` (1 por bloco em vez de 3) muda a luz guardada abaixo do mar; o OW lê a luz das colunas,
  então o mar mais claro vem do Neo (sem ação). **[H]** (não medido nesta auditoria)
* **Riscos:** com `visual.modernWaterColor=false` o Neo não registra os sprites e o OW cai no azul vanilla com a cor
  vanilla (coerente). O sprite novo a cada recarga de recursos é lido de novo a cada `bind()` (campos `volatile`), sem
  cache obsoleto. Nenhuma ação necessária.

---

## 6. Consumidores da API de célula (importa se a API mudar)

Todos chamam com `(x, y, z)` **inteiros** e só têm o `surfaceY`/`isWaterAt` por célula:

| Consumidor (jar do pack) | Uso | Evidência |
|---|---|---|
| Kunftig `2bb00b3` `compat/NeoStatesBridge` | `isWaterAt` + `surfaceY` (anzol/boia); `getMethod` na classe `NeoStates` (que não é do Bukkit) | `javap -c` |
| Akashic Player Backport `0.1.0-alpha.30-push-rc1` | `WaterPoseConditions` e `SwimmingVerticalHooks` chamam `NeoStatesBridge.isWaterAt` e `.surfaceY`; sem o Neo, leem `getBlock().getMaterial()` e `getLiquidHeightPercent` direto | `javap -c` |
| AkashicThePatcher `1.1.0-beta.53` módulo `neostates` | `NeoStatesWaterPassHooks` chama `NeoStates.canHoldWater`; o transformador mexe na classe `com.akashic.neostates.mixin.early.client.MixinWorldRenderer_WaterPass` e na `RenderBlockAtPosEvent` do DragonAPI | `javap -c` |

Implicações: (a) a API de célula existe e tem usuários reais: não pode ser quebrada (acrescentar `surfaceAt`, não trocar
`surfaceY`); (b) com um estágio por célula (parada intermediária) pose de nado e boia passam a ver ondas com a
quantização de L1 e a cegueira de L3; com `surfaceAt`, só depois que o Player Backport e o Kunftig migrarem;
(c) renomear `MixinWorldRenderer_WaterPass` ou `canHoldWater` quebra o módulo do Patcher (o mixin só é registrado sem o
Angelica, `NeoStatesCore.java:54`; no pack real o Angelica está presente, então o transformador provavelmente fica
inerte: **[H]**, não executado).

Nota Expeditions: `Expeditions/docs/TESTES.md:134` registra, no convés da chalupa, `/neostates sonda`: "pés sem
fluido, na água: false/false". Com ondas literais isso deixa de ser garantido: o convés sobe com o casco, mas ζ(x,z) no
ponto do jogador pode passar dos pés se o casco inclina (físico, se o convés fica abaixo da água; a regra do CLAUDE do
Expeditions já proíbe piso abaixo da linha d'água). O amostrador deve ignorar colunas **cobertas por um casco**, ou o
plugin deve fazer o jogador a bordo contar como seco; decisão de projeto, não é bug do Neo.

---

## 7. Achados

### NEO-1 (alto) O encaixe de ondas do Neo existe, mas é por célula inteira
`FluidStage.height` recebe `(IBlockAccess, int x, int y, int z, ...)` e devolve `[0,1]`; não há tempo, posição contínua,
velocidade, `flow` nem `cornerHeight` (o ADR-0003 os prevê; o código não). `RENDER` não tem chamador. Nenhum estágio
registrado. Sozinho, não reproduz ζ(x,z,t) com deslocamento horizontal e costa.
Evidência: `NS/fluid/FluidStage.java`, `FluidPipeline.java:42-58`, `grep FluidPipeline|HeightKind` (só `ModernWater` e `NeoStates.surfaceY`).

### NEO-2 (alto) Cristas acima do topo da água são invisíveis ao Neo
Superfície só existe onde `FluidResolver` acha água; a célula acima do último bloco é ar. Corpo (`update`), olhos
(`submerged`), câmera e `surfaceY` não veem a crista; o laço vertical de `update` nem visita a célula de água de baixo
quando `minY` está acima do repouso.
Evidência: `ModernWater.java:34-56,69-74,86-123`, `FluidResolver.java:18-31`, `NeoStates.java:88-93`.

### NEO-3 (alto) Barcos não passam pelo Neo
Neo não tem física de barco. `EntityNewBoat` (Et Futurum, o barco do pack por `replaceOldBoats=true`) lê `getBlock`,
`getBlockMetadata` e `1 - getLiquidHeightPercent` direto, num laço de células; o vanilla lê `isAABBInMaterial` (fonte em
y+1 e `meta/8`). Nenhum estágio os alcança; precisam de ganchos próprios com o amostrador por ponto.
Evidência: `javap -c ganymedes01/etfuturum/entities/EntityNewBoat` (`checkInWater`, `getBlockLiquidHeight`);
`javap -c xi` e `ahb.b(azt,awt)` do `minecraft.jar` do pack; `etfuturum/blocksitems.cfg`; `grep -i boat` no Neo.

### NEO-4 (medio) Condições para registrar um estágio sem quebrar
`FluidPipeline.register` lança `IllegalStateException` depois de `freeze()`, que o Neo chama no próprio `postInit`
(`NeoStatesMod.java:68`): o OW tem de registrar em `preInit`/`init` (todos os `init` rodam antes dos `postInit`); o
estágio tem de ser no-op para `RENDER` e para colunas fora do mar aberto; no cliente tem de usar `ClientSea.CLOCK`; e
o conjunto todo some se `physics.modernWaterPhysics` estiver desligado no servidor (a opção é do Neo e vem do servidor).
Evidência: `FluidPipeline.java:26-39,53`, `NeoStatesMod.java:66-70`, `NeoConfig.java:41-57`, `OW/client/ClientSea.java:85-91`.

### NEO-5 (medio) Sem velocidade orbital; itens não acompanham cristas; orbes ficam de fora do `update`
O empurrão do `ModernWater.update` é o `getFlowVector` (zero em fontes vizinhas de mesmo nível). A sustentação do item
é constante (0,0005/tick até 0,06) e só liga com mais de 0,1 de água sobre os pés. `EntityXPOrb` tem
`handleWaterMovement` próprio (`sq.N()` no `minecraft.jar`) que o Neo não desvia: orbes nunca passam por
`ModernWater.update` (flutuam pelo `submerged`, mas sem empurrão do Neo e com o `0.014` do Et Futurum).
Evidência: `ModernWater.java:102-121`, `WaterPhysics.java:36-54`, `MixinEntityXPOrb_Float.java:26-34`, `javap -c sq`
(`N()` chama `ahb.a(azt,awt,sa)`), `javap -c xk` idem para `EntityItem` (esse o Neo desvia).

### NEO-6 (medio) A API de célula já tem três usuários reais; a doc está atrasada
Kunftig, Player Backport alpha.30 (pose de nado) e o Patcher consomem a API com inteiros. `COMPATIBILIDADE.md` ainda
diz que o Player Backport "a combinar". Qualquer API nova tem de ser aditiva (API 5).
Evidência: `javap -c` de `NeoStatesBridge` (Kunftig e Player Backport), `WaterPoseConditions`, `NeoStatesWaterPassHooks`.

### NEO-7 (baixo) Um único alvo de mixin em comum, compatível
`BlockLiquid.shouldSideBeRendered`: OW `HEAD` cancelável x Neo `RETURN`. Resultado igual em qualquer ordem. Ordem de
aplicação não observada em jogo.
Evidência: `OW/.../BlockLiquidTopFaceMixin.java:21-27`, `NS/.../MixinBlockLiquid_WaterFaces.java:27-35`.

### NEO-8 (baixo) Textura e cor convivem
`GrayWater.icon(1)` (público, presente no jar) + `colorMultiplier` pelo evento do bioma; fallback azul vanilla sem o
Neo ou sem a opção. Medido pelo próprio OW no spawn do TEOF (13 x 13 px).
Evidência: `OW/client/WaterTexture.java:48-72`, `NS/client/GrayWater.java:33-45`, `WaterColorHandler.java`.

### NEO-9 (baixo) Jars alpha.10 coerentes com o repositório; documentação divergente
Pack e servidor com o mesmo SHA-256; mesmo conjunto de classes, lista de mixins, constantes e JSONs. Divergências só
de documentação (ADR-0003 x código, `COMPATIBILIDADE.md` x jar do Player Backport, Javadoc da API).
Evidência: seção 1.

### NEO-10 (baixo) Alocação e custo por entidade no caminho quente
`ModernWater.update` faz `new double[3]` a cada chamada (por entidade, por tick) e cada célula custa de 6 a 9 leituras de
bloco (`surface`, `FluidPipeline.height` que resolve a célula e a de cima). O OW tem a regra "nada alocado nos laços
quentes": o provedor novo deve reaproveitar buffers por thread, e convém tirar o `double[3]` do Neo no mesmo trabalho.
Evidência: `ModernWater.java:83`, `FluidPipeline.java:42-58`, `FluidResolver.java:18-31`. (Impacto em FPS não medido.)

### NEO-11 (baixo) Câmera dentro de uma crista não vê névoa
Com ondas, a câmera abaixo da malha do OW mas acima de 8/9 do bloco é "fora d'água" para o Neo: sem névoa, sem
sobreposição, `isEyeInWater` falso nos shaders. Some junto com NEO-2 se a câmera usar o amostrador por ponto.
Evidência: `MixinActiveRenderInfo_Water.java`, `UnderwaterFog.java` (`isInsideOfMaterial`).

### NEO-12 (baixo, hipótese) O módulo `neostates` do Patcher depende de nomes internos do Neo
Renomear `MixinWorldRenderer_WaterPass` ou mudar `canHoldWater` afeta o Patcher; sem o Angelica o mixin existe, com o
Angelica (pack real) não. Não foi executado.
Evidência: `javap -c NeoStatesWaterPassTransformer`/`Hooks`; `NeoStatesCore.java:54`.

---

## 8. Plano sugerido (ordem)

1. **Neo, API 5**: `SeaSurface` (SPI) + `surfaceAt(IBlockAccess, double, double)`; `ModernWater.update/submerged/surface`
   por coluna quando há provedor; `velocity` no lugar do `getFlowVector` nas colunas reclamadas; câmera por ponto;
   desvio de `EntityXPOrb.handleWaterMovement`; `new double[3]` fora. Testes puros em `WaterPhysicsTest`/novo teste do
   provedor com um provedor falso (crista acima do topo, vale, `minY` acima do repouso).
2. **OW**: provedor com `OceanField.eulerian`, `ShoreField` do desenho, relógio por lado, buffers por thread; registro no
   `init`; opção sincronizada; teste de fumaça "mesma ζ do desenho" (amostrar a malha e o provedor no mesmo `(x,z,t)`).
3. **Estágio por célula** só como degrau, se o item 1 atrasar; nunca para `RENDER`.
4. **Barcos**: mixin tardio no `EntityNewBoat` e desvio em `isAABBInMaterial` com o amostrador por ponto (frente BOAT).
5. **Player Backport e Kunftig**: migrar para `surfaceAt` (pose de nado e boia acompanham a onda).
6. Atualizar `COMPATIBILIDADE.md` e o ADR-0003 do Neo com o que mudou (frente de documentação).

---

## 9. O que ficou por verificar

* Em jogo, com o OW instalado: ordem de aplicação dos dois mixins de `shouldSideBeRendered` (o resultado é o mesmo, a
  ordem não foi observada). **[a verificar]**
* O `Boat` do 1.21.1 (força e estados) e o `EntityArrow.onUpdate` do pack (o `0.8F ordinal 0` do Neo): não relidos no
  `minecraft.jar` desta auditoria. **[a verificar]**
* Diferença real entre o ζ do cliente (`OceanClock`) e o do servidor para a física: estimada em ordem de centímetros,
  não medida. **[a verificar]**
* O comportamento de `ModernWater.update` com muitas entidades (custo por tick) e o que o gancho por coluna acrescenta.
  **[a verificar]** (frente PERF)
* Se o módulo `neostates` do Patcher fica mesmo inerte com o Angelica. **[a verificar]**
* Decisão de projeto: quem está **dentro de um casco** deve contar como na água quando ζ passa do piso? (seção 6).
