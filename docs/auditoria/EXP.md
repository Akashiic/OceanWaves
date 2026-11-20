# Frente EXP: o Expeditions e a fragata no mar do OceanWaves

Auditoria sem alterar nenhum repositório. **[verificado]** = lido no código ou medido rodando o próprio núcleo
(compilado com `javac` fora dos repositórios, em `/home/user/work/tmp/exp/`); **[hipótese]** = inferência não conferida
em jogo. Nenhum número de FPS ou resultado de jogo foi medido aqui; os tempos de CPU são do Java 21 deste contêiner.

Material desta frente (tudo em `/home/user/work/tmp/exp/`):

| arquivo | o que é |
|---|---|
| `exp-branch/` | clone do branch remoto `claude/minecraft-ocean-physics-mod-n82b79` (`b50c9cf`, 20/11/2026) do Expeditions |
| `src/Hydro.java` → `hydro.txt`, `rao_clean.txt` | hidrostática das colunas da fragata, equilíbrio, decaimento livre, passo, RAO em ondas regulares (modelos A/B/C) |
| `src/Sea.java` → `sea_clean.txt` | a fragata no mar de verdade do OceanWaves (moderado, fresco, tempestade), custo do caminho do plugin, erros de amostragem no tempo |
| `verify/` | prova de carga de `OceanWavesSea` sem o OceanWaves no classpath |
| `serverjar/`, `kserver/` | classes extraídas do `Expeditions-0.1.0.jar` e do `KunftigApi` do servidor (só `javap`) |

Modelos usados nas medidas (mesmo casco de 504 colunas do `navios.yml`, mesmas massas, amortecimentos e passo):

* **A**: o `FloatingBody` do Expeditions como está (empuxo de coluna = ρg·(ζ − y_fundo), hidrostático até a superfície).
* **B**: a mesma integração com a pressão de Froude-Krylov linear no fundo e no topo de cada coluna (decaimento e^{kz}).
* **C**: B mais o momento das pressões horizontais (−∫∇ₕp dV) em torno do centro de gravidade.

---

## Resumo

1. **O checkout local está desatualizado; a integração com o OceanWaves existe, mas só num branch remoto.** O local
   (`74903e3`, 15/11) tem só o `WaveField` próprio (4 senos) e um contador de ticks como tempo do mar. O branch
   `claude/minecraft-ocean-physics-mod-n82b79` (`b50c9cf`, não mesclado na `main`) já lê o mar do OceanWaves
   (`OceanWavesSea`/`HullSea`: mesmas componentes, tempo do mundo/20, costa, região, `restLevel` por mundo, pose adiantada)
   e tem os 6 graus de liberdade (`ShipMotion`, `WaveForces`). No servidor real, o `Expeditions-0.1.0.jar` é ainda
   anterior à fragata, o Kunftig é da API 13 e o OceanWaves não está instalado. **[verificado]**
2. **Esse branch derruba o plugin num servidor sem o OceanWaves**: `OceanWavesSea.present()` lança
   `NoClassDefFoundError: com/akashic/oceanwaves/core/Attenuation` (o verificador de bytecode carrega o tipo ao ligar a
   classe), e ela é chamada no inicializador de campo do `ExpeditionsCommand`. Provado com uma sonda. **[verificado]**
3. **A hidrostática é de verdade** (Arquimedes coluna a coluna, não linear, com momento de restauração saindo da
   integração; equilíbrio a 5 mm do calado do gerador; integração estável: erro de período < 2% mesmo com passo de 0,1 s).
   **Mas a excitação das ondas não é a de um casco num mar com pressão**: (a) **o balanço é excitado 2,4 a 3,3 vezes
   demais** porque falta o momento das pressões horizontais em torno do CG (em onda longa de través o modelo dá
   roll/declive = BM/GM ≈ 2,9 em vez de 1); no mar irregular de través, balanço rms de 4,2° contra 1,65° (fresco) e
   7,0° contra 2,9° (tempestade). (b) Sem o decaimento e^{kz} da pressão (efeito Smith) a arfagem sai 1,15 a 1,35 vez e
   o caturro 1,2 a 1,3 vez maior; RAO de arfagem de través no pico 1,52 contra 1,12. **[verificado nas contas]**
4. **O casco que a física integra não é o casco da ficha**: as 504 colunas grossas (36 × 7) dão GM 1,085 m; o
   `navios.yml` (grade fina 56 × 12) diz 0,821 m (+32%). O balanço natural real é 10,8 s; o
   `Loading.rollPeriod()` diz 12,2 s e o `periodo-balanco` do yml diz 10,94 s (sem a água arrastada: dois erros que se
   cancelam). **[verificado]**
5. Massa adicional e amortecimento são fatores constantes (2,0/1,25/1,8 e 30/10/35% do crítico, sobre a velocidade
   absoluta); os raios de giração são de regra (0,38·B, 0,25·L), não do modelo. Os períodos resultantes (arfagem
   3,85 s, caturro 4,43 s, balanço 10,8 s) são plausíveis para um casco de ~43 m na linha d'água; nada disso é dependente
   da frequência. **[verificado / comparação com navios reais: hipótese fundamentada]**
