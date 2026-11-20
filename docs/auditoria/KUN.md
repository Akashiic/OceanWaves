# KUN: o que o Kunftig oferece e limita para um navio que balança nas ondas

Frente KUN da auditoria. Escopo: `/home/user/Kunftig` (HEAD `2bb00b3`), mais os jars do cliente TEOF
(`kunftig-2bb00b3.jar`) e do servidor (`kunftig-0.1.0-dev-8349814.jar`). Nada nos repositórios foi alterado; o que
foi executado está em `/home/user/work/tmp/KUN/` (jars extraídos, driver de simulação compilado a partir das fontes do
repositório, só leitura).

Legenda de evidência usada abaixo:

* **[V]** verificado lendo o código (`arquivo:linha`).
* **[J]** verificado no bytecode do jar (`javap`).
* **[X]** verificado executando: o driver `Sim`/`Cost`/`Bench` compila `DisplayMotion`, `DeckPose`, `DeckWalker`,
  `DeckShape`, `DisplayWire` e `DisplayTracker` do repositório (classes sem Minecraft) e roda a lógica do cliente e do
  servidor. Java 21; o cliente real roda a mesma lógica em Java 8. Não é o jogo: é a lógica pura.
* **[H]** hipótese ou estimativa, não medida.

---

## 1. Resumo

1. **O Kunftig já consegue mostrar um navio que arfa, caturra e balança, e já leva o jogador junto sem tremer nem
   cair** [X]. Com roll de 35°, pitch de 14°, heave de ±1,9 m e períodos de 1,4 s, o jogador parado, andando ou pulando
   no convés fica a bordo e no piso, com deriva de 1e-12 m no referencial do convés e diferença jogador contra casco
   de no máximo 2,8 cm no quadro (1,2 mm no caso nominal). A colisão gira junto, porque o `DeckWalker` trabalha no
   referencial do convés [V].
2. **O que o Kunftig não tem é relógio nem extrapolação.** A mensagem `MOVE` leva posição, yaw, pitch, roll e "deslize
   de N ticks", e nada mais: sem horário do mundo, sem velocidade, sem velocidade angular, sem número de sequência
   [V `DisplayWire.java:226-242`]. O cliente anda linearmente até o alvo em N ticks e **para** ali; se a próxima
   mensagem atrasa, o navio para e depois corre [V `DisplayMotion.java:77-97`] [X].
3. **Resultado: o navio desenhado atrasa `glide + 1` ticks em relação ao relógio do cliente** [X]: 3 ticks (150 ms) no
   ritmo atual do Expeditions (pose a cada 2 ticks, deslize 2), 2 ticks com pose a cada tick. O mar do OceanWaves é
   desenhado em `(totalWorldTime + parcial)/20` [V `ClientSea.java:89-94`], sem esse atraso. Com 1,2 m de heave e
   período de 4,8 s, o casco fica até **23 cm** fora do ponto em que o mar (mesmo tempo) está. Isso se corrige no
   plugin, sem mexer no Kunftig, prevendo `glide + 1` ticks à frente: o erro cai a 3 mm [X].
4. **O plugin hoje não usa o tempo do mundo**: o mar da física avança com um contador próprio (`ExpeditionsCommand.java:89,95`),
   contra a regra do OceanWaves ("o tempo do mar é o tempo total do mundo / 20, nos dois lados") [V]. Tem de mudar
   antes de ligar o OceanWaves.
5. **O custo de mandar rotação a 10 ou 20 Hz é baixo**: 43 B de carga útil por mensagem (63 B no fio), 5 a 10 kbit/s por
   espectador por navio, e o `DisplayTracker` gasta 0,3 µs por mensagem montada (0,52 ms por tick para 20 navios
   contra 100 jogadores, mensagem a cada tick) [X]. O envio pelo Netty não foi medido.
6. **Cliente e servidor não divergem no fio de exibições**: `DisplayWire`, `DisplayTracker`, `DisplayMotion`,
   `BukkitDisplayBridge`, `DeckShape` e `DeckPose` têm bytecode idêntico nos dois jars [J]. Divergem na API (cliente 16,
   servidor 13) e no código só de cliente (`DeckWalker`, `DeckClient`, `DisplayRenderer`). O servidor não tem as APIs
   14 a 16 (posto de comando, posturas, luneta) [J].
7. **Alternativa "o cliente calcula o movimento"**: possível e melhor, mas pede mudanças reais (seção 8): um acoplamento
   Kunftig-OceanWaves sem dependência direta (interface de superfície do mar), horário do mundo no fio, e o integrador
   do casco compartilhado entre servidor e cliente. Recomendo três etapas: (0) previsão e relógio do mundo no plugin,
   agora; (1) uma operação de pose com horário e velocidades (API 17); (2) modo "flutua no mar" com o estado do casco
   mandado devagar e integrado no cliente.
8. Dois riscos fora do código do convés, ambos **[H]**: o estado de água do vanilla continua pelos blocos (o convés
   num cavado fica "dentro d'água" para o jogador) e os jogadores remotos no convés podem se desencaixar em mar forte.

---

## 2. Como `moveTo`, `setRotation` e `setMotion` chegam ao cliente

### 2.1 Servidor

