# Frente BOAT: por que o barco fica reto no mar do OceanWaves

Auditoria sem alterar código. Tudo que está marcado **[verificado]** foi lido no bytecode (javap) ou no código-fonte;
**[hipótese]** é inferência ainda não conferida em jogo. Nenhum número de FPS ou resultado de jogo foi medido aqui.

Material de apoio (desmontagens anotadas com nomes MCP, geradas nesta auditoria):

* `/home/user/work/tmp/boat/mc/xi.annot`: `EntityBoat` vanilla (`xi.class` do `bin/minecraft.jar` do cliente TEOF),
  anotado com o `mappings/v1_7_R4/obf2pkgmcp.srg` que vem dentro do `server.jar` do Crucible.
* `/home/user/work/tmp/boat/mc/bne.javap`, `bjb.javap`, `bjk.javap`: `RenderBoat`, `NetHandlerPlayClient`,
  `EntityClientPlayerMP`.
* `/home/user/work/tmp/boat/efr/ganymedes01/etfuturum/**.ann`: classes do barco do Et Futurum Requiem
  (`etfuturum-2.6.64-GTNH-git.1+77e9997e09.jar`), anotadas srg → MCP.
* Scripts: `/home/user/work/tmp/boat/scripts/` (anotação, varredura de jars e refmaps).

Observação de método: o descompilador (CFR) foi bloqueado pelo ambiente; tudo foi lido com `javap -c -p` e
anotado por script com os mapeamentos srg/MCP do próprio Crucible.

---

## Resumo

1. **No TEOF o barco na água não é o `EntityBoat` vanilla, é o `EntityNewBoat` do Et Futurum** (porte do barco do
   1.12): `replaceOldBoats=true` e `enableNewBoats=true` no cliente e no servidor, e o `ServerEventHandler.entityAdded`
   troca todo `EntityBoat` de classe exata por um `EntityNewBoat` no `EntityJoinWorldEvent`. **[verificado]**
2. Os dois barcos calculam a linha d'água **só pelos blocos de água** (`World.getBlock`/`getBlockMetadata`), nunca
   pela superfície do OceanWaves; o resultado é um escalar plano por tick, quantizado no topo do bloco. E nenhum dos
   dois tem grau de liberdade de rotação na física nem desenha caturro/balanço: o vanilla zera `rotationPitch` a cada
   tick; os dois renderizadores só aplicam a guinada e o tremor de dano. **[verificado]**
3. **Nada no OceanWaves alimenta entidades** com a superfície: os únicos consumidores de `OceanField.eulerian` no mod
   são `OceanWaves.surfaceY` (API, sem chamadores no mod) e o `/ondas aqui`; não há mixin em entidade. **[verificado]**
   A matemática para isso já existe e é barata (altura com inversa de Gerstner por Newton, gradiente, velocidade
   orbital, `HeightPatch`, `SurfaceFit`, atenuação da costa, a mesma hora nos dois lados).
4. Pior que "não balança": com ondas fortes, ligar só a linha d'água do barco do Et Futurum à onda sem mexer no resto
   **expulsa o jogador**: no cavado o casco fica abaixo do topo do bloco de água, `getUnderwaterStatus` devolve
   `UNDER_WATER`, `outOfControlTicks` sobe e com 60 ticks o servidor chama `removePassengers()`. **[verificado no
   bytecode; o efeito em jogo é hipótese]**
5. Proposta: um corpo flutuante de verdade (empuxo integrado em colunas do casco contra ζ(x, z, t), momentos de
   restauração de balanço e caturro, massa adicionada, amortecimento sobre a velocidade relativa à água orbital),
   em Java 8 no núcleo do OceanWaves, ligado por **mixins tardios no `EntityNewBoat`** (nomes próprios do Et Futurum,
   sem remapeamento) e por um mixin no `EntityBoat` vanilla para as subclasses e para packs sem o Et Futurum; a
   inclinação desenhada no `NewBoatRenderer.setupRotation` e no `RenderBoat.doRender`. Os graus verticais (arfagem,
   balanço, caturro) são integrados **localmente em cada lado** com o mesmo campo e a mesma hora; só x, z e guinada vêm
   da autoridade de rede.

---

## 1. A física do `EntityBoat` do 1.7.10 (vanilla)

Fonte: `xi.class` do `minecraft.jar` do cliente (o mesmo nome ofuscado no servidor: o `obf2pkgmcp.srg` do Crucible
mapeia `xi` → `net/minecraft/entity/item/EntityBoat`). O Forge não tem fontes descompiladas no cache do Gradle
(`/root/.gradle` só tem o wrapper; `OceanWaves/build` não existe), por isso a leitura foi no bytecode.

### 1.1 Empuxo: 5 fatias do AABB contra blocos de água **[verificado]**

`EntityBoat.onUpdate` (`func_70071_h_`, `xi.h()V`, `xi.annot:351`), offsets 64 a 215:

```text
n = 5; d0 = 0
para i em 0..4:
    y0 = minY + (maxY − minY)·i/5 − 0,125
    y1 = minY + (maxY − minY)·(i+1)/5 − 0,125
    fatia = AABB(minX, y0, minZ, maxX, y1, maxZ)
    se world.isAABBInMaterial(fatia, Material.water): d0 += 1/5
```

`World.isAABBInMaterial` (`func_72830_b`, `ahb.b(azt, awt)Z`) percorre os blocos do AABB e devolve verdadeiro se
algum bloco de água tem topo ≥ `minY` da fatia, com topo `(y+1)` para a fonte e `(y+1) − meta/8` para água corrente
(offsets 98 a 163: `bipush 8; if_icmpge`, `ldc 8.0; ddiv; dsub`, `dcmpl` com `azt.b`=`minY`). **[verificado]**
Ou seja, **para a fonte o vanilla usa o topo cheio do bloco (y+1), não 8/9**: a referência dele fica 1/9 de bloco acima
do nível de repouso que o OceanWaves desenha (`OceanColumns.SOURCE_TOP = 8/9`,
`OceanWaves/src/main/java/com/akashic/oceanwaves/world/OceanColumns.java:16`).

A lei vertical (offsets 814 a 880):

```text
se d0 < 1:  motionY += 0,04·(2·d0 − 1)          // afunda abaixo de meio, sobe acima
senão:      se motionY < 0: motionY /= 2;  motionY += 0,007
```

e no fim do tick `motionY *= 0,95` (offset 1499) e `motionX, motionZ *= 0,99`. Consequências:

* O equilíbrio é onde `d0` cruza 0,5: com fatias de 1/5, o barco oscila entre 2 e 3 fatias molhadas, preso ao topo
  dos blocos. **Um escalar só, sem posição no casco**: não existe momento, então não existe balanço nem caturro.
* É uma mola por blocos, não hidrostática: a "gravidade" é 0,04 bloco/tick² = 16 m/s² (com 1 bloco = 1 m e 20 ticks
  por segundo), diferente do g = 9,81 que o OceanWaves usa na dispersão das ondas (`Dispersion`). **[verificado no
  bytecode; a consequência física é dedução]**
* `rotationPitch = 0` a cada tick (offset 1518, `fconst_0; putfield rotationPitch`), e a guinada é puxada para a
  direção do movimento (offsets 1523 a 1639, `atan2`, limitada a ±20° por tick).

### 1.2 Quem move o barco **[verificado]**

* Barco **vazio** no cliente: `if (worldObj.isRemote && isBoatEmpty)` (offsets 506 a 519) interpola até a posição do
  servidor em `boatPosRotationIncrements` passos (`setPositionAndRotation2`, `func_70056_a`, `xi.annot:251`, põe
  `n + 5` passos) e sai sem física.
* Barco **tripulado pelo jogador local**: o `NetHandlerPlayClient.handleEntityAttach` (`func_147243_a`, `bjb.a(hx)`,
  `bjb.javap:1994`) chama `setIsBoatEmpty(false)` (`func_70270_d`) quando quem monta é o próprio jogador. Aí o cliente
  roda a física inteira, e `setPositionAndRotation2` ignora o servidor enquanto a diferença for ≤ 1 bloco² (offsets 64
  a 77: `dconst_1; dcmpg; ifle → return`); acima disso guarda o alvo, mas o ramo de interpolação só roda com o barco
  vazio, então o cliente do piloto fica com a própria simulação.
* O cliente do piloto **não manda a posição**: `EntityClientPlayerMP.onUpdate` (`bjk.h()V`), montado, envia só
  `C05PacketPlayerLook` e `C0CPacketInput` (strafe, forward, pulo, agachar). O servidor simula o barco com essas
  entradas (`moveForward`/`moveStrafing` do jogador, offsets 881 a 997 do `onUpdate`). Resultado: as duas pontas
  simulam; o cliente do piloto vê a dele, os outros veem a do servidor, interpolada.

### 1.3 Desenho **[verificado]**

`RenderBoat.doRender` (`func_76986_a`, `bne.a(xi, DDDFF)V`): `glTranslatef`, `glRotatef(180 − yaw, 0, 1, 0)`, o tremor
de dano `glRotatef(sin(t)·t·dano/10·dir, 1, 0, 0)`, escalas, `ModelBoat.render`. Nenhum caturro nem balanço.

---

## 2. O barco novo do Et Futurum Requiem (`EntityNewBoat`)

Jar: `/home/user/ext/teof/x/mods/etfuturum-2.6.64-GTNH-git.1+77e9997e09.jar` (o mesmo no servidor). Porte do
`EntityBoat` do 1.12, com assento extra (`EntityNewBoatSeat`). AABB 1,375 × 0,5625 (`setSize(1.375f, 0.5625f)`, no
construtor). **[verificado]**