6. **Custo**: o caminho do plugin custa 0,92 ms por tick por navio só no vertical (duas grades de 54 × 54 m a cada 2
   ticks + 2 consultas frias por coluna por subpasso); dá para cortar 5 a 10 vezes. **[verificado, medido]**
7. **20 Hz no servidor contra o desenho suave**: o esquema atual (pose a 10 Hz, deslizamento do Kunftig de 2 ticks e
   física adiantada 2 ticks) é correto em fase e o erro da interpolação linear é desprezível (≤ 2,5 mm, 0,011°); o
   rastreador do Kunftig junta `setRotation` + `moveTo` numa só mensagem por tick. Os desacertos visíveis que sobram vêm
   do lado do desenho (EdgeFade/LOD do OceanWaves, ver WAVE-5/6) e da falta de uma máscara do casco (a água desenhada
   atravessa o interior do navio). **[verificado / hipótese no visual]**
8. Proposta (§7): uma só dinâmica de corpo rígido de 6 graus no núcleo do Expeditions, alimentada por um campo de
   **pressão** do OceanWaves (o `PressurePatch` da frente WAVE, sobre a pegada girada do casco), com as forças das
   pressões verticais **e horizontais** integradas nas colunas, coeficientes de radiação tirados do casco pelo gerador
   (faixas, na frequência natural de cada modo), inércias tiradas das peças do modelo, autoridade no servidor e o
   cliente só desenhando (com máscara do casco no OceanWaves).

---

## 1. Onde o código está (o que roda e onde)

| | checkout local `/home/user/Expeditions` (`74903e3`) | branch remoto `b50c9cf` (clone em `tmp/exp/exp-branch`) | servidor real (`/home/user/ext/server/x`) |
|---|---|---|---|
| mar | `WaveField.swell`: 4 senos de água funda, `/exp mar` (`core/.../physics/WaveField.java:40-55`) | OceanWaves pela API 3 (`plugin/.../OceanWavesSea.java`, `HullSea.java`); sem ele, o `WaveField` | `plugins/Expeditions-0.1.0.jar`: só `WaveField` (classes listadas por `unzip`; `plugin.yml` ainda sem a fragata) |
| tempo do mar | contador próprio do plugin: `ticks += MOTION_TICKS`, `t0 = (ticks − 2)/20` (`plugin/.../ExpeditionsCommand.java:89,95`) — **viola a regra** "tempo do mar = tempo total do mundo / 20" | `OceanWaves.time(dim) + 0,1 s` (`ExpeditionsCommand.java:264-267` do branch) | idem local |
| graus de liberdade | 3 (arfagem, balanço, caturro); posição e rumo fixos na `waterline` | 6: `FloatingBody` (vertical) + `ShipMotion` (plano: avanço, deriva, guinada; arrasto, ondas de 1ª e 2ª ordem, Stokes, vento, amarra, fundo) | 3 |
| pose ao cliente | `setRotation` + `moveTo(origem, 2)` a cada 2 ticks (`ShipDisplays.java:185-188`) | idem, com a física 2 ticks à frente | idem local |
| Kunftig | API 9 a 13 usadas | até a 16 (leme, posturas, luneta) | servidor `kunftig-0.1.0-dev-8349814`: `API_VERSION = 13` (`javap` do `KunftigApi`); cliente TEOF `kunftig-2bb00b3` (API 16) |
| OceanWaves | — | `libs/oceanwaves-8b71472.jar` `compileOnly` (ancestral do `c56f672` local) | **não instalado** (nem no cliente) |

O `OceanWaves/docs/ARQUITETURA.md:99-125` descreve a integração do branch, e a frente WAVE (WAVE-4) a deu como
inexistente porque o branch não estava no clone local: ele está em `origin` (`git ls-remote`). A `main` remota
(`381cdf7`) também está à frente do checkout local (leme, tripulação, canhões) e atrás do branch.

---

## 2. A física atual, com rigor

### 2.1 Hidrostática por colunas: é de verdade? **Sim, no vertical.** [verificado]

`FloatingBody.step` (`core/.../physics/FloatingBody.java:80-117`) gira cada coluna (x, fundo, z, convés, área) por
Rx(caturro)·Rz(balanço) em torno do CG, acha a altura do mar sobre ela e soma ρg·(ζ − y_fundo)·A, cortado na altura da
coluna. Para um casco que é um "campo de alturas" visto de baixo, a pressão hidrostática só tem resultante vertical no
fundo das colunas (as paredes verticais se anulam), então isso **é** Arquimedes exato no mar parado, e não linear:
banda grande, convés imerso, quilha fora d'água e ressonância paramétrica saem da conta, sem coeficiente inventado.
Os braços de momento usam o ponto girado no referencial sem guinada (τ_x = −r_z·F, τ_z = r_x·F): certos.