| Passo | Evidência |
|---|---|
| `Display#moveTo(loc, glide)` copia x, y, z e **yaw** da `Location` e **mantém o pitch e o roll que o estado já tem**: o pitch da `Location` é ignorado | `BukkitDisplayBridge.java:283-298` [V] |
| `Display#setRotation(yaw, pitch, roll)` chama `State.moveTo(..., glide = 1)` na posição atual | `BukkitDisplayBridge.java:341-351` [V] |
| `State.moveTo` grava posição e ângulos, marca `moved` e fixa `glide` (0 se mudou de mundo) | `DisplayTracker.java:170-196` [V] |
| A cada tick de servidor, no **fim** (`ServerTickEvent` fase `END`), o `update` manda **uma** `MOVE` por espectador se `moved`, e zera `moved` e `glide` | `BukkitDisplayBridge.java:109-137`; `DisplayTracker.java:371-373, 400-409` [V] |
| Várias chamadas no mesmo tick se fundem: vale a última (valores e `glide`) | `DisplayTracker.java:179-195` [V] |
| Taxa máxima: 1 `MOVE` por exibição por espectador por tick (20 Hz). O Expeditions manda a cada 2 ticks (10 Hz): `runTaskTimer(this, command::tick, 2, 2)` | `ExpeditionsPlugin.java:18,40`; `ExpeditionsCommand.java:87-114` [V] |
| `setMotion(v)`: uma mensagem `MOTION` (posição e velocidade linear, até 10 blocos/tick por eixo); o servidor anda junto (`d.x += d.vx`); a rotação **não** anda | `DisplayTracker.java:276-287, 443-446`; `DisplayWire.java:244-265` [V] |

Armadilha da ordem **[V]**: `setRotation` depois de `moveTo` rebaixa o deslize para 1. O Expeditions chama `setRotation`
e depois `moveTo` (`ShipDisplays.java:79-84`), a ordem certa. Uma segunda armadilha menor: se a posição e o yaw não
mudaram mas o pitch ou o roll mudaram dentro de `setRotation`, o `moveTo` seguinte não entra no `if` de `State.moveTo`
(`DisplayTracker.java:179-186`) e o deslize fica 1. Com um navio que arfa isso quase nunca acontece. Uma chamada
atômica `setPose(loc, yaw, pitch, roll, glide)` tiraria as duas.

### 2.2 Fio

`MOVE` (op 3) = byte da operação, `int id`, 3 `double`, `float yaw`, `ushort ticks`, `float pitch`, `float roll`
(`DisplayWire.java:226-242`, leitura em `623-637`) [V]. Medido: **43 bytes** de carga útil [X]. No `S3FPacketCustomPayload`
do 1.7.10 (id 1 B, nome do canal `kunftig:display` 1+15 B, comprimento `short` 2 B) e o prefixo de tamanho do quadro: cerca
de **63 B** por mensagem **[H]**: o formato do pacote é o do protocolo 1.7.10, não conferido no jar do servidor
(o `server.jar` do Crucible é um lançador sem as classes do Minecraft).

Campos que **não existem**: horário do mundo, número de sequência, velocidade, velocidade angular, aceleração.
`DisplayWire.read` devolve `null` para operações acima de `POSE` (10) (`DisplayWire.java:470-472`) [V]: um cliente antigo
ignora operações novas, então acrescentar operações é compatível para trás.

### 2.3 Cliente

| Passo | Evidência |
|---|---|
| Os pacotes de jogo são processados no `runTick`, na seção `gameMode`: `PlayerControllerMP.updateController` (classe ofuscada `bje`, método `e()`) chama `processReceivedPackets` | `bao.p()` chama `bje.e()` (bytecode de `bin/minecraft.jar`, seção "gameMode"); `bje.e()` chama `ej.a()` [J] |
| O mixin entrega a carga do canal `kunftig:display` a `DisplayClient.onPayload` na hora | `NetHandlerPlayClientDisplayMixin.java:33-39` [V] |
| `MOVE` vira `motion.glide(x, y, z, yaw, pitch, roll, ticks)` | `DisplayClient.java:244-249` [V] |
| A cada tick do cliente, no `START` (antes do mundo e do jogador), `motion.tick()` avança uma etapa | `DisplayClient.java:376-387` [V]. Que o `START` do FML vem antes do `updateController` é o padrão do FML **[H]** (o `minecraft.jar` é o vanilla sem os patches do Forge) |
| `glide(n)`: o alvo é o novo ponto, começando **de onde a exibição está agora** (não do alvo anterior); `x += (to - x) / glide` a cada tick, o que dá uma rampa **linear** que chega em `n` ticks. Chegou, **para** (sem velocidade) | `DisplayMotion.java:48-63, 77-97` [V] |
| Ângulos: yaw, pitch e roll são interpolados **separadamente**, cada um "pelo lado mais curto" (`wrap`) | `DisplayMotion.java:59-61, 128-136` [V] |
| O desenho usa `lastX + (x - lastX) * parcial` (interpolação de entidade do vanilla: o quadro está sempre um tick atrás do estado) e `glTranslated`, `glRotatef(-yaw, Y)`, `glRotatef(pitch, X)`, `glRotatef(roll, Z)` | `DisplayMotion.java:103-125`; `DisplayRenderer.java:542-546` [V] |
| A pose fica **fora** da malha compilada (a malha é do modelo, a matriz é do quadro): mexer o navio a cada tick não refaz buffer | `DisplayRenderer.java:542-546`; `docs/API.md:468-476` [V] |

**Não há extrapolação, buffer de jitter nem correção de relógio.** A rampa de cada `MOVE` começa quando o pacote é
processado, que é um tick inteiro do cliente: a chegada é quantizada em 50 ms.

### 2.4 Atraso: medido

Linha do tempo de um `MOVE` com deslize `g`, enviado para a pose do tempo τ e processado no tick `i = τ` do cliente
(sem rede, para isolar o pipeline):

* o estado só chega ao alvo em `g` ticks (`P(i+g) = alvo`);
* o desenho mostra `lerp(P(n-1), P(n))`, um tick atrás do estado.

Logo o desenho do tempo `r` mostra a pose de `r - (g + 1)`. O driver reproduz o `DisplayMotion` com mensagens nos ritmos
reais e procura o atraso que minimiza o erro [X]:

