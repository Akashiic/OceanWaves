# Frente WAVE: fidelidade e coerência da dinâmica das ondas

Auditoria sem alterar nenhum repositório. **[verificado]** quer dizer lido no código ou medido rodando o próprio
núcleo do OceanWaves (`core/`, compilado com `javac` fora do repositório, em `/home/user/work/tmp/wave/classes`);
**[hipótese]** é inferência ainda não conferida em jogo. Nenhum número de FPS nem resultado de jogo foi medido aqui.

Material desta frente (tudo em `/home/user/work/tmp/wave/`):

* `src/WaveAudit.java` → `out.txt`: estados de mar, velocidades, ∂ζ/∂t, **desenho × física**, pressão em
  profundidade, custo.
* `src/WaveAudit2.java` → `out2.txt`: raio de influência da costa por escala, efeito do `EdgeFade`.
* `src/WaveAudit3.java` → `out3.txt`: erro da dispersão de água funda em oceanos de 15 a 25 blocos.
* `src/Cost.java` → `out4.txt`: custo e erro do `HeightPatch`.
* `src/Kin.java` → `out5.txt`: protótipo da cinemática (u, w, aceleração, pressão) e o custo dela.
* `src/Fp.java`: o resumo `fingerprint` do estado "moderado" no Java 21 deste contêiner.