Medido com as 504 colunas da fragata (`hydro.txt`, §1-2):

| | colunas grossas (o que o plugin integra) | `navios.yml` (gerador, grade 56 × 12) |
|---|---|---|
| calado de equilíbrio | 2,539 m | 2,534 m |
| KB / BM | 1,508 / 3,146 m | — / ≈ 2,88 m (de GM + KG − KB) |
| **GM** | **1,085 m** | **0,821 m** |
| Awp | 296,4 m² | 290,4 m² |
| GML | 60,16 m | 60,69 m |
| LCB | 0,331 m | 0,323 m |
| trim em equilíbrio (300 s) | −0,7 cm em 48 m | — |

Forma: linha d'água ~42,7 × 9,2 m (boca máxima 13 m bem acima d'água: a "taça" da fragata), Cb ≈ 0,50, Cwp ≈ 0,76,
L/B ≈ 4,7, B/T ≈ 3,6.

Erros de discretização e de forma **[verificado no código; efeito: hipótese]**:

* **GM +32%** (achado EXP-4): `tools/shipgen/ships.py:585` calcula a hidrostática com 56 × 12 colunas por bordo e
  `ships.py:709` grava no yml 36 × 7. O momento de inércia da flutuação (∝ x²) é dominado pelas colunas da borda, onde a
  célula grossa conta área inteira. O `ShipYamlTest` só confere o calado (±6 cm), então passa.
* **Obras mortas**: `hull.py:143-163` acha o fundo por bisseção sobre `half(s, h)`, que com `tumble = 0.12`
  (`fragata.py:48`) não é monótona acima de u = 0,62; a coluna vai do fundo ao convés (`ships.py:574`) mesmo onde o
  costado já fechou para dentro, e o `z` é o de `h.z(s)`, sem o lançamento da proa nem o balanço da popa
  (`hull.py:60`, `fragata.py:47`). Isso só muda as contas com banda grande ou proa enterrando (curva de GZ, reserva de
  flutuação), não o GM.
* **Coluna inclinada**: `sub` é medido na vertical do mundo e multiplicado pela área no plano do navio: erro O(θ²)
  (6% do volume da coluna a 20°). Pequeno.

### 2.2 A excitação das ondas: o que falta para ser "um casco num mar de pressão" [verificado nas contas]

O empuxo de coluna contra a elevação é a força de Froude-Krylov **só das pressões verticais e sem o decaimento com a
profundidade**. Dois erros, medidos em ondas regulares (a = 0,15 m, casco parado no plano, `rao_clean.txt`):

| λ (m) | T (s) | través: arfagem A | B | balanço/declive A | B | **C** | proa: caturro/declive A | C |
|---|---|---|---|---|---|---|---|---|
| 20 | 3,58 | 1,24 | 0,71 | 0,29 | 0,22 | 0,08 | 0,06 | 0,04 |
| 28 | 4,23 | **1,52** | **1,04** | 0,46 | 0,38 | 0,14 | 0,10 | 0,06 |
| 40 | 5,06 | 1,46 | 1,12 | 0,77 | 0,67 | 0,24 | 0,56 | 0,42 |
| 64 | 6,40 | 1,29 | 1,10 | 1,55 | 1,41 | 0,51 | 0,96 | 0,80 |
| 100 | 8,00 | 1,18 | 1,07 | 3,41 | 3,22 | 1,16 | 1,08 | 0,96 |
| 170 | 10,43 | 1,11 | 1,04 | **11,18** | 10,88 | **4,62** | 1,10 | 1,01 |
| 300 | 13,86 | 1,06 | 1,02 | 6,75 | 6,61 | **2,34** | 1,08 | 1,01 |

* **Balanço (o maior erro).** A física certa para um corpo flutuante numa onda longa de través: a gravidade aparente
  fica normal à superfície e o navio se alinha com ela (balanço estático = declive; a 300 m a ressonância amplifica
  1/(1 − (10,8/13,9)²) ≈ 2,4, e o modelo C dá 2,34). O modelo de colunas soma só ρg·declive·I_T = Δg·BM·declive e o
  compara com Δg·GM·θ: balanço estático = (BM/GM)·declive ≈ 2,9·declive. O que falta é o momento, em torno do CG,
  da força horizontal das pressões (que age no centro de carena, abaixo do CG). Na ressonância o modelo do plugin dá
  11,2 vezes o declive; com a física completa, 4,6. **O `ShipMotion` do branch aplica a força horizontal de
  Froude-Krylov + massa adicional no CG (`ShipMotion.java:180-182`), sem momento**: o erro continua lá.
* **Arfagem e caturro (efeito Smith).** A pressão da onda no fundo (2,5 m) é e^{−k·2,5} da da superfície; a coluna
  contra ζ ignora isso. Pico da arfagem de través 1,52 (A) contra 1,12 (B); os números confirmam o WAVE-3.