### 2.1 Ele é o barco que existe no TEOF **[verificado]**

* `config/etfuturum/blocksitems.cfg`: `enableNewBoats=true`, `replaceOldBoats=true` (cliente: linhas 14 e 37;
  servidor: as mesmas).
* `ServerEventHandler.entityAdded(EntityJoinWorldEvent)`, offsets 131 a 208: se as duas opções estão ligadas e
  `entity.getClass() == EntityBoat.class` (classe **exata**), cria um `EntityNewBoat` no lugar. Subclasses
  (`ic2.core.item.EntityIC2Boat`, os barcos do Thaumic Horizons) não são trocadas.

### 2.2 Linha d'água: blocos, um escalar, máximo sobre o AABB **[verificado]**

* `getLiquidHeight(world, x, y, z) = y + getBlockLiquidHeight(...)`; `getBlockLiquidHeight` = 1 se a fonte tem água em
  cima, senão `1 − BlockLiquid.getLiquidHeightPercent(meta)`, ou seja 8/9 para a fonte com ar em cima (bate com o
  `SOURCE_TOP` do OceanWaves).
* `checkInWater()`: percorre as colunas do AABB na faixa de `minY`, `waterLevel = max(getLiquidHeight)` e devolve se
  algum topo de água passa de `minY`.
* `getUnderwaterStatus()`: se algum bloco de água tem topo acima de `maxY + 0,001`, devolve `UNDER_WATER` (ou
  `UNDER_FLOWING_WATER` com meta ≠ 0).
* `getBoatStatus()`: `UNDER_*` primeiro, depois `IN_WATER`, depois `ON_LAND` (atrito do chão) ou `IN_AIR`.
* `getWaterLevelAbove()`: o topo da água acima do casco, usado ao cair na água vindo do ar.

### 2.3 Lei vertical (`updateMotion`, privado) **[verificado]**

```text
g = −0,04 (bloco/tick²)
IN_WATER:              d2 = (waterLevel − minY)/altura, momentum 0,9
UNDER_FLOWING_WATER:   g = −0,0007, momentum 0,9
UNDER_WATER:           d2 = 0,01, momentum 0,45
IN_AIR → água:         reposiciona em getWaterLevelAbove() − altura + 0,101, motionY = 0, status IN_WATER
motionX, motionZ, deltaRotation *= momentum
motionY += g
se d2 > 0: motionY += d2·(0,04/0,65);  motionY *= 0,75
```

O equilíbrio é d2 = 0,65: a linha d'água fica a 0,65 × 0,5625 ≈ 0,366 bloco acima do fundo do AABB. Para um casco
caixa é a lei de Arquimedes (força linear no calado), com densidade relativa 0,65 e um amortecimento de 25% por tick
inventado; o defeito não é a lei, é a **entrada**: `waterLevel` é o máximo dos topos de bloco (plano, em degraus) e o
casco é um ponto sem rotação.

### 2.4 Inclinação: não existe **[verificado]**

* A física não tem estado de balanço nem de caturro.
* `rotationPitch` só é interpolado de rede (`tickLerp`, campo `lerpXRot`) para barcos de outros; nada o calcula.
* `NewBoatRenderer.setupRotation(EntityNewBoat, float, float)`: `glRotatef(180 − yaw, 0, 1, 0)`, o tremor de dano
  `glRotatef(..., 1, 0, 0)` e `glScalef(−1, −1, 1)`. Sem caturro, sem balanço.

### 2.5 Autoridade: o cliente do piloto manda **[verificado]**

* `onUpdate` (`func_70071_h_`), offsets 646 a 687: no cliente, se `canPassengerSteer()` e quem controla é o
  `EntityClientPlayerMP`, envia `BoatMoveMessage(x, y, z, yaw, pitch, entityId, dimensão)` ao servidor a cada tick.
* `BoatMoveHandler.onMessage`: no servidor, se o remetente é o `riddenByEntity` do barco, aceita a posição e a rotação
  (`setPositionAndRotation`) e responde com `S18PacketEntityTeleport`; só recusa se
  `|Δposição² − velocidade²| > 100` (anti-trapaça frouxo, "Vehicle moved wrongly").
* `canPassengerSteer()`: verdadeiro sempre no servidor; no cliente, verdadeiro se quem controla é um `EntityPlayer`
  montado direto no barco. Logo o servidor também simula (e é sobrescrito pela mensagem); barcos vazios são simulados
  só no servidor e interpolados nos clientes em 5 passos (`func_70056_a` põe `lerpSteps = 5`; `tickLerp`).
* Consequência para as ondas: **o `rotationPitch` já viaja** (na `BoatMoveMessage` e nos pacotes de entidade, que
  levam o pitch em um byte, ~1,4°); **o balanço não tem campo** em `Entity` e precisaria de pacote próprio ou de ser
  calculado em cada cliente.