Todos os números de "config padrão" usam o que `OceanConfig.seaState()` monta
(`src/main/java/com/akashic/oceanwaves/OceanConfig.java:253-266`): preset, vento para 20°, altura ×1, escala de
Froude 0,5, crista 0,8, 64 componentes, semente 0x0CEA17. Tempos de CPU são do Java 21 deste contêiner (4 núcleos,
sem GPU); o cliente roda Java 8, que o próprio projeto mede ~1,5× mais lento (`docs/ARQUITETURA.md`, "Desempenho
medido").

---

## Resumo

1. **A matemática do mar é sólida e bem documentada.** JONSWAP com as leis de Hasselmann e o teto de
   Pierson-Moskowitz, espalhamento Donelan-Banner amostrado pela inversa exata, dispersão com Newton, maquete de Froude
   exata, Gerstner com a garantia L = ΣQkа < 1 (sem dobra), correção do nível médio euleriano, inversa de Newton para a
   altura num ponto e a costa com garantia de não dobrar. A síntese é determinística: o resumo do "moderado" no
   Java 21 daqui é `3473730547258373915`, igual ao fixado em `SynthesisTest.PINNED`. **[verificado]**
2. **A física e o desenho usam a mesma função, o mesmo estado, a mesma semente e o mesmo nível do mar por mundo.**
   O servidor manda os 17 números do `SeaState`, o nível, a largura da costa, o γ de quebra e a região
   (`net/MessageSeaState.java:198-216`); os dois lados chamam `Synthesis.build` e `OceanColumns.isOpenSea`. **[verificado]**
3. **Mas a superfície que uma entidade leria hoje não é exatamente a desenhada**, por cinco motivos, do maior para o
   menor: (a) `OceanWaves.surfaceY` ignora a costa e a região (perto da costa ou fora de uma região limitada o
   erro é a onda inteira); (b) o `EdgeFade` do cliente apaga as ondas de 88 a 120 m da câmera (alcance 8) e a física
   não (erro rms de 0,29 m no "moderado" e 0,75 m na "tempestade"); (c) o LOD espectral com `espacamento = 1` tira
   as ondas de menos de 2,5 m e enfraquece as de 2,5 a 5 m mesmo junto da câmera (2,5 a 3 cm rms, até 10 cm, perto;
   5 a 7 cm rms, até 22 cm, a 100–128 m); (d) a malha é linear entre vértices; (e) o relógio do cliente atrasa meia
   latência. (c) e (d) são pequenos para um casco; (a) e (b) não. **[verificado; os números são do núcleo, não do jogo]**
4. **Falta toda a cinemática da água para um corpo flutuante de verdade.** A `WaveSample` traz a velocidade da
   *partícula de Gerstner* da malha (Q·a·ω na horizontal), que é 0,67 a 0,77 da velocidade orbital linear (a·ω), sem
   decaimento com a profundidade, sem aceleração e sem pressão. Não há ∂ζ/∂t euleriano (o `vy` lagrangiano difere
   dele 6 a 8% rms). **[verificado]**
5. **Empuxo de coluna contra η superestima a excitação das ondas.** A pressão dinâmica decai como e^{kz}: no calado
   da fragata (2,5 m) ela é 0,60 de η no "moderado" e 0,77 na "tempestade" (rms ponderado pelo espectro). O
   `FloatingBody` do Expeditions integra ρg(η − y) por coluna (`FloatingBody.java:92-101`): força de onda 1,3 a 1,7×
   maior que a de Froude-Krylov. **[verificado na conta; o efeito no balanço em jogo é hipótese]**
6. **A integração com o Expeditions descrita nos documentos não existe neste checkout nem no servidor.**
   `docs/ARQUITETURA.md:99-125` e o `README.md:48` dizem que está no branch
   `claude/minecraft-ocean-physics-mod-n82b79` (`OceanWavesSea`); esse branch não está no clone local e o
   `plugins/Expeditions-0.1.0.jar` do servidor só tem o `WaveField` próprio (quatro senoides, sem Gerstner, sem
   inversa). **[verificado]**
7. Proposta (§8): um `SeaSampler` no núcleo, uma instância por corpo ou thread, com `surface` (altura euleriana,
   normal, gradiente, ∂ζ/∂t, com costa e região, partida quente), `kinematics` (u, v, w, aceleração e pressão
   dinâmica com estiramento de Wheeler na fase lagrangiana, coerente com a superfície desenhada: p = 0 nela, resíduo
   medido 3·10⁻¹⁸ m) e um `PressurePatch` por camadas para cascos. Custo medido: 3,4 a 5,2 µs por ponto de
   superfície e +2 µs pela cinemática (Java 21, 64 componentes); 0,25 a 0,86 ms por camada de casco 48 × 14 m.

---

## 1. Espectro, espalhamento, dispersão e síntese

### 1.1 Espectro: JONSWAP (e Pierson-Moskowitz como limite) **[verificado]**

`core/.../Spectrum.java:39-78`: α = 0,076(U²/Fg)^0,22, ω_p = 22(g²/UF)^{1/3}, σ = 0,07/0,09, γ = 3,3 no mar de vento,
o pico limitado por baixo pelo de PM (ω_p ≥ 0,855 g/U) e α ≥ 0,0081. São as fórmulas de Hasselmann et al. (1973) na
forma de Horvath (2015). O marulho é um JONSWAP com γ = 10 escalado para o Hs pedido (`Spectrum.java:59-64`). O fator
TMA (`Spectrum.java:103-116`) está certo, mas **nenhum preset o usa** (profundidade infinita em
`SeaState.wind`, `core/.../SeaState.java:88-91`). A própria documentação registra que as leis de α e ω_p dão ~30% a
mais de Hs que a lei de energia do JONSWAP (`docs/MATEMATICA.md` §1.1): é uma escolha conhecida, não um erro.

### 1.2 Espalhamento direcional: Donelan-Banner **[verificado]**

`Spreading.java:132-157`: β(ω/ω_p) por partes (2,61 r^1,3; 2,28 r^-1,3; 10^ε acima de 1,6) e D(θ) = β/(2 tanh βπ) sech²(βθ).
A direção de cada componente sai da inversa fechada da acumulada, com quantis da sequência da razão áurea
(`Synthesis.java:296-299`): a energia por direção é a do espectro, por construção. Duas licenças, ambas documentadas:
β fica constante abaixo de r = 0,56 e o marulho estreita β por 4 (`Synthesis.java:233`). Não há espalhamento
bimodal acima do pico (Ewans), o que é aceitável.

### 1.3 Dispersão **[verificado]**

`Dispersion.java:172-201`: ω² = gk tanh(kh), Newton com o chute de Fenton e McKee. Na maquete de Froude
(`Synthesis.java:274-307`) o k físico vira k/s e ω é recalculado com a profundidade do modelo: (k/s)·(s·h_fís) = k·h_fís,
logo ω_modelo = ω_fís/√s. Exato. **O problema é que a profundidade é uma só e infinita.** Oceano do Minecraft tem 15
a 30 blocos (o TEOF tem água em y 30). Medido em `out3.txt`:

| estado | h = 15: variância com kh < π | h = 25 | erro de ω (ponderado pela energia), h = 15 / 25 | componente mais longa em h = 20 |
|---|---|---|---|---|
| moderado | 58% | 12% | 1,3% / 0,3% | 151 m, ω 1,21× alto |
| fresco | 80% | 49% | 3,3% / 0,6% | 180 m, 1,29× |
| tempestade | 88% | 73% | 7,5% / 2,1% | 250 m, 1,47× |
| marulho | 100% | 98% | 19,8% / 8,1% | 225 m, 1,40× |

Para o desenho isso pouco importa (as duas pontas usam a mesma ω, então não há desacordo). Para a física importa
duas vezes: (1) as ondas longas andam rápido demais para o fundo que existe; (2) a cinemática sob a superfície
(velocidade orbital, pressão) em profundidade finita é cosh(k(z+h))/cosh(kh), não e^{kz}, e a órbita é elíptica. Com o
`SeaState.depth` finito o TMA e a dispersão já ficariam coerentes; falta a cinemática usar o mesmo h.

### 1.4 Síntese **[verificado]**

`Synthesis.java:226-309`: N intervalos iguais em ln ω, frequência com tremor dentro de cada um, amplitude da energia do
intervalo, fase uniforme, componentes ordenadas do mais longo ao mais curto (o LOD corta um prefixo). Hs medido nos
presets (`out.txt`, seção A): calmo 0,12 m, brisa 0,50, moderado 1,16, fresco 1,87, forte 2,29, tempestade 2,99,
marulho 0,78 (na escala 0,5). Determinismo: só `StrictMath` e `SplitMix64` na síntese; as avaliações por tick e por
quadro usam `Math` (1 ulp de diferença entre JVMs, ~10⁻¹⁶ m, `docs/adr/ADR-0002`). O resumo fixado bate no Java 21
daqui (`src/Fp.java`); no Java 8 não foi possível conferir (não há JDK 8 neste contêiner).

Observação de configuração: `SeaPresets.java:203` dá 80 componentes à tempestade, mas `OceanConfig.seaState()` aplica
`withComponents(64)` por cima (`OceanConfig.java:264`): o que roda é 64 (resumos diferentes: `958b5fc4…` × `661a75e1…`).
Não é desacordo entre os lados (os dois recebem o mesmo estado), só documentação enganosa.

---

## 2. Gerstner, deslocamento horizontal e o limite de dobra **[verificado]**

* Superfície trocoidal P(x₀, t) = (x₀ − ΣQa d̂ sen θ, Σa cos θ + c) (`OceanField.java:43-99`, `GridEvaluator.java:298-414`).
* Qᵢ = crista·(1 para k ≤ 2k_pico, senão 2k_pico/k), reescalado se L > `maxSteepness` = 0,85
  (`Synthesis.java:317-342`). Com os padrões L fica em 0,47 a 0,55 (`out.txt`, A): o teto só morde com altura ≥ 2.
  Com o teto, M = ΣQa satura (Q·a = L/k): tempestade ×2 → L 0,85, M 6,33 m.
* Prova de não dobra: ‖∇D‖ ≤ L < 1 ⇒ x₀ ↦ x₀ + D é bijetora, J ≥ (1 − L)². Correto, e os testes
  `surfaceNeverFolds`/`shoreNeverFoldsTheSurface` cobrem.
* Correção do nível médio c = ΣQka²/2 (moderado 1,5 cm; tempestade 4,6 cm): o navio não afunda no mar agitado.
* **A órbita da partícula de superfície é elíptica** (horizontal Q·a, vertical a). Em água funda a órbita física é um
  círculo de raio a. Para a forma da superfície e o ∂ζ/∂t isso é irrelevante (o vertical é exato); para a velocidade
  horizontal da água não (§7).

---

## 3. Amplitudes e períodos na escala de blocos **[verificado]**

`out.txt`, seção A (1 bloco = 1 m, escala de Froude 0,5):

| estado | Hs | onda de pico (λ, T, a) | Σa (crista máx.) | L | M = ΣQa | c |
|---|---|---|---|---|---|---|
| calmo | 0,12 m | 3,9 m, 1,58 s, 1 cm | 0,30 | 0,47 | 0,23 | 1,4 mm |
| brisa | 0,50 | 15,5 m, 3,15 s, 5 cm | 1,15 | 0,52 | 0,79 | 6 mm |
| moderado | 1,16 | 34,6 m, 4,71 s, 14 cm | 2,51 | 0,48 | 1,71 | 1,5 cm |
| fresco | 1,87 | 51,2 m, 5,73 s, 23 cm | 3,88 | 0,50 | 2,63 | 2,6 cm |
| forte | 2,29 | 64,1 m, 6,41 s, 29 cm | 4,69 | 0,50 | 3,13 | 3,2 cm |
| tempestade | 2,99 | 71,8 m, 6,78 s, 39 cm | 6,02 | 0,55 | 4,09 | 4,6 cm |
| marulho | 0,78 | 133 m, 9,24 s, 15 cm | 1,03 | 0,05 | 0,72 | 1,6 mm |

São proporções de mar real em meia escala: a fragata (48 × 13 m, calado 2,53 m; `navios.yml:215-223` do
Expeditions) tem 1,4 comprimento de onda de pico no "moderado" e 0,67 na "tempestade" (onde caturra de verdade). Os
períodos de pico (4,7 a 6,8 s) ficam perto dos períodos naturais de arfagem/caturro de um casco dessas medidas, o que
é bom: haverá ressonância visível sem precisar exagerar nada. A "amplitude" de uma componente é pequena (cm) porque
a energia está espalhada em 64; a altura vem da soma (crista máxima Σa).

---

## 4. A hora do mar nos dois lados, e o lag

### 4.1 O que o código faz **[verificado]**

* Servidor: `OceanWaves.time(dim) = WorldServer.getTotalWorldTime()/20` (`api/OceanWaves.java:69-72`). Discreto por
  tick, sem fração. O mundo de plugin do Crucible tem o seu próprio `WorldInfo` (e o seu tempo total): a API lê o do
  mundo certo pelo número da dimensão.
* Cliente: `NetHandlerPlayClientTimeMixin` lê o tempo total de cada `S03PacketTimeUpdate`
  (`mixin/mixins/client/NetHandlerPlayClientTimeMixin.java:196-199`) e o `OceanClock` (`core/.../OceanClock.java`)
  ajusta uma reta (inclinação = TPS estimado + correção do erro em 1,5 s). Antes da primeira observação vale o relógio
  do mundo do cliente (`client/ClientSea.java:89-95`); a troca de mundo zera o relógio (`ClientSea.java:110-113`).
* Que o servidor manda o S03 a cada 20 ticks está conferido pelo projeto no 1.7.10 vanilla; **no Crucible não pude
  conferir**: o `server.jar` traz só os `binpatches` (as classes vanilla vêm do jar baixado em tempo de execução).

### 4.2 Avaliação

* **Lag do servidor é coerente.** A 15 TPS o mar anda a 0,75 do tempo real nos dois lados: o servidor porque o tempo
  é o tick, o cliente porque estima a frequência (`OceanClock.java:127-135`). Um corpo integrado no servidor com
  dt = 1 tick = 0,05 s de tempo do mar continua certo em relação à onda (tudo fica em câmera lenta, junto).
* **Corpo integrado no cliente** (o barco que o próprio jogador conduz, o jogador nadando) precisa do mesmo cuidado:
  dt do passo = avanço do relógio do mar no tick (≈ TPS_servidor/20 × 0,05 s), não 0,05 s fixo; senão, a 15 TPS, o
  barco do cliente oscila a 20 Hz contra um mar a 15. **[hipótese de projeto; nada disso existe ainda]**
* **O relógio pode andar para trás.** Com erro > 40 ticks o `observe` salta (`OceanClock.java:121-125`), também
  para trás. Depois de uma travada de mais de ~2 s em que o servidor pula ticks (o 1.7.10 desiste de recuperar
  atrasos acima de 2 s), o cliente terá corrido ~40+ ticks à frente e volta. A documentação diz que "o mar nunca anda
  para trás" (`docs/MATEMATICA.md` §7): vale só dentro de 40 ticks. **[código verificado; o efeito em jogo é hipótese]**
* **Atraso.** O relógio do cliente fica meia latência atrás do servidor (o pacote chega atrasado e é tomado como
  "agora"), mais até um tick porque o 1.7.10 processa os pacotes no tick do cliente **[hipótese]**. Uma pose de navio
  calculada no tick T chega com o mesmo atraso: casa com o mar do cliente, *desde que o desenho da pose não acrescente
  atraso*. O deslizamento do Kunftig acrescenta N ticks e o plano documentado compensa calculando a física N ticks à
  frente (`docs/ARQUITETURA.md:112-116`). Entidades vanilla têm a sua própria interpolação no cliente (o barco tem
  incrementos de posição) que **não** é compensada: um barco calculado no servidor aparecerá atrás da onda por essa
  interpolação **[hipótese; a frente BOAT tem o bytecode]**. Com ∂ζ/∂t rms de 0,46 m/s (moderado) e 0,85 m/s
  (tempestade), cada tick de atraso vale ~2 a 4 cm de altura.
* Um tick de folga entre "quando o plugin lê `time()`" e "quando o S03 foi montado" também vale ~2 a 4 cm. Pequeno.

---

## 5. PONTO CRÍTICO: a superfície que uma entidade leria é exatamente a desenhada?

### 5.1 O que cada lado amostra **[verificado]**

| | física (`OceanWaves.surfaceY`, `OceanField`, `HeightPatch`) | desenho (`OceanRenderer` → `OceanMesher` → `GridEvaluator.evaluateFast`) |
|---|---|---|
| componentes | todas (`surfaceY`) ou um prefixo escolhido (o plano do Expeditions: λ ≥ 4 m) | pesadas pelo LOD espectral: λ/s < 2,5 some, 2,5 a 5 parcial (`SpectralLod.java:269-278`); s = max(`espacamento`, 0,02·d), `espacamento` = 1 por padrão (`OceanConfig.java:57`) |
| deslocamento horizontal | invertido por Newton: x₀ + D(x₀) = x (`OceanField.java:110-136`; `HeightPatch.java:254-294` sobre a grade bilinear) | vértices na grade lagrangiana x₀, deslocados; entre vértices, triângulos (linear) |
| costa | `surfaceY`: **nenhuma** (`api/OceanWaves.java:146-153`); `OceanField`/`HeightPatch`: a `ShoreField` que quem chama passar (`OceanWaves.shore`) | `ShoreField` por chunk com folga `influenceRadius` limitada a 32 (`client/OceanChunks.java:262-302`) |
| região | `surfaceY`: **nenhuma**; `shore()` conta a borda da região como costa | colunas fora da região não são desenhadas (`OceanChunks.java:163-168`) |
| borda do alcance | nenhuma | `EdgeFade` multiplica a atenuação de (alcance − 8 − largura) a (alcance − 8) (`client/EdgeFade.java:22-31`, `OceanRenderer.java:268`) |
| nível médio | c do conjunto usado (o prefixo recalcula c, `WaveSet.java:315-320`) | c do conjunto inteiro (`GridEvaluator.java:380`) |
| tempo | tick inteiro/20 | `OceanClock` (contínuo, meia latência atrás) |
| precisão | double | fasores e somas em float relativos à origem do chunk |

### 5.2 Quanto difere, medido no núcleo **[verificado]**

`out.txt`, seção D: câmera 3 m acima do mar, `espacamento` 1, `detalhe` 0,02, sem costa; a altura "desenhada" é a da
malha que o `OceanMesher` gera (inversa sobre a interpolação bilinear dos vértices deslocados, aproximando os dois
triângulos do quad), contra a inversa exata com todas as componentes; 300 pontos por distância, tempos sorteados.

| estado | d = 4 m | 16 | 32 | 64 | 96 | 128 |
|---|---|---|---|---|---|---|
| moderado, rms / máx (m) | 0,026 / 0,069 | 0,025 / 0,075 | 0,024 / 0,069 | 0,032 / 0,116 | 0,048 / 0,132 | 0,066 / 0,173 |
| fresco | 0,028 / 0,081 | 0,028 / 0,083 | 0,028 / 0,087 | 0,034 / 0,111 | 0,049 / 0,135 | 0,069 / 0,218 |
| tempestade | 0,030 / 0,099 | 0,030 / 0,086 | 0,030 / 0,091 | 0,040 / 0,115 | 0,058 / 0,163 | 0,074 / 0,216 |

Perto da câmera o erro é quase todo das componentes de 1,25 a 5 m que o `espacamento` = 1 corta (rms das de λ < 5 m:
3,5 a 4 cm; `out.txt`, A). Longe, o LOD corta mais. Para um casco de dezenas de metros isso se cancela na integração;
para um item ou um jogador (0,6 m) não.

Fora dessa tabela, e bem maiores:

* **`EdgeFade`** (`out2.txt`): com alcance 8 chunks a faixa vai de 88 a 120 m; a 96 m o desenho tem 84% da onda, a
  112 m, 16%. Erro rms ≈ (1 − A)·Hs/4: até **0,29 m no moderado e 0,75 m na tempestade**. Com alcance 6 a faixa começa
  em 56 m. Um navio de 48 m visto de 100 m caturra num mar que, na tela, está liso.
* **Costa e região em `surfaceY`**: perto da costa o desenho vai a zero (A → 0 nas colunas de terra) e `surfaceY`
  dá a onda inteira (até Σa = 2,5 m no moderado). Numa região limitada (`regiao.modo = spawn` ou `ponto`) `surfaceY` dá
  ondas onde o cliente desenha a água vanilla parada.
* **Folga da costa no cliente** (`OceanChunks.java:266-268`, `Math.min(m, 32)`) contra a do servidor
  (`api/OceanWaves.java:134-140`, sem limite): o raio de influência passa de 31 com escala ≥ 0,75 e altura ×2 na
  tempestade (46 e 62 blocos, `out2.txt`). Aí o cliente erra na borda dos chunks (fresta, o mesmo defeito que o
  `CLAUDE.md` descreve) e a atenuação dele deixa de ser a do servidor.

### 5.3 Coisas que **estão** certas **[verificado]**

* O deslocamento horizontal **é** invertido para achar a altura em (x, z), nas duas implementações da física
  (`OceanField.eulerian`, Newton com a matriz I + ∇D; `HeightPatch.height`, Newton sobre a bilinear). Erro do
  `HeightPatch` contra a inversa exata: 1,5 mm (s = 0,5, 49 componentes), 4,7 mm (s = 0,5, 64), 6,2 mm (s = 1, 49),
  15 mm (s = 1, 64) (`out4.txt`).
* A costa entra igual: A(x₀) multiplica η e D, A²·c no nível médio, nos dois caminhos (`OceanField.java:85-98`,
  `GridEvaluator.java:381-392`, `HeightPatch` passa `att` ao `evaluateFast`). Com a mesma `ShoreField` (folga ≥
  `influenceRadius` nos dois lados) os valores batem.
* Mesmo estado, semente, componentes e nível do mar por mundo nos dois lados (`ServerSea.java:330-338`,
  `ClientSea.java:120-130`); restLevel = nível − 1 + 8/9 nos dois (`api/OceanWaves.java:78-80`, `ClientSea.java:49-51`).
* `isOpenSea` é uma só (`world/OceanColumns.java:281-291`), usada pelo servidor (`shore`) e pelo cliente (`OceanChunks`).

---

## 6. Cliente e servidor: o mesmo mar? **[verificado]**

Sim, com três ressalvas: (1) `OceanWaves.waves()` devolve o `WaveSet` compartilhado do servidor com vetores
`public final double[]` mutáveis (`WaveSet.java:229-245`, `ServerSea.java:311-317`): um plugin que escreva neles
dessincroniza a física dos clientes sem aviso; (2) cliente sem o mod no servidor usa a própria configuração (modo
local, `ClientSea.java:131-140`): aí não há física de servidor para comparar; (3) colunas de chunk não carregado
contam como mar aberto nos dois lados, mas o conjunto de chunks carregados difere entre cliente e servidor: o
desacordo só aparece na borda do alcance, longe dos corpos.

---

## 7. O que falta para um corpo flutuante

### 7.1 O que a `WaveSample` dá hoje **[verificado]**

Altura, deslocamento, ∂η/∂x₀, ∂D/∂x₀ (normal e jacobiano), e a velocidade da partícula de Gerstner na superfície
(`OceanField.java:62-65`): (vx, vz) = ΣQaω d̂ cos θ, vy = Σaω sen θ. Só na superfície, só da partícula.

### 7.2 O que um corpo precisa e não há

| grandeza | para quê | hoje |
|---|---|---|
| ∂ζ/∂t euleriano no ponto (x, z) | taxa de variação do volume submerso, amortecimento por velocidade relativa, "subir com a onda" | não há. O `vy` lagrangiano difere: rms 0,028 m/s em 0,46 (moderado), 0,066 em 0,85 (tempestade), 6–8% (`out.txt`, C). Fórmula fechada com o que a amostra já tem: ∂ζ/∂t = vy − ∇₀η·(I + ∇D)⁻¹·v_h |
| gradiente/normal no mundo | inclinação para corpos pequenos, deriva de declive | a normal da superfície parametrizada existe (`WaveSample.normal`); o gradiente euleriano ∇ζ = (I + ∇D)⁻ᵀ∇₀η não é exposto |
| velocidade orbital u, v, w em profundidade | arrasto (Morison), "nadar sente a onda", itens à deriva | não há. A horizontal da partícula é Q·a·ω: **0,67 a 0,77 da orbital linear a·ω** (`out.txt`, B: moderado 0,32 contra 0,47 m/s rms), porque Q < 1 e cai como 1/k nas ondas curtas, que dominam a velocidade |
| aceleração da água | força de inércia (Froude-Krylov horizontal + massa adicional: (1 + C_a)ρV·a) | não há |
| pressão dinâmica p(x, y, z) | empuxo por coluna no fundo do casco (Froude-Krylov vertical) | não há; quem integra usa ρg(η − y), hidrostática até a superfície |
| profundidade finita | cosh no lugar de e^{kz}, órbita elíptica | não há (§1.3) |

### 7.3 Por que a pressão importa para a fragata **[verificado na conta]**

Numa coluna vertical do casco o empuxo é a pressão no fundo vezes a área. Na teoria linear, p_fundo = ρg(−z_b) +
ρg Σ aᵢ e^{kᵢ z_b} cos θᵢ (z_b < 0); o empuxo "de coluna" usa ρg(η − z_b), isto é, sem o e^{kz}. Razão rms entre a
parte dinâmica a 2,5 m (calado da fragata) e η (`out.txt`, E):

| calmo | brisa | moderado | fresco | forte | tempestade | marulho |
|---|---|---|---|---|---|---|
| 0,03 | 0,33 | 0,60 | 0,69 | 0,73 | 0,77 | 0,87 |

Ou seja, o `FloatingBody` do Expeditions (`core/.../physics/FloatingBody.java:92-101`, `sub = sea.height(...) − wy`)
superestima a força de onda em **1,3 a 1,7×** nos mares que interessam, e as ondas curtas (brisa) mexem o casco 3×
mais do que deveriam. Não é "presepada", é uma aproximação comum (Atlas, jogos), mas o pedido aqui é o literal.

### 7.4 Para "nadar sente a onda" e itens **[hipótese de projeto]**

O vanilla decide "na água" pelos blocos (`handleWaterMovement`, `isInsideOfMaterial`). Com ondas: na crista acima do
nível de repouso não há bloco de água (o jogador "voa" dentro da onda desenhada); no cavado há blocos acima da
superfície (o jogador "nada" no ar). Precisa: imersão = ζ(x, z) − y_pés com a mesma ζ que o cliente desenha, empuxo
proporcional à fração imersa, arrasto sobre a velocidade relativa à orbital (u, w a meia imersão), e o mesmo na névoa
submersa da câmera. A coluna tem de ser `isOpenSea` (a regra única). O jogador local é simulado no cliente: usa o
relógio do cliente; itens e mobs, no servidor: usam o tick. É a frente de entidades; aqui só a interface (§8).

---

## 8. Proposta: a interface de amostragem da física

### 8.1 Princípios

1. **Uma função, um dono do tempo.** O núcleo dá a superfície e a cinemática; quem integra passa t: o servidor
   `totalWorldTime/20` do tick; o cliente o relógio do mar no início do tick (não `System.nanoTime` por quadro). Cada
   corpo tem uma só autoridade (servidor para navios do Expeditions, cliente para o jogador e o barco que ele
   conduz), para não comparar duas simulações com meia latência de diferença.
2. **A mesma superfície que o cliente desenha**: costa e região dentro do amostrador, não opcionais; prefixo de
   componentes só por escolha explícita do tamanho do corpo (λ < 2× o corpo se cancela).
3. **Coerência cinemática com a malha**: a fase de cada componente é avaliada no x₀ que a inversa achou (a fase
   lagrangiana), e a profundidade é medida a partir da superfície local (estiramento de Wheeler, z_s = y − ζ em água
   funda; z_s = h(y − ζ)/(h + ζ) em profundidade finita). Assim p_din/ρg na superfície é η(x₀) = ζ − c: a pressão total
   é zero na superfície desenhada (resíduo medido 3,45·10⁻¹⁸ m, `out5.txt`; o c de 1,5 cm se subtrai), e a velocidade
   não explode sob as cristas.
4. **Nada alocado no laço**; uma instância por thread ou por corpo, como `OceanField`/`HeightPatch`.

### 8.2 Esboço (núcleo, Java 8, sem Minecraft)

```java
/** Uma instância por corpo ou thread. t em segundos do mar; coordenadas do mundo; y relativo ao nível de repouso. */
public final class SeaSampler {
    public SeaSampler(WaveSet waves, Attenuation shoreAndRegion, double depth);   // depth = SeaState.depth (∞: funda)
    /** Prefixo pelo tamanho do corpo: componentes com λ < minWavelength não entram (0: todas). */
    public void minWavelength(double meters);

    /** Superfície euleriana em (x, z): ζ, ∇ζ, normal, ∂ζ/∂t. Partida quente pelo x₀ guardado em p. */
    public void surface(double x, double z, double t, SeaProbe p);
    /** Cinemática em (x, y, z) abaixo da superfície (usa p.x0, p.z0, p.zeta da última surface no mesmo x, z):
     *  u, v, w; aceleração; p_din/ρg. Acima da superfície devolve zeros. */
    public void kinematics(double y, double t, SeaProbe p);
}

public final class SeaProbe {          // reaproveitável
    public double x0, z0;              // partícula achada (partida quente)
    public double zeta, dzdx, dzdz, dzdt;
    public double nx, ny, nz, attenuation;
    public double u, v, w, ax, ay, az, pDyn;   // pDyn em metros de coluna d'água (÷ ρg)
}

/** Para cascos: K camadas (b = 0, −1, −2, −3 m…) avaliadas como HeightPatch com amplitudes aᵢ·e^{kᵢb};
 *  a pressão no fundo de cada coluna sai da isóbara lagrangiana: interpolação em b das alturas das camadas. */
public final class PressurePatch {
    public void update(double minX, double minZ, double maxX, double maxZ, double spacing, double t,
                       Attenuation att, double[] layers);
    public double pressureHead(double x, double y, double z, double[] guess);   // ρg·(isóbara − y)
}
```

O `PressurePatch` é a versão "literal" do empuxo por colunas: no modelo de Gerstner as isóbaras são as superfícies de
nível lagrangiano b (trocoides de amplitude a·e^{kb}); b = 0 é a superfície desenhada. Com K = 3 a 4 camadas e
interpolação em b, o fundo de cada coluna lê a pressão certa, e a superfície continua a do cliente.

### 8.3 Custo (Java 21 daqui; o Java 8 do cliente é ~1,5× mais lento)

| operação | custo medido | fonte |
|---|---|---|
| `surface` num ponto, 64 componentes, partida fria / quente | 5,2 µs / 3,4 µs | `out.txt`, F (`OceanField.eulerian`) |
| uma avaliação lagrangiana (o passo de Newton) | 1,7 µs | idem |
| `kinematics` num ponto (64 componentes, com um `exp` por componente) | ~2,0 µs | `out5.txt` (protótipo `Kin.java`) |
| `HeightPatch` 48 × 14 m, s = 0,5 / 1, 49 componentes (λ ≥ 4 m) | 0,67 / 0,25 ms por atualização | `out4.txt` |
| idem, 64 componentes | 0,86 / 0,32 ms | idem |
| consulta no `HeightPatch` | 0,07 a 0,09 µs | `out.txt`, F |

Orçamentos derivados (contas, não medidas em jogo):

* **Fragata, servidor**: `PressurePatch` com 4 camadas a s = 1 (as camadas fundas perdem as ondas curtas e podem usar
  menos componentes) ≈ 4 × 0,25 ms = 1 ms por atualização; a cada 2 ticks com interpolação no tempo, ~0,5 ms/tick por
  navio. As ~300 a 1.500 colunas custam 0,1 µs cada (0,03 a 0,15 ms). Mais a cinemática no centro (arrasto,
  Froude-Krylov horizontal): alguns pontos × 5 µs.
* **Jogador ou item**: 1 `surface` + 1 `kinematics` por tick ≈ 5,5 µs (quente) no Java 21. 200 itens no mar ≈ 1,1 ms
  por tick no servidor; se ficar caro, um `HeightPatch` por chunk com entidades na faixa da superfície, recalculado por
  tick, compensa a partir de ~50–100 consultas por chunk.
* **Barco do Et Futurum** (no cliente de quem conduz): 4 a 9 pontos sob o casco por tick, ~20 a 50 µs.

---

## 9. Achados

| id | sev. | título | evidência |
|---|---|---|---|
| WAVE-1 | alto | `surfaceY` ignora costa e região, e aloca por chamada | `api/OceanWaves.java:146-153` |
| WAVE-2 | alto | velocidade da água é a da partícula de Gerstner (0,67–0,77 da orbital), sem profundidade, aceleração nem pressão | `core/.../OceanField.java:62-65`, `out.txt` B |
| WAVE-3 | alto | empuxo de coluna contra η superestima a força de onda 1,3–1,7× no calado da fragata | `Expeditions/core/.../FloatingBody.java:92-101`, `out.txt` E |
| WAVE-4 | alto | a integração Expeditions↔OceanWaves documentada não existe no checkout nem no servidor | `docs/ARQUITETURA.md:99-125`, `README.md:48`, `plugins/Expeditions-0.1.0.jar` |
| WAVE-5 | médio | `EdgeFade` apaga no desenho as ondas que a física sente (88–120 m com alcance 8; 56–88 m com 6) | `client/EdgeFade.java:22-31`, `OceanRenderer.java:268`, `out2.txt` |
| WAVE-6 | médio | LOD espectral com `espacamento` 1: 2,5–3 cm rms perto da câmera, 5–7 cm a 100–128 m | `OceanConfig.java:57-58`, `SpectralLod.java:269-278`, `out.txt` D |
| WAVE-7 | médio | folga da costa do cliente limitada a 32; a do servidor não | `client/OceanChunks.java:266-268`, `api/OceanWaves.java:134-140`, `out2.txt` |
| WAVE-8 | médio | dispersão e cinemática sempre de água funda em oceanos de 15–25 blocos | `SeaState.java:88-91`, `SeaPresets.java:196-207`, `out3.txt` |
| WAVE-9 | médio | o relógio do cliente salta para trás depois de travadas longas; corpos no cliente sem dt do tempo do mar | `core/.../OceanClock.java:121-125` |
| WAVE-10 | baixo | sem ∂ζ/∂t euleriano nem gradiente no mundo; `vy` lagrangiano difere 6–8% rms | `WaveSample.java`, `out.txt` C |
| WAVE-11 | baixo | `WaveSet` compartilhado com vetores públicos mutáveis pela API | `WaveSet.java:229-245`, `ServerSea.java:311-317`, `api/OceanWaves.java:51-53` |
| WAVE-12 | baixo | o preset tempestade promete 80 componentes; a configuração roda 64 | `SeaPresets.java:203`, `OceanConfig.java:264` |
| WAVE-13 | baixo | atraso de interpolação de entidades vanilla não compensado (o do Kunftig é) | `docs/ARQUITETURA.md:112-116` (hipótese) |

---

## 10. Perguntas abertas

1. Onde está o branch `claude/minecraft-ocean-physics-mod-n82b79` do Expeditions (`OceanWavesSea`, `ShipMotion`,
   `DERIVA.md`)? Ele usa a cinemática do OceanWaves no plano (Froude-Krylov, deriva de Stokes, segundo a ARQUITETURA);
   a frente de navios precisa dele para auditar.
2. O `EdgeFade` existe por causa do Mirai/Distant Horizons. Pode ficar além da distância em que navios são vistos (ou
   ser suspenso num raio em volta de cada exibição de navio conhecida)?
3. Qual profundidade usar: uma por mundo (a mediana medida, como o nível do mar), ou por coluna (quebraria a
   frequência única por componente; a fase teria de mudar com o fundo: refração, que a §5.1 da MATEMATICA deixa como
   trabalho futuro)?
4. O `S03PacketTimeUpdate` no Crucible sai mesmo a cada 20 ticks e com o tempo total do mundo do jogador? (Não
   conferível aqui: as classes vanilla do Crucible vêm do binpatch.)
5. Para a horizontal da água: velocidade orbital linear (física) ou a da partícula de Gerstner (a que a textura e a
   espuma mostram)? Proposta: linear para forças; Gerstner só para efeitos visuais que acompanham a malha.