* **Difração e radiação** ficam fora dos três modelos: na arfagem de um casco largo e raso a difração é da ordem da
  Froude-Krylov perto da ressonância **[hipótese, teoria das faixas]**; o amortecimento "de radiação" é um fator fixo
  (§2.3).

No mar de verdade do OceanWaves (estado igual ao de `OceanConfig.seaState()`: preset, vento para 20°, escala 0,5,
crista 0,8, 64 componentes, semente 0x0CEA17), 300 s depois de 60 s de partida, casco parado no plano
(`sea_clean.txt`):

| mar (Hs) | rumo | arfagem rms/máx (m) A · C | balanço rms/máx (°) A · C | caturro rms/máx (°) A · C |
|---|---|---|---|---|
| moderado (1,16 m) | proa ao mar | 0,115/0,31 · 0,094/0,25 | 0,63/1,65 · 0,50/1,41 | 1,08/2,88 · 0,81/2,15 |
| moderado | través | 0,317/0,87 · 0,236/0,69 | **2,47/7,96 · 0,88/2,87** | 0,95/2,39 · 0,70/1,85 |
| fresco (1,87 m) | proa ao mar | 0,291/0,85 · 0,242/0,74 | 1,39/4,11 · 0,88/2,88 | 2,11/5,84 · 1,69/4,77 |
| fresco | través | 0,507/1,38 · 0,413/1,19 | **4,18/12,27 · 1,65/5,41** | 1,46/4,39 · 1,14/3,45 |
| tempestade (2,98 m) | proa ao mar | 0,603/1,99 · 0,524/1,83 | 2,97/8,92 · 1,67/5,09 | 3,17/11,12 · 2,67/8,93 |
| tempestade | través | 0,821/2,84 · 0,712/2,28 | **6,99/21,8 · 2,90/10,2** | 1,94/5,85 · 1,57/4,84 |

A coluna "Al" (o modelo A sobre a superfície linear de todas as componentes) bate com A em ±1% na arfagem e no caturro e
em até 25% no balanço (proa ao mar, moderado): a superfície do plugin (Gerstner euleriana, λ ≥ 4 m) não é a fonte do
erro, a física da pressão é. Isso explica o "balanço máximo de 19,3° da fragata no mar fresco" e os 44° na tempestade
da certificação do branch (`exp-branch/docs/DERIVA.md`, §10), que passou porque os critérios dela são correlação,
Arquimedes na média e não emborcar, não a resposta em frequência.

### 2.3 Massa adicional, amortecimento e inércias [verificado no código; comparação: hipótese fundamentada]

`FloatingBody.java:17-28,52-58`: massa em arfagem 2,0·m, inércia de balanço 1,25·I, caturro 1,8·I; amortecimento
linear como fração do crítico (arfagem 0,30, balanço 0,10, caturro 0,35) sobre a **velocidade absoluta**, mais o
quadrático de balanço (1,2·|ṙ|ṙ). O gerador (`ships.py:591-613`): navio leve = 80% do deslocamento de projeto com KG a
46% do pontal, mais os pesos de cada nível; **kxx = 0,38·B, kzz = 0,25·L** (regras de bolso, não da distribuição de
massa das 13.875 peças do modelo); LCG = LCB por hipótese; a guinada usa o mesmo raio do caturro
(`HullGeometry`, branch).

| | período medido (decaimento, `hydro.txt` §3) | ζ medido | navios reais de ~40 m na linha d'água |
|---|---|---|---|
| arfagem | 3,85 s | 0,30 | T ≈ 2,4·√T_calado ≈ 3,8 s; a₃₃/m para B/T ≈ 3,6 e ω²B/2g ≈ 1,2 fica em 1,0 a 1,6 (Lewis, com redução 3D): massa total 2,0 a 2,6·m, período 3,85 a 4,3 s **[hipótese]** |
| caturro | 4,43 s | 0,35 | ≈ 0,9 a 1,2 vez o de arfagem; ζ 0,3 a 0,5: coerente |
| balanço | 10,8 s (5°), 11,0 s (15°) | 0,10 (5°), 0,12 (15°) | regra IMO 2·C·B/√GM: 8,8 s com a boca na linha d'água, 13,6 s com a máxima; ζ de 0,05 a 0,1 no casco, **mais o amortecimento aerodinâmico do aparelho** (não modelado) |

Os períodos estão na faixa real, e a ressonância de arfagem/caturro cai no pico dos mares fresco a tempestade (4,7 a
6,8 s), como num navio de verdade. O que não é "literal": coeficientes que não dependem da frequência, nenhum
acoplamento de radiação (deriva↔balanço, arfagem↔caturro), amortecimento sobre a velocidade absoluta (na teoria linear
a excitação tem a parcela iωB·ζ_ef que isso omite), e o `rollC`/`pitchC` calculados com o GM do yml (0,821), não com o
das colunas.