| Ritmo | Deslize | Atraso medido | Altura fora do corpo no mesmo tempo (heave 1,2 m, período ~4,8 s) |
|---|---|---|---|
| a cada 2 ticks (o Expeditions hoje) | 2 | **3,00 ticks (150 ms)** | **0,234 m** |
| a cada tick | 1 | **2,00 ticks (100 ms)** | 0,156 m |
| a cada 2 ticks, plugin prevendo `g+1 = 3` ticks à frente | 2 | **0,00 ticks** | **0,003 m** |
| a cada tick, prevendo 2 ticks à frente | 1 | 0,00 ticks | 0,001 m |

Em geral o desencontro máximo de altura entre casco e mar no mesmo ponto é `Δ ≈ A·ω·τ` (A amplitude, ω = 2π/T, τ o
atraso) **[H]** (derivado; o 0,234 m acima é a medida). Com A = 1 m, T = 8 s e 150 ms: ~9 cm. Com A = 0,5 m e T = 3 s: ~16 cm.

**A latência de rede se cancela, em primeira ordem, se o plugin usar o tempo do mundo [H]**: o servidor manda o tempo
(`S03PacketTimeUpdate`: total do mundo, hora do dia, `doDaylightCycle`) a cada 20 ticks (`tickCounter % 20`, seção
"timeSync" de `MinecraftServer`) [J]; o cliente conta sozinho entre uma sincronia e outra (`WorldClient.tick` soma 1 ao
total a cada tick) [J]. O relógio do cliente fica então atrás do servidor por uma latência de ida L, e o `MOVE` também
chega com L de atraso: no instante em que o cliente processa a pose de τ, o relógio dele marca ≈ τ. Sobra só o pipeline
`g + 1`. Isso supõe latência simétrica e estável e não foi medido com dois processos.

**Com o relógio do plugin, não do mundo, o desencontro é arbitrário**: `ExpeditionsCommand.tick` faz `ticks += 2`, com
`ticks` começando em 0 quando o plugin carrega, e passa `t0 = (ticks - 2) / 20` ao `FloatingBody.step`
(`ExpeditionsCommand.java:89,95`) [V]. Hoje não importa (o mar do plugin é o `WaveField` de 4 trens, sem relação com o
desenhado); com o OceanWaves, o navio ficaria numa fase qualquer do mar visível.

---

## 3. Rotação em três eixos: ângulos, não quatérnios

* A pose é **yaw, pitch, roll** em graus, interpolados cada um por reta [V `DisplayMotion.java:59-61, 84-91`]. A matriz é
  `Ry(-yaw) · Rx(pitch) · Rz(roll)`, a mesma no desenho (GL) e no `DeckPose` [V `DeckPose.java:25-50`; `DisplayRenderer.java:542-546`].
  É a convenção intrínseca de navio (guinada, depois caturro em torno do través, depois balanço em torno da proa), a
  mesma que o `FloatingBody` usa (`R = Rx(caturro) · Rz(balanço)` no referencial sem guinada) [V `FloatingBody.java:80-122, 88`].
  Sinais: pitch positivo baixa a proa, roll positivo levanta o lado +x, nos dois [V `Display.java:127-135`; `DeckTest.java:141`;
  `FloatingBody.java:6-9`].
* **Erro de interpolar ângulos de Euler em vez de fazer slerp**, medido no ritmo do Expeditions (pose a cada 2 ticks)
  com roll 22°, pitch 9° e yaw em movimento [X]:

  | Frequência do mar (1 = períodos de 5 a 7 s) | Erro angular máximo |
  |---|---|
  | 1 | 0,0043° |
  | 2 (períodos de 2,9 s) | 0,0171° |
  | 4 (períodos de 1,4 s) | 0,0676° |

  Desprezível: 0,07° a 15 m de alavanca é 1,8 cm, no pior caso extremo. O limite conhecido de Euler é o pitch em ±90°
  (travamento de cardan), longe do que um navio faz. **Não precisa de quatérnio.** O erro dominante da pose é o
  atraso da seção 2.4, não a interpolação.
* A amostragem a 10 Hz é uma poligonal da trajetória verdadeira; o erro de corda numa senoide é `A·ω²·Δt²/8`:
  0,8 mm para heave de 1 m com período de 8 s; 3,7 mm para 0,3 m com período de 2 s **[H]** (cálculo).
  O que se vê é o salto de velocidade a cada vértice da poligonal, pequeno.

---

## 4. O `DeckClient` leva o jogador num convés que balança

### 4.1 Como é feito [V]

* Tudo ao fim do tick do jogador (`PlayerTickEvent` fase `END`, `DeckClient.java:72-82`), depois de o vanilla mover o
  jogador e antes do envio da posição ao servidor.
* `walker.step(deck, pose(motion, 0), pose(motion, 1), pé, ...)` (`DeckClient.java:148-158`): `pose(motion,0)` é o estado do
  tick anterior, `pose(motion,1)` o deste tick. O jogador é posto em `now.toWorld(l)`, o mesmo ponto local, na pose
  nova (`DeckWalker.java:206-216`). É um **carregamento rígido**, tick a tick, independente da velocidade do convés.
* Em pé: o piso é procurado ao longo da **vertical do mundo** (`up()` da pose), por isso nada escorrega num convés
  inclinado (`DeckWalker.java:141-187`). Acima de `MIN_UP = 0,35` de cosseno (~70° de inclinação) ninguém fica em pé
  (`DeckWalker.java:38, 110`).
* No ar (pulo, queda): carregado por até `MAX_AIR_TICKS = 40` ticks e preso às faces das caixas (`DeckWalker.java:31, 188-201, 349-425`).
* O jogador local e o casco são interpolados com o mesmo `parcial` entre os mesmos dois ticks, então "não tremem um contra
  o outro", como diz o `CLAUDE.md`.