### 2.6 Onde ele lê a água

Só em `World.getBlock`, `World.getBlockMetadata` e `BlockLiquid.getLiquidHeightPercent` (os quatro métodos de 2.2).
Não passa pelo `isAABBInMaterial` nem pelo `handleMaterialAcceleration` (os pontos que o Neo States embrulha,
`Neo-States/src/main/java/com/akashic/neostates/mixin/early/common/MixinWorld_Water.java:69-134`). **[verificado]**

### 2.7 A armadilha do cavado **[verificado no bytecode; efeito em jogo: hipótese]**

Se só a altura do barco passar a seguir ζ (por exemplo trocando `waterLevel`), no cavado de uma onda o casco desce
abaixo do topo do bloco (`restLevel = nível − 1 + 8/9`). Então:

1. `getUnderwaterStatus` vê bloco de água acima de `maxY` → `UNDER_WATER`;
2. `updateMotion` usa `d2 = 0,01` e momentum 0,45 (o barco quase para e afunda devagar);
3. `onUpdate`, offsets 17 a 76: `outOfControlTicks += 1` e, no servidor, com 60 ticks (3 s) `removePassengers()`.

Com Hs de 2 a 4 m os cavados passam de 1 m abaixo do repouso; um casco de 0,56 m de altura com 0,37 m de calado
fica "debaixo d'água" para o Et Futurum. **O status tem de vir da superfície das ondas, não dos blocos.**

Na crista (acima do topo do bloco) o oposto: o AABB está em blocos de ar, `checkInWater` falha, status `IN_AIR`, o
barco cai até o topo dos blocos. Junto com 2.3 e 2.4, **é exatamente por isso que o barco fica reto e parado no nível
do bloco**.

---

## 3. O que o OceanWaves já tem (e o que falta)

### 3.1 Nada alimenta entidades **[verificado]**

* `grep` em `src/main/java` e `core/src/main/java` por `Entity`, `boat`, `barco`, `buoy`, `empuxo`, `surfaceY`,
  `eulerian(`: os únicos usos de `OceanField.eulerian` são
  `api/OceanWaves.java:146-153` (`surfaceY`, sem chamadores no mod) e `server/OceanCommand.java:217-240`
  (`/ondas aqui`, que já monta a costa com `OceanWaves.shore` e mede o tempo da consulta).
* Os mixins do mod são só de cliente e de desenho (`mixins.oceanwaves.json`: `BlockLiquidTopFaceMixin`,
  `RenderGlobalOceanPassMixin`, `NetHandlerPlayClientTimeMixin`, `TextureAtlasSpriteAccessor`; `"mixins": []`,
  `"server": []`).
* `docs/ARQUITETURA.md:155` lista "Física de entidades: barcos vanilla (heave pelo `SurfaceFit`, inclinação desenhada)"
  como próxima fase; `README.md:49`: "Barcos vanilla, entidades ... próximas fases".

### 3.2 O que está pronto para usar **[verificado no código]**

| Peça | Onde | O que dá |
|---|---|---|
| Altura num ponto com a inversa de Gerstner | `core/.../OceanField.java:110-136` (`eulerian`) | acha x₀ com x₀ + D(x₀) = x por Newton (converge quadrático, partida quente com o x₀ do tick anterior), devolve η e preenche a `WaveSample` |
| Amostra lagrangiana completa | `OceanField.java:43-99` (`lagrangian`) e `WaveSample` | η, deslocamento (dx, dz), gradiente (hx, hz), jacobiano (dxx, dxz, dzz), **velocidade orbital (vx, vy, vz)**, atenuação; com costa, as derivadas já levam o gradiente da atenuação |
| Retalho de alturas para um casco | `core/.../HeightPatch.java:42-121` | avalia a grade lagrangiana uma vez por tick (laço de tabelas do `GridEvaluator`) e cada consulta inverte sobre a interpolação bilinear, sem trigonometria; `blend` interpola entre dois instantes |
| Plano por mínimos quadrados | `core/.../SurfaceFit.java` | altura no centro + inclinações (método do Atlas, GDC 2019): bom para itens e entidades pequenas; para o barco serve de referência/estado inicial, não como lei de movimento |
| Costa | `api/OceanWaves.java:94-140` (`shore`, `shoreMargin`) no servidor; no cliente, a `ShoreField` de cada chunk (`client/OceanChunks.java:25-43`, campo `Column.shore`) | a mesma atenuação que a malha desenha |
| Região e nível de repouso | `OceanWaves.region(dim)`, `restLevel(dim)` (servidor); `ClientSea.region()`, `restLevel()` (cliente) | onde há ondas e o Y de repouso de cada mundo |
| A mesma hora | servidor: `OceanWaves.time(dim) = getTotalWorldTime()/20` (`api/OceanWaves.java:69-72`); cliente: `ClientSea.time(partial)` (`client/ClientSea.java:89-95`), pelo `OceanClock` (estimado dos pacotes de tempo) ou `(worldTime + partial)/20` | ζ igual nos dois lados (componentes iguais bit a bit, mensagem `MessageSeaState`) |
| Coluna de mar aberto | `world/OceanColumns.isOpenSea` | a regra única de onde há onda |