### 2.4 Integração numérica [verificado]

Euler semi-implícito (simplético) em subpassos de 1/80 s, 8 por chamada de 2 ticks (`ExpeditionsCommand.java:44,93-111`).
Ângulos de Euler integrados com momentos nos eixos do mundo (válido para ângulos pequenos e médios; sem termos
giroscópicos). Sensibilidade ao passo (`hydro.txt` §4):

| dt | arfagem (s / ζ) | balanço | caturro |
|---|---|---|---|
| 1/160 | 3,848 / 0,298 | 10,763 / 0,102 | 4,435 / 0,352 |
| 1/80 | 3,846 / 0,298 | 10,758 / 0,102 | 4,425 / 0,353 |
| 1/40 | 3,825 / 0,299 | 10,750 / 0,103 | 4,417 / 0,354 |
| 1/20 | 3,817 / 0,301 | 10,750 / 0,103 | 4,383 / 0,356 |
| 1/10 | 3,767 / 0,305 | 10,733 / 0,103 | 4,333 / 0,361 |

Estável e precisa com folga; o que limita o passo no branch é o contato com o fundo (30 rad/s, `ShipMotion.java:37`):
1/40 s ainda serve (ω·dt = 0,75). Os 8 subpassos são margem, não necessidade.

---

## 3. A fragata no mar do OceanWaves: o que o branch acerta

* **Mesmo mar**: as componentes do servidor (`OceanWaves.waves(dim)`), a mesma inversa euleriana do Gerstner
  (`HeightPatch`), costa e região (`OceanWaves.shore` com `shoreMargin` + 16 blocos), mesmo `restLevel(dim)` (no
  `Akashic/Spawn`: 31 − 1 + 8/9 = 30,889) e a dimensão pelo nome do mundo, relida por minuto
  (`OceanWavesSea.java:77-194` do branch). **[verificado]**
* **Mesmo tempo**: `OceanWaves.time(dim)` (tempo total do mundo / 20); a física vai 2 ticks à frente para compensar o
  deslizamento de 2 ticks do Kunftig (`ExpeditionsCommand.java:264-267`). **[verificado]**
* **A superfície sobre o casco**: no meio do intervalo de 0,1 s, a interpolação no tempo entre as duas grades erra 7 mm
  rms (31 mm no máximo) contra a inversa exata das mesmas componentes; contra todas as 64 (o que o cliente desenha perto
  da câmera), 31 mm rms, 11 cm no máximo (`sea_clean.txt`, fim). Para um casco de 43 m é desprezível. **[verificado]**
* **Coordenadas do mundo**: o vertical integra no referencial sem guinada com o mar amostrado no mundo; o plano integra
  no referencial do navio e leva o CG no mundo (`ShipMotion.java:230-240`). **[verificado]**
* **Certificação** (`OceanDriftCertificationTest`) com o mar de verdade, 15 min, mares fresco a tempestade × 2:
  Arquimedes na média, correlação com a elevação, sem disparar na troca súbita de mar. Útil, mas não testa a resposta
  em frequência (por isso não pega §2.2) e monta o mar por `SeaPresets.get(nome).withSeed` (a tempestade sai com 80
  componentes; o servidor roda 64: WAVE-12). **[verificado]**

## 4. Lacunas e riscos

### 4.1 Carga do plugin sem o OceanWaves (crítico) [verificado]

`OceanWavesSea` tem campos e assinaturas com tipos do OceanWaves (`ShoreField`, `WaveSet`, `SeaState`) e passa um
`ShoreField` onde se espera `Attenuation` (`OceanWavesSea.java:189` → `HullSea.shore(Attenuation)`). Ao ligar a classe, o
verificador precisa carregar `Attenuation` para checar a atribuição; sem o mod, `NoClassDefFoundError`. A sonda
(`tmp/exp/verify/`, classes do branch compiladas contra o jar fixado e rodadas sem ele, Java 21, class file 65)
imprime `falhou: java.lang.NoClassDefFoundError: com/akashic/oceanwaves/core/Attenuation` na chamada de
`OceanWavesSea.present()`. Essa chamada está no inicializador de campo do `ExpeditionsCommand`
(`ExpeditionsCommand.java:191` do branch), construído no `onEnable`: o erro sobe e o plugin não liga. É exatamente o
estado do servidor de produção hoje (sem OceanWaves). O `CLAUDE.md` do branch diz que "o plugin carrega sem o mod":
o teste disso nunca rodou sem o jar no classpath (os testes têm `testImplementation(oceanWaves)`).
Correção: a detecção por `Class.forName` + campo, numa classe sem nenhum tipo do OceanWaves; `OceanWavesSea` só é tocada
depois.

### 4.2 Física (§2)