### 4.2 Executado [X]

O driver usa as classes reais com o convés de `DeckTest` (piso, amuradas, castelo, caixote) e reproduz o pipeline do cliente
(`DisplayMotion.tick` no começo, `glide` ao receber, `DeckWalker.step` ao fim, gravidade do vanilla, passada de 0,2158
bloco/tick, pulo de 0,42). O corpo segue `heave = 1,2·sin(1,3t)`, `pitch = 9·sin(0,9t)`, `roll = 22·sin(1,1t+1)`, yaw
0,9°/s e avanço de 0,2 m/s (os números do teste `standsStillOnADeckThatHeavesRollsPitchesTurnsAndSails`), mas **entregues
pelo `MOVE` a cada 2 ticks, deslize 2**, como o plugin faz. 1.200 ticks por caso.

| Caso | Deriva do ponto no convés | Diferença jogador × casco no quadro (4 parciais por tick) | Ticks fora do piso (fora os 3 do pouso inicial) |
|---|---|---|---|
| parado, sem jitter | 3e-13 m | 0,3 mm | 0 |
| parado, 1/3 das mensagens atrasadas 1 tick | 5e-13 m | 0,7 mm | 0 |
| andando ao longo do convés, sem jitter | 0 | 0,7 mm | 0 |
| andando, com jitter | 0 | 1,2 mm | 0 |
| pulando a cada 30 ticks | n/a | 2,5 mm | só os ticks no ar |
| **estresse:** roll 35°, pitch 14°, heave ±1,9 m, períodos de 1,4 s, andando, com jitter | 0 | **28 mm** | 0 |
| estresse, parado | 2e-13 m | 12 mm | 0 |

A matriz completa (amplitude 1 e 1,6 contra frequência 1, 2 e 4) está no apêndice; em nenhuma célula o jogador saiu de
bordo nem perdeu o piso. A diferença no quadro vem da corda contra o arco: o jogador é interpolado em linha reta no mundo
e o casco gira em arco, `r(1 - cos(θ/2)) ≈ rθ²/8`.

**Custo**: `DeckWalker.step` com um convés do tamanho da fragata (1.458 caixas) leva **6,3 µs** por tick (2.048 caixas:
9,1 µs) [X] (caixas sintéticas espalhadas; Java 21 aquecido; o cliente em Java 8 tem a mesma ordem).

### 4.3 O que o teste não cobre e merece atenção

* **Tremor que existe: o do navio contra o mundo.** Quando uma `MOVE` chega 1 tick tarde, a origem do casco para e depois
  corre; o salto de velocidade da origem foi de **0,118 m/tick** contra 0,010 no caso sem jitter e 0,078 m/tick de velocidade
  máxima de heave [X]. O passageiro carregado junto não sente (ele vai com o casco), mas vê o mar passar aos trancos,
  e vê o casco e o mar saírem de fase. A causa é a falta de extrapolação (seção 2.3), não o `DeckClient`.
  Tamanho da quantização esperada na vida real **[H]**: com jitter de rede de dezenas de ms contra ticks de 50 ms,
  atrasos de 1 tick são frequentes; não medido com rede real.
* **A água do vanilla ignora o convés [H]** (e o ponto mais importante fora da cinemática). `DeckClient` e `DeckWalker` não
  tratam estado de água: `grep` de `inWater|isInWater|handleWaterMovement|Material.water` em `display/` e
  `client/display/` não acha nada [V]. O estado de água do jogador sai dos **blocos** do mundo, que continuam planos. Hoje
  o convés da chalupa fica a 1 bloco da água e "quem está a bordo não conta como dentro d'água"
  (`Expeditions/docs/TESTES.md:134`). Com ondas, o casco sobe e desce `ζ` em volta do nível de repouso, mas a água de
  blocos não: no cavado, com borda livre de ~0,9 bloco e `ζ = -2 m`, o piso fica ~1,1 bloco **abaixo** da água de blocos, e o
  vanilla põe o jogador "na água" (nada, arrasta, perde o pulo normal) em cima do convés. Correção possível no Kunftig:
  enquanto `walker.aboard()` e o pé acima do piso da exibição, forçar o estado de água para falso (mixin em
  `Entity#handleWaterMovement` ou zerar `inWater` no `PlayerTickEvent` `START`). É a mesma questão que o item 3 do pedido do
  usuário ("as entidades devem perceber que o mar tem dinâmica") faz do outro lado: quem não está a bordo continua com água plana.
* **Jogadores remotos no convés [H]** (`DeckClient.remote`, `DeckClient.java:232-255`). Um jogador remoto chega com alguns ticks de atraso
  (a posição dele vem pelo servidor) e é redesenhado no piso se estiver a até `REMOTE_REACH = 0,4` bloco (0,8 se já estava a
  bordo) na **vertical** (`DeckWalker.surface`); na horizontal ele fica onde o servidor o põe. Aritmética: com heave de
  2 m e período de 6 s, a velocidade vertical do piso chega a 2,1 m/s = 0,10 m/tick; com ~8 ticks de atraso entre a
  posição do remoto e a pose atual, a diferença vertical passa de 0,8 bloco e o remoto deixa de ser encaixado (desenhado no ar ou
  enterrado). O balanço move um ponto a 4 m do eixo a ~0,08 m/tick: ~0,5 m de deslize horizontal com 6 ticks de atraso. Correção
  possível: o cliente guardar um histórico curto de poses por exibição e converter a posição do remoto para o referencial do
  convés na pose do tempo do pacote dele (ou o servidor repassar a coordenada local).
* **Câmera**: o jogador é carregado, mas a câmera não inclina com o casco (nenhuma rotação de câmera em `DeckClient`) [V]. O
  `docs/API.md` já lista "o balanço da câmera ao andar" como "ainda não". Não é obrigatório para o "literal".