Custo de uma consulta (bancada do núcleo neste contêiner, `docs/ARQUITETURA.md:133-134`, não medido em jogo):
**~7,4 µs fria / ~4 µs quente no Java 8, ~4,8 / ~2,5 µs no Java 21** com 64 componentes; uma grade 17 × 17 do
`HeightPatch`, ~55 µs (Java 8) / ~42 µs (Java 21). Para um barco com 12 colunas: ~30 a 90 µs por tick e por barco
consultando ponto a ponto, ou uma grade por tick e consultas quase grátis. **[estimativa a partir da bancada]**

### 3.3 Lacunas a resolver na ligação

* **Atenuação no cliente fora do desenho.** `OceanChunks` é `final class` de pacote, o mapa é `HashMap<Long, Column>`
  (cada `get` encaixota um `Long`) e a `ShoreField` só é montada para chunks que a malha precisa. Falta um acessor
  `attenuationAt(x, z)` sem alocação (guardar a coluna do barco entre ticks). Mesma thread: no 1.7.10 o tick do
  cliente e o desenho rodam na thread principal. **[verificado: estrutura; acessor: proposta]**
* **Atenuação no servidor.** `OceanWaves.shore(...)` aloca dois vetores e varre colunas a cada chamada: guardar uma
  `ShoreField` por barco, com folga `shoreMargin(dim)` (como o Expeditions faz para o navio,
  `docs/ARQUITETURA.md:104-109`), refeita quando o barco sai do retângulo ou o mar muda.
* **O espectro desenhado perto da câmera não é o completo.** O `SpectralLod` some com as componentes de λ abaixo de
  2,5·s a 5·s, com s = `OceanConfig.minSpacing` = 1,0 (`OceanConfig.java:57`, `client/OceanRenderer.java:249-251`,
  `core/.../SpectralLod.java`): perto da câmera faltam as ondas de λ < 2,5 m e as de 2,5 a 5 m vêm atenuadas. Para o
  barco seguir "a superfície desenhada" à letra, a física deveria usar o mesmo peso por λ com o s mínimo (é função
  só de λ e da configuração, então determinística), mas o servidor não conhece o `minSpacing` do cliente.
  **[verificado: o filtro; o impacto é pequeno porque as componentes curtas têm amplitude pequena: hipótese]**
* **Precisão entre JVMs.** `OceanField` usa `Math.sin/cos` (a síntese usa `StrictMath`, a consulta não): cliente
  Java 8 e servidor Java 21 podem diferir na última casa. Para um sistema amortecido isso não diverge. **[verificado:
  o uso de `Math`; a irrelevância é dedução]**

---

## 4. Proposta: onde e como ligar

### 4.1 O corpo flutuante (núcleo, Java 8, sem Minecraft)

Uma classe nova no núcleo (por exemplo `core/FloatingHull`), no espírito do `FloatingBody` do Expeditions
(`Expeditions/core/src/main/java/com/akashic/expeditions/physics/FloatingBody.java`), que não dá para reaproveitar
direto: o `HullColumns` de lá é `record` (Java 21) e o cliente é Java 8. Estado: `y, vy, roll, rollRate, pitch,
pitchRate` no referencial do barco sem a guinada (mesma convenção do Expeditions/Kunftig: x de través, y para cima, z
para a proa).

Por subpasso dt (2 a 4 por tick):

1. Colunas do casco: N pontos (por exemplo 4 × 3 sobre o fundo de ~1,75 × 1,25 m do barco do 1.12, cada um com área
   Aᵢ, fundo bᵢ e topo tᵢ no referencial do corpo). Posição de cada coluna no mundo: guinada da entidade + caturro e
   balanço do corpo.
2. Superfície em cada coluna: ηᵢ = `eulerian(xᵢ, zᵢ, t)` com a atenuação da costa (partida quente com o x₀ da
   coluna no subpasso anterior; vetores reaproveitados, nada alocado). Opcional: ηᵢ nos dois extremos do tick e
   interpolação linear nos subpassos, como no Expeditions.
3. Calado de cada coluna: dᵢ = clamp(restLevel + ηᵢ − yfundoᵢ, 0, tᵢ − bᵢ).
4. Forças e momentos hidrostáticos: F = ρ g Σ Aᵢ dᵢ − m g; M_balanço = ρ g Σ Aᵢ dᵢ uᵢ; M_caturro = −ρ g Σ Aᵢ dᵢ vᵢ
   (sinais na convenção acima). Com o mesmo g = 9,81 do resto do OceanWaves, **sem** a gravidade de 0,04 bloco/tick²
   da entidade (16 m/s²) enquanto o barco estiver no mar com ondas.