* Momento das pressões horizontais ausente → balanço ×2,4 a ×3,3 (EXP-2).
* Pressão sem decaimento e^{kz} → arfagem ×1,15–1,35, caturro ×1,2–1,3 (EXP-3; confirma WAVE-3).
* GM das colunas ≠ GM da ficha (EXP-4).
* Coeficientes fixos, inércias de regra (EXP-6, EXP-7). Amortecimento do aparelho ausente: com as velas largas o
  balanço real de um navio de pano é bem mais amortecido **[hipótese]**.
* Cinemática mista: o vertical sente a superfície de Gerstner euleriana; o plano (`WaveForces`) usa a velocidade e a
  aceleração de Airy (a·ω), enquanto a superfície desenhada desloca as partículas Q·a (0,67 a 0,77 da orbital linear,
  WAVE-2). Não há um campo de pressão único do qual as duas saiam (EXP-10).
* Sob pano largo o `Sailing` põe a posição e o rumo cinematicamente (`vx = 0`, `ExpeditionsCommand.java:297-314` do
  branch); as ondas só entram como correnteza somada (EXP-16).
* Alagamento, água no convés, emborcamento: fora (o `Damage` do branch soma água como peso, sem inércia).

### 4.3 Custo (EXP-8) [verificado, `sea_clean.txt`]

| peça | custo | por quê |
|---|---|---|
| `HullSea.prepare`: 2 `HeightPatch` | **1,21 ms a cada 2 ticks** | quadrado de 54 × 54 m (raio do casco + 2 em volta do CG, `HullSea.java:29-35,60-71` do branch) mais a margem do deslocamento, para uma pegada de ~43 × 9 m; recalcula as duas grades em vez de reaproveitar a de t₁ como t₀ |
| `FloatingBody.step` sobre o `HullSea` | **80 µs por subpasso** (0,64 ms por 8) | 504 colunas × 2 consultas (`HeightPatch.blend`) com Newton frio (`guess = null`, até 8 iterações) |
| idem sobre o `WaveField` próprio | 23,5 µs por subpasso | 4 senos |
| total do vertical | **0,92 ms por tick por navio** | Java 21; o Crucible também é Java 21 |

O `DERIVA.md` §7 do branch diz "um passo custa 4 a 6 µs por navio (504 colunas)": não bate com o passo vertical medido
aqui. Cortes óbvios: grade sobre a pegada girada (~5×), reaproveitar t₁ (2×), partida quente por coluna (~2×), λ ≥ 8 m
com espaçamento 2 para as camadas fundas (onde e^{kz} já apagou as curtas), 4 subpassos por chamada.

### 4.4 Servidor a 20 Hz, cliente suave [verificado]

* O rastreador do Kunftig guarda o estado e manda um `MOVE` por tick com o último alvo e o último deslizamento
  (`Kunftig/.../display/DisplayTracker.java:170-195,371-372`): o `setRotation` (deslizamento de 1 tick,
  `BukkitDisplayBridge.java:341-351`) seguido do `moveTo(…, 2)` no mesmo tick vira uma mensagem só, com 2 ticks.
* O cliente desliza posição, guinada, caturro e balanço juntos, em linha reta por tick, e interpola o quadro pelo
  `partialTicks` (`DisplayMotion.java:48-91`). Com a física adiantada 2 ticks, a pose do instante t chega à tela no
  instante t do relógio do cliente (o atraso de meia latência é o mesmo do `OceanClock`).
* Erro da interpolação linear a 10 Hz na fragata de través no mar fresco: **2,5 mm de arfagem, 0,011° de balanço,
  0,009° de caturro** no máximo. Não precisa de 20 Hz nem de Hermite.
* Riscos que sobram **[hipótese]**: rajadas de pacotes (o deslizamento recomeça da pose atual), o relógio do cliente
  saltando para trás depois de travadas longas (WAVE-9), e o jogador no convés: o `DeckClient` o leva com a pose
  deslizada, coerente com o que ele vê.

### 4.5 O que o jogador vê além do casco [hipótese, com o código lido]

* **EdgeFade e LOD do OceanWaves** (WAVE-5, WAVE-6): de 88 a 120 m da câmera (alcance 8) o cliente apaga as ondas que a
  física sente: a fragata vista de longe caturra num mar liso.
* **Sem máscara do casco**: nada no OceanWaves recorta a superfície dentro da pegada de um navio (busca por
  `kunftig|casco|hull|expedit` em `OceanWaves/src/main/java`: só um comentário da API). O piso da coberta fica 13 px
  (0,81 m) acima da linha d'água (`Expeditions/docs/FRAGATA.md:558-559`); com arfagem relativa de dezenas de cm a mais de
  1 m nos mares fresco e tempestade (§2.2), a água desenhada vai atravessar o porão e a coberta. Os blocos de água
  vanilla dentro do casco já são uma convenção do projeto (`CLAUDE.md` do Expeditions), mas a superfície ondulada é
  nova.

---

## 5. Requisitos de "literal" (o que a física precisa ter)