* **Servidor**: o jogador flutua no ar sobre a exibição para o servidor e precisa de `allow-flight=true` (o TEOF tem) [V `docs/API.md:902-905`].
  Com mar maior o passageiro anda mais rápido em coordenadas do mundo (0,10 m/tick de heave): bem dentro dos limites do vanilla
  ("moved too quickly" só passa de ~10 blocos/tick) **[H]**, mas não foi testado em servidor.
* O que o doc diz "visto no jogo" (TEOF 5.4, `docs/API.md:921-934`) foi com o `DeckWalker` antigo; o do `2bb00b3` é
  mais novo (caixas sólidas de todos os lados, `DROP` 0,75, passo só com altura livre). Os testes do `DeckTest` cobrem os dois
  casos de inclinação (`standsStill...`, `wallsHoldOnARollingDeck`), mas o que o cliente TEOF faz com ele está **a verificar no jogo**.

---

## 5. A colisão gira com a rotação?

**Sim, por construção [V].** As caixas ficam no referencial da exibição e viajam arredondadas a 1/32 de bloco
(`DeckShape.java:81, 145-169`). `DeckPose.toLocal/toWorld` aplicam a matriz completa de yaw, pitch e roll
(`DeckPose.java:52-63`), e o `DeckWalker` faz toda a conta no referencial do convés: caixas alinhadas aos eixos do
**navio**, jogador como ponto com raio e altura (`DeckWalker.java:1-18`). Só o jogador mantém altura vertical do mundo (o
Minecraft não inclina a caixa dele), tratada pela procura de piso ao longo de `up()`. O pivô é a origem da exibição:
quem quer o casco girando em torno do centro de gravidade põe a origem em `CG - R·CG_local`, como o plugin já faz
(`ExpeditionsCommand.origin`, `FloatingBody.originOffset`). Limites: no máximo 2.048 caixas por exibição (a fragata tem
1.458); o `DECK` cabe no `short` de comprimento do pacote do 1.7.10 (2.048 × 12 B = 24,6 kB < 32.767) **[H]** (limite do protocolo).

---

## 6. O custo de mandar rotação a 20 Hz para vários jogadores

Medido em [X] com o `DisplayTracker` e o `DisplayWire` reais, pose nova em todo tick para todos os navios
(cenário: todos os jogadores dentro do alcance de 128 blocos), Java 21, aquecido:

| Navios × jogadores | µs por tick no `DisplayTracker.update` | Mensagens por tick | Carga útil por tick (sem moldura) |
|---|---|---|---|
| 1 × 10 | 8,9 | 10 | 430 B |
| 1 × 100 | 32,9 | 100 | 4,3 kB |
| 5 × 50 | 86,5 | 250 | 10,8 kB |
| 5 × 100 | 192 | 500 | 21,5 kB |
| 20 × 50 | 290 | 825 | 35,5 kB |
| 20 × 100 | 524 | 1.669 | 71,8 kB |

(Nas linhas de 20 navios os navios deslizam para fora do alcance durante o teste, por isso menos de 20 × N mensagens.)

* CPU do tracker: ~0,3 µs por mensagem montada; 0,52 ms por tick (1% do tick) no pior caso acima. Há uma alocação por
  espectador por tick (`DisplayWire.move` monta um `ByteArrayOutputStream` e um `DataOutputStream` por mensagem,
  `DisplayTracker.java:372`) [V]; irrelevante nesse volume. **Não medido:** `new S3FPacketCustomPayload`, `sendPacket` e o
  trabalho do Netty (criptografia, escrita), que dominam em volume.
* Banda **[H]** (63 B por mensagem no fio, sem cabeçalhos TCP/IP): 10 Hz = 0,63 kB/s = 5 kbit/s por espectador por navio; 20 Hz =
  1,26 kB/s = 10 kbit/s. 50 espectadores × 3 navios a 20 Hz: ~190 kB/s (1,5 Mbit/s) de saída. Os cabeçalhos TCP/IP podem
  somar ~40 B por segmento se o Nagle não juntar.
* O Kunftig **não limita** a taxa nem agrupa várias exibições; o limite é 1 `MOVE` por exibição por espectador por tick.
  Os espectadores são todos os que listam o canal `kunftig:display`, estão no mundo e a até `alcance + 4` blocos da
  **origem** da exibição (`DisplayTracker.java:453-461`); o alcance máximo é 128 (`KunftigDisplays`).
* Do lado do cliente, o custo de receber é uma `glide()` (O(1)); o do `DeckWalker`, ~6 µs/tick; o do desenho não depende
  da pose (a malha é compilada).

**Conclusão: a taxa não é o gargalo**; o problema da pose enviada é a qualidade (atraso, jitter), não o volume.

---

## 7. Alternativas dentro da API atual (etapa 0): o que dá para consertar só no plugin

Sem mexer no Kunftig:

1. **Prever `g + 1` ticks à frente** ao calcular a pose (a física avança até `τ + g + 1`), com `g` o deslize usado: zera o
   atraso medido [X] (seção 2.4).
2. **Usar o tempo do mundo**, `world.getFullTime() / 20` por mundo (ou `OceanWaves.time(dim)`), no lugar do contador
   `ticks` (`ExpeditionsCommand.java:89,95`). `World#getFullTime` do Bukkit corresponde ao total do mundo **[H]** (não conferido
   no jar do Crucible).
3. **Subir a cadência** para 1 tick (20 Hz, deslize 1) reduz o atraso a 2 ticks e o salto de velocidade pela metade
   ([X], 0,0051 contra 0,0101 m/tick), ao custo de dobrar a banda; com a previsão do item 1 o atraso some nos dois ritmos.
4. **Não depender da ordem** `setRotation`/`moveTo` (seção 2.1).