5. Amortecimento sobre a velocidade **relativa à água**: a vertical de cada coluna menos `vy` orbital da
   `WaveSample` (linear + quadrático, como `HEAVE_DAMPING`/`ROLL_EDDY` do Expeditions); massa e inércia adicionadas.
6. Passo semi-implícito. Ordem de grandeza: calado ~0,2 a 0,37 m → ω = √(g/d) ≈ 5 a 7 rad/s, ω·dt = 0,35 por tick:
   estável, mas impreciso; 2 a 4 subpassos por tick. **[estimativa]**
7. Massa e calado calibrados para o barco repousar onde o Et Futurum o põe hoje (linha d'água ~0,366 acima do fundo do
   AABB), mais o peso de cada passageiro (o barco afunda um pouco com dois a bordo: hidrostática de verdade).
8. Horizontal: manter o remo e o momentum do Et Futurum; somar o arrasto relativo à água (vx, vz orbitais) dá a deriva
   das ondas sem nenhuma regra extra.

Testes no núcleo, como os do projeto: equilíbrio no mar calmo, período natural contra a fórmula, resposta a uma onda
longa (o barco acompanha η em fase quando λ ≫ casco) e a uma curta (média sobre o casco), e "nada alocado" no passo.

### 4.2 Ligação no `EntityNewBoat` (o barco do TEOF)

O Et Futurum é compilado com nomes srg para o Minecraft e nomes próprios para o resto; num mixin com
`remap = false` os alvos são exatamente os nomes do bytecode. É classe de mod: usar **mixin tardio** do UniMixins
(`ILateMixinLoader`/`@LateMixin`, aplicado depois que os jars de mod entram no classpath), condicionado à presença do
Et Futurum. **[a verificar: o OceanWaves hoje só tem a configuração inicial `mixins.oceanwaves.json` com
`OceanMixinPlugin`; aplicar um mixin a uma classe de mod pela configuração inicial pode falhar na preparação]**

| Alvo | Tipo | O que fazer |
|---|---|---|
| `EntityNewBoat.getBoatStatus()` (privado, devolve `EntityNewBoat$Status`) | `@Inject(at = RETURN, cancellable)` | no mar com ondas (coluna de mar aberto, região, costa), status pelo casco contra ζ: `IN_WATER` se alguma coluna molha, `IN_AIR` se nenhuma. **Nunca `UNDER_WATER` por causa do bloco** (2.7). Atualiza `waterLevel` para quem ainda o lê (`getWaterLevelAbove`) |
| `EntityNewBoat.updateMotion()` (privado) | `@Inject(HEAD)` guarda; `@Inject(TAIL)` sobrescreve | o Et Futurum continua aplicando o momentum horizontal e a guinada; no fim, `motionY` é trocado pelo do corpo flutuante (que integrou os subpassos) e `rotationPitch` recebe o caturro. Fora do mar com ondas, nada muda |
| `EntityNewBoat.func_70071_h_()` (onUpdate) | `@Inject(at = INVOKE moveEntity, shift = AFTER)` ou TAIL | barcos de outros no cliente (que `tickLerp` puxa para a posição de rede): pôr `posY`/caturro/balanço do corpo local (ver 4.4) |
| `EntityNewBoat.updatePassenger(Entity)` | `@Inject(TAIL)` | o assento gira com o barco: o passageiro sobe e desce com o balanço em vez de flutuar sobre um casco inclinado |
| `NewBoatRenderer.setupRotation(EntityNewBoat, float, float)` | `@Inject(at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V", ordinal = 0, shift = AFTER))` | depois da guinada: `glRotatef(caturro, 1, 0, 0)` e `glRotatef(balanço, 0, 0, 1)`, interpolados com o `partialTicks` (guardar o valor anterior). O pivô é o do `setupTranslation` (y + 0,375) |

Estado do corpo em campos `@Unique` do mixin. Sinais e eixos do desenho: conferir com o caso de teste do
`/exp exibir calibracao` do Expeditions ou equivalente. **[a verificar em jogo]**

### 4.3 Ligação no `EntityBoat` vanilla (subclasses e packs sem o Et Futurum)

Mixin inicial com refmap (nomes srg em produção):

* `EntityBoat.func_70071_h_` (`onUpdate`): `@WrapOperation` na única chamada de
  `World.func_72830_b(AxisAlignedBB, Material)Z` (`isAABBInMaterial`, offset 199, dentro do laço das 5 fatias):
  no mar com ondas devolve falso (`d0 = 0` → `motionY += −0,04`, que é só a gravidade vanilla). Melhor ainda,
  `@Inject` antes da chamada de `moveEntity` (`func_70091_d`, offset 1385) que **substitui** `motionY` pelo do corpo
  flutuante (descartando a gravidade de 16 m/s² e o `*0,95`), e grava caturro/balanço em campos `@Unique` (o
  `rotationPitch = 0` do offset 1518 continua, o desenho lê o campo próprio).