1. **Uma pressão, não uma altura.** As forças vêm de p(x, y, z, t) integrada no casco: vertical (fundo − topo de cada
   coluna) **e** horizontal (−∫∇ₕp dV por coluna, com o braço até o CG). Na superfície desenhada p = 0 (isóbara
   lagrangiana b = 0 do Gerstner, WAVE §8.1-8.2), em profundidade a·e^{kb} (ou cosh em água rasa, WAVE-8).
2. **Radiação e difração com números do casco**: a₃₃, a₄₄, a₅₅, a₂₂, a₂₄ e os B correspondentes na frequência natural de
   cada modo (teoria das faixas com seções de Lewis ou ajuste fechado), calculados pelo gerador e escritos no
   `navios.yml`; difração de onda longa como (ρ∇ + a)·(aceleração da água) no ponto de ação certo; amortecimento sobre a
   velocidade relativa à água.
3. **Viscoso**: balanço de Ikeda simplificado (atrito, vórtices do bojo, quilha), arrasto cruzado já existente, e o
   amortecimento aerodinâmico do aparelho conforme o pano.
4. **O casco certo**: as colunas do runtime são as mesmas da hidrostática da ficha (ou a ficha sai delas), com o costado
   real (tumblehome, lançamentos) e um teste que compare GM/GML/Awp do yml com os das colunas.
5. **Inércias das peças**: massa e centro de cada osso (volume × densidade do material) → KG, LCG e o tensor de inércia
   (kxx, kyy, kzz e o produto xz) por nível.

## 6. Plano de verificação (sem jogo)

* Barcaça retangular em onda longa de través: balanço estático/declive → 1 (hoje 2,9 com o GM daqui). Arfagem de
  barcaça: RAO de Froude-Krylov fechado com e^{−kT}.
* RAO da fragata em ondas regulares (como `Hydro.java`) contra teoria das faixas da mesma forma; tolerâncias por faixa
  de λ/L.
* `ShipYamlTest`: GM, GML e Awp das colunas do runtime contra o yml (±3%).
* Orçamento: o vertical de um navio abaixo de 0,15 ms por tick no Java 21.
* Sonda de carga sem o OceanWaves (o que `tmp/exp/verify/` faz) como teste do plugin.

---

## 7. Arquitetura proposta (onde roda cada parte)

```
            OceanWaves (mod, servidor e cliente; núcleo Java 8 puro)
            ├─ SeaSampler / PressurePatch (WAVE §8): ζ, ∇ζ, ∂ζ/∂t, p/ρg nas camadas b = 0, −1, −2, −4 m,
            │  ∇ₕp, aceleração da água; costa + região dentro; grade sobre um RETÂNGULO GIRADO
            ├─ API 4: pressurePatch(dim, centro, guinada, comprimento, boca, espaçamento) e a hora (time)
            └─ cliente: máscara de cascos (pegadas mandadas pelo servidor ou pelo Kunftig) e EdgeFade
               ignorado onde há navio à vista
                                │  (mesma JVM no Crucible; compileOnly; detecção sem tipos do mod)
            Expeditions core (Java puro, testado)
            ├─ HullModel: colunas (as mesmas da ficha) + volumes molhados por coluna
            ├─ Seakeeping6: um corpo rígido de 6 graus (troca FloatingBody + metade do ShipMotion)
            │    M·ν̇ + C(ν)ν = F_hidrostática+FK(p) + F_dif + F_rad(A, B) + F_visc + F_vento + F_amarra + F_fundo
            │    M = massa + matriz de massa adicional; F das pressões vert. e horiz. com braço até o CG
            ├─ Sailing por cima: empuxo das velas e leme como forças, não posição imposta
            └─ testes: RAO, limite de onda longa, decaimento, GM da ficha
            shipgen (Python, offline)
            ├─ inércias das peças → kg, lcg, kxx, kyy, kzz, kxz por nível
            ├─ faixas (Lewis) → a_ij, b_ij na frequência natural de cada modo → navios.yml
            └─ colunas finas com o costado real, as mesmas da hidrostática
            Expeditions plugin (thread principal do Bukkit)
            ├─ a cada 2 ticks: PressurePatch em t₁ = time + 0,1 (reaproveita t₀), 4 subpassos, pose adiantada
            └─ opcional: a integração numa thread do plugin com o WaveSet (imutável) e a pose aplicada na principal
            Kunftig (cliente): só desenha e leva quem está no convés (como hoje)
```

Decisões e por quê:

* **Autoridade no servidor**: um navio é visto por muitos e leva vários a bordo; integrar no cliente exigiria a mesma
  dinâmica em cada cliente (o balanço é sensível às condições iniciais) e correção periódica. O erro de 10 Hz é
  desprezível (§4.4), então não há ganho visual em mover a física.
* **Pressão no OceanWaves, corpo no Expeditions**: a pressão depende do mar (componentes, Gerstner, costa, profundidade) e
  serve também ao barco do Et Futurum e às entidades (frentes BOAT e WAVE); o corpo depende do navio.