O que isso **não** resolve: jitter de rede (o navio ainda para e corre quando uma mensagem atrasa), o relógio do cliente
oscilando contra o do servidor (seção 8.4) e o custo do servidor de simular o casco com o mar real (seção 8.5).

---

## 8. A alternativa: o cliente calcula o movimento do navio a partir do mar determinístico

### 8.1 Por que vale

A superfície do OceanWaves é uma função pura `ζ(x, z, t)` do estado do mar e do tempo do mundo; o servidor e o cliente a têm
**bit a bit** (mesmas componentes, `StrictMath`, `SplitMix64`) [V `api/OceanWaves.java:20-24`]. Se o cliente integra o
casco com essa mesma função no seu relógio, o navio e o mar visíveis ficam em fase **por construção**, sem atraso
de pipeline, sem jitter e sem compensar latência; a rede só leva o que o cliente não sabe (leme, vela, carga, e uma
correção lenta).

### 8.2 O que falta hoje (evidência)

| Falta | Evidência |
|---|---|
| O Kunftig não conhece o OceanWaves, nem o mar | nenhuma referência a `oceanwaves` em fontes, gradle ou docs do repositório (`grep`) [V] |
| O Kunftig não tem o horário do mundo: o relógio das exibições (`DisplayClient.clock`) conta ticks de cliente desde que o jogo abriu; as animações e o `glide` correm nele | `DisplayClient.java:134, 171-174, 376-380` [V] |
| A mensagem de pose não leva horário nem velocidades | `DisplayWire.java:226-242` [V] |
| O `DisplayMotion` só tem pulo, rampa linear e voo linear com rotação fixa | `DisplayMotion.java:34-97` [V] |
| A física do casco está no `core/` do Expeditions (`FloatingBody`, `HullColumns`, `WaveField`), que o Kunftig não pode depender; usa `Math.sin/cos`, não `StrictMath` | `FloatingBody.java`; 7 usos de `Math.*` e 0 de `StrictMath` em `physics/` [V] |
| Os dados do casco (504 colunas por navio, massa, inércia, GM) vão do gerador ao `navios.yml` do plugin, não ao pack nem ao cliente | `navios.yml` (4 navios, 504 colunas cada); `docs/FRAGATA.md` [V] |

### 8.3 O que precisaria mudar (proposta; nada disto foi implementado)

**(a) Superfície do mar por interface, sem dependência direta.** O Kunftig define uma interface (por exemplo
`SeaSurface { double height(int dimension, double x, double z, double timeSeconds); double restLevel(int dimension); }`) e um ponto de
registro no cliente (`DisplayClient.INSTANCE.seaSurface(...)` ou mensagem do FML IMC). O OceanWaves registra a dele (o
`OceanField` e a atenuação de costa que ele já tem no cliente) quando o Kunftig está presente. É o mesmo padrão que o
Kunftig já usa com o GeckoLib, por reflexão e só quando carregado (`GeckoDisplays.java`). Sem provedor registrado, o
cliente não anuncia o modo e cai no `MOVE`.

**(b) Tempo.** `DisplayClient` passa a oferecer `worldTime(partial) = (mc.theWorld.getTotalWorldTime() + partial) / 20`, a
mesma conta de `ClientSea.time` (`ClientSea.java:89-94`). O `clock` de animação fica como está.

**(c) Fio** (aditivo: um cliente novo registra um canal novo, por exemplo `kunftig:display4`, só de registro, como o
`display2` e o `display3` fazem; quem não registra continua recebendo `MOVE`):

* `BODY` (op 11), esboço **[H]**: `int id, long worldTick, double x, z, float yaw, float yawRate, float vx, vz` (movimento horizontal
  decidido pelo leme e pela vela, **autoritativo do servidor**), `float y, vy, roll, rollRate, pitch, pitchRate` (estado
  verticais do casco no instante `worldTick`), `ushort loadingId` (índice de uma tabela de carregamentos do manifesto).
  ~70 a 80 B a 2 a 4 Hz: ~300 B/s por espectador, contra ~630 B/s do `MOVE` a 10 Hz, e sem o jitter.
* `FLOAT` no manifesto `kunftig/displays.json` (como `escala`, `ocultos`): colunas do casco, massa, inércias, amortecimentos e
  tabela de carregamentos, para o plugin e o cliente lerem o **mesmo** arquivo.
* `BODY` entra no `SHOW` para quem chega depois.

**(d) Cliente.** Um `DisplayMotion` novo "flutuante": a cada tick, integra o casco (mesmos subpassos de 1/80 s do plugin) com
`SeaSurface.height(..., t)` e produz `P(n)` para a interpolação de desenho e para o `DeckClient`, que **não muda** (continua lendo
`motion.x/y/z/yaw/pitch/roll(parcial)`). Ao chegar um `BODY` do tempo `τ`, o cliente **refaz** a integração de `τ` até o tick atual (os
estados passados do mar são computáveis, é função do tempo) e dilui a diferença contra a trajetória anterior em 4 a 6 ticks
(sem salto). Isso é a "predição com o modelo exato"; entre dois `BODY` o erro cresce só por diferenças de entrada.

**(e) Física compartilhada.** O integrador e o `HullColumns` saem do `core/` do Expeditions para um módulo Java 8 sem Minecraft que
Kunftig (cliente), Expeditions (plugin) e OceanWaves (a superfície) usem (por exemplo no `core/` do OceanWaves, que já é
"matemática pura, Java 8, com testes"). Trocar `Math.*` por `StrictMath` (regra de determinismo do OceanWaves; `Math.sin`
pode diferir no último bit entre o Java 8 do cliente e o Java 21 do servidor **[H]**).

### 8.4 Uma terceira via, mais barata e mais literal: a resposta linear (RAO)