* `RenderBoat.func_76986_a` (`doRender`): `@Inject` depois do `glRotatef` da guinada (ordinal 0) com o caturro e o
  balanço. Atenção: o **futura3d** já tem `RenderBoatMixin` com `@Inject(HEAD, cancellable)` em
  `doRender(EntityBoat, DDDFF)` que troca o barco por um modelo 3D quando há substituição configurada
  (`futura3d-90c7159.jar`, `com/akashic/futura/mixin/mixins/RenderBoatMixin`, refmap
  `func_76986_a`); nesse caso a inclinação precisa entrar também no `EntityReplacements.renderVehicle` do futura3d
  (projeto Akashic, `Akashiic/YesSteveModel`). **[verificado: o mixin; a convivência: proposta]**
* Não recomendado: injetar no `RenderManager.func_147939_a` para todos os renderizadores. O Angelica já faz
  `@Redirect` da chamada `Render.doRender` ali (`MixinRenderManager_BatchEligibility`, `MixinRenderManager` dos
  shaders, `angelica-2.2.43.jar`); outro redirect ou um inject nesse mesmo `INVOKE` conflita. **[verificado: os
  redirects do Angelica]**
* O `ic2.core.item.EntityIC2Boat` sobrescreve `func_70071_h_` inteiro e tem o próprio `isOnWater` (chama
  `func_72830_b` direto): um mixin no `EntityBoat.onUpdate` não o alcança. **[verificado]**

### 4.4 Cliente e servidor: quem integra o quê

Princípio: **x, z e guinada vêm de quem manda na rede; arfagem, balanço e caturro são integrados em cada lado com a
mesma física, o mesmo campo e a mesma hora.** Não é animação: são as mesmas equações, e como o sistema é amortecido e
forçado pelo mesmo ζ, duas integrações com x, z quase iguais convergem para a mesma resposta depois de poucos períodos.

| Caso | Hoje | Proposta |
|---|---|---|
| Barco do Et Futurum pilotado (cliente do piloto) | o cliente simula e manda x, y, z, yaw, pitch na `BoatMoveMessage` | o cliente integra o corpo; y e caturro seguem na mensagem como já vão; balanço só local |
| O mesmo barco visto por outros clientes | interpolação de 5 passos (`tickLerp`) da posição recebida | x, z, yaw da rede; y, caturro e balanço do corpo local (evita o atraso de ≥ 250 ms + latência, ~11° de fase numa onda de 8 s) |
| Barco do Et Futurum vazio | o servidor simula; os clientes interpolam | o servidor integra o corpo (é ele que manda nas colisões); os clientes integram o vertical localmente como acima |
| `EntityBoat` vanilla | as duas pontas simulam; o piloto ignora o servidor até 1 bloco | o mesmo corpo nas duas pontas |

Hora: no servidor, `getTotalWorldTime()/20` mais a fração do subpasso. No cliente, a regra do projeto é a mesma; o
desenho do mar usa `ClientSea.time(partial)` (o `OceanClock`). O barco desenhado interpola entre o tick anterior e o
atual, então fica até 1 tick atrás do mar desenhado; calcular o corpo para o fim do tick seguinte (como o Expeditions
adianta a pose do navio, `docs/ARQUITETURA.md:112-116`) põe os dois juntos. A diferença entre o `OceanClock` e o
`worldTime` local do cliente tem de ser medida no harness. **[a verificar]**

### 4.5 Riscos e pontos que outras frentes tocam

* **Passageiro "debaixo d'água" no cavado.** O olho do jogador fica ~1,6 bloco acima do barco; num cavado de mais de
  ~1,5 m ele pode ficar abaixo do topo do bloco de água e o jogo (e o Neo States, `isInsideOfMaterial` pela superfície
  da célula, `MixinEntity_ModernWater.java:72`) o considera submerso: bolhas, neblina, afogamento, mesmo acima da
  superfície desenhada. É a frente de entidades. **[hipótese]**
* **O `FluidStage` do Neo States não representa crista acima do bloco**: devolve altura 0 a 1 dentro da célula de
  água (`Neo-States/src/main/java/com/akashic/neostates/fluid/FluidStage.java`). Para entidades em geral é preciso
  outra via (a superfície do OceanWaves consultada direto). **[verificado: a interface]**
* **Queda.** O `func_70064_a` do Et Futurum quebra o barco com `fallDistance > 3` em terra; na crista o barco está
  em blocos de ar e acumula queda até tocar água de novo. Só preocupa onde a onda chega à praia, e lá a costa atenua
  ζ. **[hipótese]**