* **Custo-alvo**: PressurePatch de 4 camadas sobre a pegada girada a espaçamento 1 m (λ ≥ 4 m na camada 0, λ ≥ 8 m a
  espaçamento 2 nas fundas): ~1 ms a cada 2 ticks, ~0,5 ms/tick por navio (contas da WAVE §8.3, a medir); 504 colunas
  × 0,1 µs por consulta quente: 0,05 ms por subpasso.
* **Escala**: o mar do OceanWaves é uma maquete de Froude 0,5 e o navio está em 1 bloco = 1 m. Como os dois usam o
  mesmo g, a dinâmica é coerente; o navio se comporta como um de 2 × o tamanho no mar real do preset (ondas
  relativamente mais íngremes). É escolha de jogo, não erro; deve ficar dito.

---

## 8. Achados

| id | sev. | título | evidência |
|---|---|---|---|
| EXP-1 | crítico | o branch da integração derruba o plugin num servidor sem o OceanWaves | `exp-branch/plugin/.../OceanWavesSea.java:64-71,189`, `ExpeditionsCommand.java:191`; `tmp/exp/verify/` |
| EXP-2 | alto | balanço excitado 2,4–3,3× demais: falta o momento das pressões horizontais | `FloatingBody.java:88-104`, `ShipMotion.java:180-182` (branch); `rao_clean.txt`, `sea_clean.txt` |
| EXP-3 | alto | empuxo hidrostático até ζ (sem e^{kz}): arfagem 1,15–1,35×, caturro 1,2–1,3× | `FloatingBody.java:91-99`; `rao_clean.txt`, `sea_clean.txt` |
| EXP-4 | alto | o casco do runtime (36 × 7) tem GM 1,085 m; a ficha diz 0,821; períodos documentados incoerentes | `ships.py:585,620,709`, `Loading.java:22-24`, `hydro.txt` |
| EXP-5 | alto | o que roda e onde: checkout local sem a integração, branch não mesclado, servidor com jar antigo, Kunftig API 13 e sem OceanWaves | `git ls-remote`, `serverjar/`, `kserver/` (`API_VERSION = 13`) |
| EXP-6 | médio | massa adicional e amortecimento fixos, sobre a velocidade absoluta, sem acoplamentos nem aparelho | `FloatingBody.java:17-28,52-58,110-114` |
| EXP-7 | médio | inércias de regra (0,38·B, 0,25·L), KG e LCG por hipótese | `ships.py:591-613` |
| EXP-8 | médio | 0,92 ms/tick por navio no vertical (grade 54 × 54 dupla, Newton frio) | `HullSea.java` (branch), `HeightPatch.java:123-128`; `sea_clean.txt` |
| EXP-9 | médio | colunas sem o fechamento das obras mortas nem os lançamentos | `hull.py:133-163`, `ships.py:558-576`, `fragata.py:44-49` |
| EXP-10 | médio | vertical em Gerstner euleriano, plano em Airy: sem um campo de pressão único | `WaveForces.java` (branch), `HullSea.java` (branch) |
| EXP-11 | médio | o desenho não acompanha: EdgeFade/LOD e nenhuma máscara do casco | `OceanWaves/client/EdgeFade.java:22-31` (WAVE-5), busca em `OceanWaves/src`; `FRAGATA.md:558` |
| EXP-12 | baixo | checkout local: tempo do mar pelo contador do plugin | `ExpeditionsCommand.java:89,95` |
| EXP-13 | baixo | pose a 10 Hz com deslizamento: correta e precisa; sobram rajadas e o relógio | `DisplayTracker.java:371`, `DisplayMotion.java:77-91`; `sea_clean.txt` |
| EXP-14 | baixo | integração estável com folga: 8 subpassos são margem | `hydro.txt` §4 |
| EXP-15 | baixo | certificação não mede resposta em frequência e monta o mar diferente do servidor | `OceanDriftCertificationTest.java:309-381` (branch) |
| EXP-16 | baixo | sob pano, posição e rumo cinemáticos | `ExpeditionsCommand.java:297-314` (branch) |
| EXP-17 | baixo | fora da grade a superfície vira zero sem aviso | `HullSea.java:74-77` (branch) |

## 9. Perguntas em aberto

* O branch `claude/minecraft-ocean-physics-mod-n82b79` vai ser mesclado (e a `main` atualizada no servidor) antes de
  qualquer mudança de física? O EXP-1 tem de ser corrigido antes de ir ao servidor sem o OceanWaves.
* O OceanWaves vai ser instalado no servidor e no cliente TEOF? (Hoje em nenhum dos dois.)
* Até onde ir: radiação com coeficientes por frequência natural (barato, offline) ou memória (Cummins, estado)?
* Emborcar e alagar entram no jogo? Mudam o que a física precisa (volumes internos, água no convés).
* O Kunftig do servidor sobe para a API 16 junto (o branch usa 14 a 16 para leme, posturas e luneta)?