Como o mar do OceanWaves **é** uma soma de componentes `(k, ω, a, fase)`, a resposta de arfagem, balanço e caturro de um casco em
regime linear é a soma das respostas a cada componente: `ζ_nav(t) = Σ |RAO_c(ω)| · a_c · cos(k·x - ωt + φ_c + θ_c(ω))`, com `RAO`
tirado do mesmo modelo do `FloatingBody` (massa adicionada, amortecimento, rigidez de restauração do GM e da área de flutuação e a
integral de Froude-Krylov sobre as colunas). **[H]** (proposta, não implementada nem validada contra o integrador).

* **Sem estado**: nada para integrar nem para dessincronizar; servidor e cliente calculam o mesmo número para o mesmo tempo; o
  navio pode ser avaliado em qualquer instante (predição, interpolação, quem chega depois, sem snapshot).
* **Custo**: O(componentes) por navio por tick (64 componentes × 3 graus, ~1 µs a 2 µs), contra 4.032 amostras do mar por pose
  (504 colunas × 8 subpassos) do integrador atual no servidor.
* **Perde** o que não é linear (o amortecimento quadrático do balanço do `FloatingBody`, o convés emergindo ou a quilha batendo,
  inclinações grandes, o adernamento do vento). Um híbrido resolve: RAO para a banda de ondas mais um estado lento (adernamento
  médio, calado, rumo) mandado pelo servidor.
* É a opção que mais combina com "no sentido mais literal e matemático possível" e a que menos pede do fio.

### 8.5 Custo no cliente (estimativa **[H]**)

O `docs/ARQUITETURA.md:129-134` do OceanWaves mede uma consulta pontual com inversa, 64 componentes: ~4 µs a ~2,5 µs (Java 8 aquecido). Integrar
504 colunas × 4 subpassos por tick é ~2.000 amostras por navio por tick: **~5 a 8 ms por tick por navio no cliente**, na thread
principal (e o mesmo ordem no servidor: 4.032 amostras por pose, ~10 ms por pose a cada 2 ticks por navio com o mar real).
Inviável como está; as saídas são menos colunas no cliente (64 colunas: ~0,5 ms), substeps de 1/40 s, uma `HeightPatch` por
quadro (o `OceanRenderer` já monta tabelas), ou a RAO da seção 8.4. É um assunto da frente de desempenho; está aqui porque
decide a viabilidade do "cliente calcula".

### 8.6 Riscos do "cliente calcula"

* **Consistência entre clientes**: cada cliente usa o relógio dele (diferença típica de ±1 tick entre eles). Cada tela é
  autoconsistente (navio e mar na mesma fase), mas dois clientes veem fases ligeiramente diferentes; os jogadores remotos são
  redesenhados pelo `remote()` contra a pose **local**, então continuam no convés de quem os vê.
* **Autoridade**: o servidor continua com a simulação autoritativa (alcance, canhões, colisão com outro navio, validação
  de quem está a bordo) e a diferença para o que cada cliente desenha é de L + 1 a 3 ticks; validações no servidor precisam de tolerância.
* **Relógio do cliente**: o total do mundo no cliente é sincronizado a cada 20 ticks e anda sozinho no meio [J]. Se o servidor
  roda abaixo de 20 TPS, o relógio do cliente anda mais rápido que o do servidor e **salta para trás** a cada sincronia
  (por exemplo, 2 ticks por segundo a 18 TPS) **[H]**; o mar visível e o navio calculado no cliente dão o salto juntos (em fase), mas o `MOVE`
  do servidor (etapa 0) não, e a água não acompanha. Quem decide o relógio do mar do cliente é a frente WAVE; aqui só se
  registra que o salto de fase do mar também acontece com o navio no servidor.
* **Versões**: o modo exige API nova nos dois lados (o servidor está na API 13, ver seção 9).

### 8.7 Recomendação por etapas

| Etapa | O que | Onde | Esforço |
|---|---|---|---|
| 0 | Prever `g+1` ticks; tempo do mundo no lugar do contador; ordem `setRotation`/`moveTo` | Expeditions (plugin) | pequeno, sem Kunftig novo |
| 1 | API 17: `Display#setPose(loc, yaw, pitch, roll, glide)` atômica **e** uma pose com horário do mundo e velocidades (op 11 `MOVE2`: `worldTick`, posição, ângulos, velocidade linear e angular por tick); o cliente extrapola e dilui a correção. Taxa de 5 a 10 Hz passa a bastar | Kunftig (fio, `DisplayMotion`) | médio |
| 2 | Modo "flutua no mar": `SeaSurface` (interface), `BODY` e `FLOAT`, integrador compartilhado, ou a RAO (8.4) | Kunftig + OceanWaves + Expeditions core | grande |
| em paralelo | Estado de água do convés (seção 4.3); jogadores remotos com histórico de pose | Kunftig (cliente) | pequeno a médio |

---

## 9. Comparação: `kunftig-2bb00b3` (cliente) contra `kunftig-0.1.0-dev-8349814` (servidor)

Os dois jars foram extraídos para `/home/user/work/tmp/KUN/{client,server}`; o `git` do repositório tem os dois commits
(`8349814` é "Merge the deck branch (API 12 and 13) into main's line", 19 commits antes do `2bb00b3`).

* `KunftigApi.API_VERSION`: **cliente 16, servidor 13** [J] (`javap -constants`).
* **Fio de exibições: igual.** Bytecode (`javap -c -p`) **idêntico** nos dois jars em `DisplayWire`, `DisplayWire$Message`,
  `DisplayTracker`, `DisplayTracker$State`, `DisplayMotion`, `DeckShape`, `DeckPose`, `BukkitDisplayBridge`,
  `BukkitDisplayBridge$BukkitDisplay`, `DisplayClient$BoneTurn`, `NetHandlerPlayClientDisplayMixin` e `DisplayModelIndex` [J].
  Operações 0 a 10 e os canais `kunftig:display`, `display2`, `display3` são os mesmos. **Não há divergência no fio de exibições.**