* **FoamFix no servidor** (`FoamFix-1.7.10-universal-1.0.4.jar`, `clientBoatDesyncFix=true` em `config/foamfix.cfg`)
  altera por ASM `EntityBoat.setIsBoatEmpty` e `setPositionAndRotation2` (patchers `BoatDesyncFixPatcher_Main` e
  `_Extra`). São métodos do lado cliente e o FoamFix não está no cliente do TEOF: sem efeito prático, mas um mixin
  nosso nesses dois métodos teria de ser `@Inject`, nunca `@Overwrite`. **[verificado]**
* **Desempenho.** Física de barco é desprezível perto do desenho do mar (~8 a 10 ms por quadro no harness,
  `docs/ARQUITETURA.md`): 12 colunas × 2 a 4 µs por barco por tick. O desenho da inclinação é uma rotação de matriz.
  **[estimativa]**

---

## 5. Outros mods do pack que tocam no barco

Varredura de todos os jars de `mods/` e `mods/1.7.10/` do cliente e de `mods/` do servidor, procurando
`net/minecraft/entity/item/EntityBoat`, `net.minecraft.entity.item.EntityBoat` e `EntityBoat` em classes e JSON, mais
os refmaps por `func_70071_h_`, `func_70091_d`, `func_70072_I`, `func_147939_a` (scripts
`scanjars.py`, `scanjars2.py`, `refmaps.py`; saídas `scan_client*.txt`, `scan2.txt`, `refmaps.txt` em
`/home/user/work/tmp/boat/`). **[verificado]**

| Mod | O que faz com o barco | Impacto |
|---|---|---|
| Et Futurum Requiem | substitui `EntityBoat` por `EntityNewBoat` (`ServerEventHandler.entityAdded`); mixins `boatcamera/client/MixinEntity` (limita a guinada do passageiro) e `boatstat/MixinEntityPlayer` | é o alvo principal (4.2) |
| futura3d (`futura3d-90c7159` no cliente, `0.1.0-dev-1c242cb` no servidor) | `RenderBoatMixin`: `@Inject(HEAD, cancellable)` em `RenderBoat.func_76986_a`, troca por modelo 3D se configurado; `PlayerInputsAdapter` lê o barco | só o vanilla; a inclinação tem de ir também no `renderVehicle` dele |
| FoamFix (só servidor) | ASM em `EntityBoat.setIsBoatEmpty`/`setPositionAndRotation2` | nenhum na física; não usar `@Overwrite` ali |
| Angelica | `@Redirect` de `Render.doRender` e `doRenderShadowAndFire` em `RenderManager.func_147939_a` | não injetar no `RenderManager` |
| IndustrialCraft 2 | `EntityIC2Boat extends EntityBoat`, `onUpdate` próprio | não é trocado pelo Et Futurum nem alcançado pelo mixin vanilla |
| Thaumic Horizons | `EntityBoatGreatwood`, `EntityBoatThaumium` estendem o vanilla | herdam o mixin vanilla se chamarem o `onUpdate` do pai (não conferido) |
| Neo States | embrulha `isAABBInMaterial`, `handleMaterialAcceleration`, `handleWaterMovement`, `isInsideOfMaterial` (água hospedada, água moderna) | não toca no barco do Et Futurum (que lê blocos direto); o vanilla vê a água hospedada |
| Outros (AM2, DragonAPI, DynamicSurroundings, OpenBlocks, Reliquary, Adventure Backpack, Witchery) | `instanceof EntityBoat`, listas, barco inflável próprio | nenhum mexe na física do barco |

Nenhum transformador ou mixin de outro mod mexe no `EntityBoat.onUpdate` vanilla nem no `EntityNewBoat`.

---

## 6. Perguntas em aberto

1. O mixin tardio do UniMixins está disponível no ambiente de build do OceanWaves (GTNH convention) e funciona com o
   `OceanMixinPlugin` atual, ou o alvo no Et Futurum deve ir por outra configuração?
2. A física do barco deve usar o espectro completo ou o mesmo peso por λ que a malha usa perto da câmera (que depende
   do `minSpacing` do cliente)? O servidor não conhece esse valor.
3. O balanço dos barcos de outros jogadores fica só local (cada cliente integra) ou vale um campo de rede próprio?
4. O tempo da física no cliente: `worldTime/20` do tick ou o `OceanClock` amostrado no tick? Medir a diferença no
   harness antes de decidir.
5. Quanto o barco do Et Futurum pesa e qual o calado de verdade (o modelo do 1.12 é maior que o AABB 1,375 × 0,5625)?
   A calibração precisa reproduzir a linha d'água atual no mar calmo.
6. O registro de rede do `EntityNewBoat` (alcance e frequência de atualização do rastreador) não foi lido
   (`ModEntityList.registerEntity` é chamado de outro lugar): conferir antes de decidir quanto da pose viaja pela rede.