* **Diferem, só no cliente**: `DisplayClient` (perfil `kunftigDisplays` e o mapa de luz ligado em volta do desenho),
  `DeckClient` (jogadores remotos com `onGround` e `still()` para o `carrier` do futura3d; não solta mais ao voar), `DeckWalker`
  (caixas sólidas de todos os lados, `DROP` 0,75, degrau só com altura livre, 220 linhas novas) e `DisplayRenderer` (a malha compilada
  dos ossos parados, `StaticBones`) [J] [V `git diff 8349814 2bb00b3`]. São classes de cliente: o servidor nunca as carrega.
* **Só no cliente (ausentes no servidor)**: API 14 (posto de comando: `KunftigInput.STATION`, `MoveKeys`, `InputCarry`), API 15
  (`Posture`, `PostureServer`, `PostureWire`, `PostureClient` e três mixins de postura), API 16 (zoom da câmera) [J] (lista de
  arquivos dos dois jars). O `futura3d` tem `FuturaModels.setExternalState` nos dois lados (cliente `90c7159`, servidor `1c242cb`) [J].
* **Consequências**: (1) para tudo o que esta auditoria precisa de **pose e convés**, os dois lados falam o mesmo (API 12 e 13). (2) O
  servidor **precisa de jar novo** antes de o Expeditions usar o posto de comando do leme (API 14), posturas (15) ou a luneta (16);
  e de qualquer operação nova que a etapa 1 ou 2 acrescente: `DisplayWire.read` recusa operações desconhecidas, então um cliente
  novo com servidor velho só deixa de receber a operação (seguro), mas um servidor novo manda operações que um cliente
  velho ignora (seguro também). (3) O "visto no jogo" do `docs/API.md` sobre o convés foi com o `DeckWalker` antigo;
  o do `2bb00b3` mudou bastante e está só nos testes.

---

## 10. Perguntas em aberto

1. Com rede real, qual a distribuição do intervalo entre `MOVE`s em ticks do cliente (jitter) e quantos "paradas" por minuto
   isso dá? Pede dois processos (servidor e cliente) e um registro de `clock` na chegada.
2. O `START` do `ClientTickEvent` vem antes do `updateController` no TEOF (Forge/FML com Angelica)? Muda o atraso em 1 tick.
3. O estado de água do vanilla do jogador sobre o convés num cavado: o `inWater` do `EntityPlayerSP` fica verdadeiro? O Neo States
   substitui a conta (`/neostates sonda` mede "vanilla" e "1.21.1")? Medir no jogo com `/exp mar` alto.
4. Quão longe, na horizontal e na vertical, um jogador remoto fica do piso com mar de 2 a 3 m (seção 4.3)? Precisa de dois clientes.
5. `World#getFullTime()` no Crucible devolve o total do mundo como o `OceanWaves.time(dim)`?
6. O `Math.sin/cos` do Java 8 (cliente) e do Java 21 (servidor) dão o mesmo bit nos valores que o integrador usa?
7. O que o servidor faz ao receber a posição de um jogador que está "no ar" sobre um convés que se move a 0,1 m/tick com o
   Crucible: algum plugin de anticheat do TEOF reclama (já era "a verificar" no `docs/API.md`)?

---

## Apêndice A: matriz de estresse do carregamento [X]

Pose a cada 2 ticks, deslize 2, 1/3 das mensagens atrasadas 1 tick; `andando` e `parado`. Roll = 22° × amplitude, períodos de 5,7 s / frequência.

| Amplitude | Frequência (período) | Andando: diferença jogador × casco | Parado: idem | Ticks fora do piso (fora o pouso) | Maior salto de velocidade da origem (m/tick) |
|---|---|---|---|---|---|
| 1,0 (roll 22°) | 1 (5,7 s) | 1,2 mm | 0,3 mm | 0 | 0,118 |
| 1,0 | 2 (2,9 s) | 3,6 mm | 1,2 mm | 0 | 0,232 |
| 1,0 | 4 (1,4 s) | 11,8 mm | 4,7 mm | 0 | 0,449 |
| 1,6 (roll 35°) | 1 | 2,5 mm | 0,8 mm | 0 | 0,187 |
| 1,6 | 2 | 8,1 mm | 3,1 mm | 0 | 0,371 |
| 1,6 | 4 | 27,8 mm | 11,9 mm | 0 | 0,718 |

Nenhuma célula com deriva local acima de 5e-13 m nem saída de bordo.

## Apêndice B: reproduzir

```bash
cd /home/user/work/tmp/KUN/sim
D=/home/user/Kunftig/src/main/java/com/akashic/kunftig/display
javac -d out $D/DeckShape.java $D/DeckPose.java $D/DeckWalker.java $D/DisplayMotion.java \
  $D/DisplayWire.java $D/DisplayTracker.java src/com/akashic/kunftig/display/*.java
java -cp out com.akashic.kunftig.display.Sim    # atraso, Euler contra slerp, carregamento, estresse
java -cp out com.akashic.kunftig.display.Cost   # bytes da MOVE e custo do DisplayTracker
java -cp out com.akashic.kunftig.display.Bench  # DeckWalker.step com 50 a 2.048 caixas
```

Jars: `javap -c -p -cp /home/user/work/tmp/KUN/client com.akashic.kunftig.display.DisplayWire` e o mesmo com `.../server`, e
`diff`. Relógio e pacotes do vanilla: `/home/user/work/tmp/KUN/allmc.txt` (`javap -c -p` de `bin/minecraft.jar`; `bao.p()` é o
`runTick`, `bje.e()` o `updateController`, `bjf.b()` o `WorldClient.tick`).
