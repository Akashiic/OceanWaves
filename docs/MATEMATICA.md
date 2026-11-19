# A matemática do mar

Tudo aqui está implementado em `core/` (Java 8 puro, sem Minecraft) e conferido pelos testes de
`core/src/test`. Unidades do mundo: 1 bloco = 1 m, tempo em segundos, ângulos em radianos (graus só na interface).
Plano horizontal (x, z), y para cima, como no Minecraft.

## 1. Do vento ao espectro

### 1.1 Espectro de frequência: JONSWAP

O mar de vento é descrito pela densidade espectral S(ω) (m²·s/rad), a energia por faixa de frequência angular.
Usamos o JONSWAP (Hasselmann et al., 1973), na forma de Horvath (2015, eq. 28):

$$S(\omega) = \frac{\alpha g^2}{\omega^5}\exp\!\left[-\frac54\left(\frac{\omega_p}{\omega}\right)^4\right]\gamma^{r},\qquad r = \exp\!\left[-\frac{(\omega-\omega_p)^2}{2\sigma^2\omega_p^2}\right]$$

com σ = 0,07 para ω ≤ ω_p e 0,09 acima, γ = 3,3, e os parâmetros que o vento U (m/s, a 10 m) dá depois de soprar
por uma pista F (m):

$$\alpha = 0{,}076\left(\frac{U^2}{F g}\right)^{0{,}22},\qquad \omega_p = 22\left(\frac{g^2}{U F}\right)^{1/3}.$$

Pista longa demais daria um mar mais desenvolvido que o totalmente desenvolvido: o pico não desce abaixo do de
Pierson-Moskowitz, ω_p ≥ 0,855·g/U, e α ≥ 0,0081 (`Spectrum.Jonswap.fromWind`).

A variância da superfície é m₀ = ∫S dω e a altura significativa, Hs = 4√m₀. Com γ = 1 o espectro é o de
Pierson-Moskowitz, de integral fechada m₀ = αg²/(5ω_p⁴): `PhysicsBasicsTest.integrationMatchesTheClosedForm`
confere a integração numérica contra ela (erro 10⁻⁶ depois de descontada a cauda além de 12ω_p).

> As leis de α e ω_p acima e a lei de crescimento da energia do JONSWAP (gHs/U² = 1,6·10⁻³·(gF/U²)^½) são ajustes
> diferentes aos mesmos dados; com γ = 3,3 as duas primeiras dão ~30% a mais de Hs. O teste confere só a ordem de
> grandeza contra a lei da energia e documenta a diferença.

**Marulho.** Ondas que viajaram longe da tempestade: um JONSWAP estreito (γ = 10) com o α escolhido para a altura
significativa pedida, de outra direção (`Spectrum.Jonswap.swell`). Mar de vento + marulho é o "JONSWAP duplo" do
Atlas (GDC 2019).

### 1.2 Profundidade: fator TMA

Em água de profundidade h o espectro perde energia nas frequências baixas (Bouws et al., 1985). Com ω_h = ω√(h/g),
o fator de Kitaigorodskii na aproximação de Thompson e Vincent (1983) é

$$\Phi(\omega,h) = \begin{cases}\tfrac12\omega_h^2 & \omega_h\le1\\ 1-\tfrac12(2-\omega_h)^2 & 1<\omega_h<2\\ 1 & \omega_h\ge2\end{cases}$$

(`Spectrum.tma`). O padrão é água funda (h = ∞): os oceanos do Minecraft têm 15 a 30 blocos e a costa é tratada à
parte (§5).

### 1.3 Espalhamento direcional: Donelan-Banner

$$D(\theta) = \frac{\beta}{2\tanh(\beta\pi)}\,\mathrm{sech}^2(\beta\theta),\qquad \int_{-\pi}^{\pi}D\,d\theta = 1,$$

θ a partir da direção do vento, e β(ω/ω_p) por partes (Donelan, Hamilton e Hui, 1985; Banner, 1990): 2,61·r^1,3
abaixo de 0,95, 2,28·r^-1,3 até 1,6 e 10^ε, ε = −0,4 + 0,8393·exp(−0,567·ln r²), acima. Ondas mais curtas que o pico
espalham mais.

A escolha sobre o cos^2s de Mitsuyasu ou Hasselmann: a distribuição acumulada de sech² tem inversa fechada,

$$\theta(u) = \frac{1}{\beta}\,\mathrm{atanh}\big((2u-1)\tanh(\beta\pi)\big),$$

então a direção de cada componente é *sorteada exatamente* da distribuição (amostragem por importância) em vez de
componentes em direções fixas com pesos. `PhysicsBasicsTest` confere a normalização e a inversa por integração.

### 1.4 Dispersão

$$\omega^2 = g\,k\tanh(k h),$$

resolvida em k por Newton a partir do chute de Fenton e McKee (1990) (`Dispersion.k`); em água funda, ω² = gk.

### 1.5 Escala de Froude

O mar real que um vento de 12 m/s levanta tem o pico em ~100 m de comprimento de onda, grande para um mundo de
blocos. Uma **maquete de Froude** de escala s multiplica comprimentos e amplitudes por s e tempos por √s: o vetor de
onda vira k/s e a frequência ω/√s, e (k/s, ω/√s) continua obedecendo a ω² = gk. É o mesmo mar, fisicamente válido,
em escala reduzida; o vento continua o real (para as velas do Expeditions). Padrão: s = 0,5 (`SeaPresets.SCALE`).
`SynthesisTest.froudeScaleIsAnExactScaleModel` confere a relação componente a componente.

## 2. Do espectro às componentes: síntese estratificada

Para cada sistema (mar de vento, marulho), a faixa [0,6ω_p, 8ω_p] (marulho: [0,75, 1,8]), cortada pelos
comprimentos de onda permitidos, é dividida em N intervalos iguais em ln ω. No intervalo j:

* frequência ωⱼ sorteada dentro do intervalo (estratificação com tremor);
* amplitude pela energia do intervalo: $a_j = \sqrt{2\,S(\omega_j)\,\Phi(\omega_j,h)\,\Delta\omega_j}$;
* direção θⱼ = θ_vento + θ(uⱼ), com uⱼ da sequência de baixa discrepância da razão áurea (u ← u + 0,618… mod 1);
* fase φⱼ uniforme em [0, 2π).

Como a direção sai da distribuição D e a amplitude carrega a energia inteira do intervalo, Σaⱼ²/2 → m₀: **Hs é
preservado por construção** (`SynthesisTest.significantHeightIsPreserved`, erro < 6% nos estados prontos, a parte
perdida nas pontas da faixa). Com fases independentes, a superfície é um processo gaussiano de espectro S.

As componentes saem **ordenadas do comprimento de onda mais longo para o mais curto**: cortar as curtas é tomar um
prefixo (`WaveSet.prefix`).

**Determinismo.** Só `StrictMath` e um SplitMix64 escrito à mão entram na síntese: o mesmo `SeaState` dá as mesmas
componentes, bit a bit, em qualquer JVM. `SynthesisTest.fingerprintIsPinned` fixa um resumo das componentes do
estado "moderado"; ele passa igual no Java 8 (Temurin 8u512, a JVM dos clientes) e no Java 21 (a do servidor):
**verificado** neste ambiente. O servidor manda só os 17 números do estado (~140 bytes) e cada lado sintetiza.

## 3. A superfície: ondas trocoidais (Gerstner)

Cada componente i tem vetor de onda **k**ᵢ = kᵢ d̂ᵢ e fase θᵢ = **k**ᵢ·**x**₀ − ωᵢt + φᵢ. A partícula de superfície
que em repouso estaria em **x**₀ = (x₀, z₀) está em

$$\mathbf P(\mathbf x_0,t) = \Big(\mathbf x_0 - \sum_i Q_i a_i\,\hat{\mathbf d}_i\sin\theta_i,\;\; \sum_i a_i\cos\theta_i + c\Big),$$

o modelo de Gerstner (1802), Fournier e Reeves (1986) e Tessendorf (2001, §4.1). O deslocamento horizontal
**D** = −Σ Qᵢaᵢ d̂ᵢ sen θᵢ junta as partículas nas cristas: crista aguda, vale largo.

### 3.1 Sem dobra, com garantia

O mapa **x**₀ ↦ **x**₀ + **D**(**x**₀) tem jacobiano I + ∇**D**, com

$$\nabla\mathbf D = -\sum_i Q_i a_i k_i\,(\hat{\mathbf d}_i\hat{\mathbf d}_i^{\mathsf T})\cos\theta_i,\qquad \|\nabla\mathbf D\|\le L := \sum_i Q_i k_i a_i .$$

Se **L < 1**, os autovalores de I + ∇**D** ficam no disco de raio L em torno de 1: o determinante (o jacobiano J)
nunca zera, J ≥ (1 − L)², e **x**₀ ↦ **x** é uma contração perturbada da identidade, portanto **bijetora**: a
superfície **nunca se dobra sobre si** (o defeito clássico do Gerstner com Q alto, que o GPU Gems cap. 1 só evita
pedindo ΣQwA ≤ 1 "na média"). A síntese escolhe Qᵢ = crista·wᵢ, com wᵢ = 1 para as ondas dominantes (até o dobro
do número de onda de maior amplitude) e decaindo como k_ref/kᵢ nas curtas (crista aguda é coisa de onda grande), e
reescala tudo se L passar de `maxSteepness` = 0,85 (`Synthesis.gerstner`). `OceanFieldTest.surfaceNeverFolds`
confere J ≥ (1 − L)² e a normal sempre para cima em 20 mil pontos de um mar de tempestade com a altura ×2,5.

### 3.2 O nível médio não afunda

Numa onda trocoidal a média euleriana da altura (a média sobre o *lugar*, não sobre a partícula) fica abaixo do
repouso: para uma componente, η̄ = (1/λ)∫a cos θ (1 − Q k a cos θ) dx₀ = −Qka²/2. Componentes distintas não se
cruzam na média. Somando

$$c = \sum_i \frac{Q_i k_i a_i^2}{2}$$

à altura, a média euleriana volta ao nível do mar (conservação da massa: a água não some nas ondas, e um navio não
fica mais fundo no mar agitado que no calmo). `OceanFieldTest.eulerianMeanLevelIsTheRestLevel` mede a média em
960 mil pontos (resíduo < 15% de c) e confere que sem a correção ela seria −c.

### 3.3 Assimetria crista-vale

Expandindo a inversa, uma onda trocoidal é, no lugar, η ≈ a cos θ + (Qka²/2) cos 2θ em torno do nível médio: a
segunda harmônica de Stokes. A crista fica Qka²/2 mais alta que a amplitude e o vale o mesmo tanto mais raso
(`OceanFieldTest.crestsAreSharperThanTroughs`, numa componente com ka = 0,3: crista 1,045 m, vale 0,955 m).

### 3.4 Derivadas, normal, jacobiano, velocidade

Fechadas (`OceanField.lagrangian`):

* ∂η/∂x₀ = −Σ aᵢkₓᵢ sen θᵢ, ∂η/∂z₀ = −Σ aᵢk_zᵢ sen θᵢ;
* ∂Dₓ/∂x₀ = −Σ Qᵢaᵢd̂ₓᵢkₓᵢ cos θᵢ, ∂Dₓ/∂z₀ = ∂D_z/∂x₀ = −Σ Qᵢaᵢd̂ₓᵢk_zᵢ cos θᵢ, ∂D_z/∂z₀ = −Σ Qᵢaᵢd̂_zᵢk_zᵢ cos θᵢ;
* tangentes **T**ₓ = (1 + ∂Dₓ/∂x₀, ∂η/∂x₀, ∂D_z/∂x₀), **T**_z = (∂Dₓ/∂z₀, ∂η/∂z₀, 1 + ∂D_z/∂z₀);
  normal **N** = **T**_z × **T**ₓ / ‖·‖;
* jacobiano J = (1 + ∂Dₓ/∂x₀)(1 + ∂D_z/∂z₀) − (∂Dₓ/∂z₀)²: menor que 1 nas cristas (superfície comprimida), a máscara
  de espuma de Tessendorf;
* velocidade da partícula (lagrangiana): ∂**D**/∂t = Σ Qᵢaᵢωᵢ d̂ᵢ cos θᵢ, ∂η/∂t = Σ aᵢωᵢ sen θᵢ (para o arrasto de um
  casco em relação à água).

`OceanFieldTest.analyticDerivativesMatchFiniteDifferences` confere todas contra diferenças centrais.

**Precisão.** A fase é calculada em double e reduzida a [0, 2π) antes do seno (o `Math.sin` do Java 8 tem um caminho
lento para argumentos grandes). Com x até 3·10⁷ (a borda do mundo) e t de anos, θ < 10⁹ rad e o erro de
arredondamento fica abaixo de 10⁻⁷ rad.

## 4. A altura num ponto do mundo: inversa por Newton

A física pergunta "qual a altura da água em (x, z)?", mas a superfície é dada pela partícula **x**₀. É preciso
resolver **F**(**x**₀) = **x**₀ + **D**(**x**₀) − **x** = 0.

O Crest (Unity) e o Atlas fazem iteração de ponto fixo, **x**₀ ← **x** − **D**(**x**₀), 4 vezes. Ela converge com
taxa L (com L = 0,5, 4 iterações deixam 6% do erro inicial, que é o deslocamento, até metros). Aqui é **Newton**: a
matriz I + ∇**D** já sai da mesma avaliação (é a do jacobiano), e

$$\mathbf x_0 \leftarrow \mathbf x_0 - (I+\nabla\mathbf D)^{-1}\mathbf F(\mathbf x_0),$$

com convergência quadrática. `OceanFieldTest.inversionFindsTheParticleThatLandsOnThePoint`: 5 mil pontos, resíduo
< 10⁻⁶ m, no máximo 5 iterações (tipicamente 3). Partindo do **x**₀ da consulta anterior (um casco que andou
centímetros num tick), basta uma iteração (`warmStartTakesOneIterationForAHullThatBarelyMoved`). Se o determinante
cair abaixo de 0,05 (não acontece com L < 1), o passo vira o de ponto fixo, sempre seguro.

### 4.1 Retalho de alturas para cascos

Um casco com 300 colunas e 4 subpassos por tick faria 1.200 inversões × 64 componentes por tick. `HeightPatch`
avalia a grade lagrangiana sobre o retângulo do casco (alargado pelo maior deslocamento, Σ Qᵢaᵢ) uma vez, com o laço
de tabelas da §6, e cada coluna inverte por Newton sobre a interpolação bilinear de **D** (o jacobiano sai da
célula), sem trigonometria. Erro de interpolação ~ a(ks)²/8: com s = 0,5 m e ondas de 4 m ou mais (um casco não
sente as mais curtas, que se cancelam sobre ele), `HeightPatchTest` mede < 2 cm rms contra a inversão exata.

## 5. A costa

### 5.1 Atenuação

As ondas morrem perto da costa e em água que não é mar aberto. A atenuação A(x, z) ∈ [0, 1] multiplica amplitudes e
deslocamentos de todas as componentes:

$$\mathbf P = \big(\mathbf x_0 + A\,\mathbf D,\; A\,\eta + A^2 c\big).$$

A é calculada nos cantos dos blocos (`ShoreField`): um canto encostado numa coluna que não é mar aberto (terra, gelo,
água corrente, água com algo em cima) é bloqueado; a **distância euclidiana exata** de cada canto ao bloqueado mais
próximo, pela transformada de Felzenszwalb e Huttenlocher (2012), dá o fator de costa smoothstep(0, largura, d).
Entre os cantos, interpolação bilinear. Ao longo de toda aresta de uma coluna de terra os dois cantos são
bloqueados: **a superfície encontra a costa exatamente no nível da água parada**, sem fresta e sem atravessar o bloco.

O fator de fundo limita a altura pela profundidade d (critério de quebra de McCowan, H ≤ γd, γ = 0,78): com
u = γd/Hs, f(u) = 1 − (1 − u)² até 1 (suave). A = costa × fundo.

O gradiente de A entra nas derivadas (∂(Aη)/∂x₀ = A ∂η/∂x₀ + η ∂A/∂x₀, e o mesmo para **D**), então normal,
jacobiano e a inversa continuam certos na faixa da costa.

**A costa também não dobra a superfície.** Com M = Σ Qᵢaᵢ (o maior deslocamento possível),

$$\|\nabla(A\mathbf D)\| \le A\,\|\nabla\mathbf D\| + |\mathbf D|\,|\nabla A| \le A L + M\,|\nabla A|,$$

e basta |∇A| < (1 − L·A)/M. Uma faixa de costa estreita, uma quina ou um degrau de profundidade nos blocos podem
violar isso (o fator de fundo muda de uma coluna para a outra). Um limite uniforme |∇A| ≤ (1 − L)/M seria seguro mas
pessimista demais: num mar de tempestade (L = 0,77, M = 5,7 m) a onda levaria 50 blocos para crescer. A condição
certa depende do valor: é folgada onde A é pequeno (rente à costa) e aperta só em mar aberto. Com a troca de variável

$$B = -\frac{\ln(1 - L A)}{L}, \qquad \frac{dB}{dA} = \frac{1}{1 - L A},$$

ela vira |∇B| < 1/M, **uniforme**. `ShoreField` transforma A em B, impõe |∇B| ≤ 1/(1,5·M) com um envelope de cone (o
maior campo abaixo de B com essa constante de Lipschitz, por duas passadas de chanfro; só abaixa valores, nunca cria
onda), e volta a A. Resultado: ‖∇(A**D**)‖ ≤ AL + (1 − AL)/1,5 < 1 em qualquer costa, com a superfície alterada só onde
a condição falhava. `ShoreFieldTest.shoreNeverFoldsTheSurface` (tempestade, crista 1, faixa de 3 blocos, ilhas de um
bloco a cada ~10 e degraus de profundidade de 25 para 1) confere A·L + M·|∇A| < 1 e J > 0 em 140 mil pontos;
`clampKeepsOpenSeaOpen` confere que numa costa reta a onda volta a 90% em menos de 60 blocos e a 100% longe dela. No
mapa de ilhas a atenuação média cai para 0,34 (as ilhas abrigam o mar), o preço da garantia.

> Aproximação assumida: a fase das componentes não muda com a profundidade (sem refração nem empinamento por
> diminuição da celeridade). Exigiria um campo de fases por traçado de raios, não determinístico de graça. Fica
> registrado como trabalho futuro.

### 5.2 A mesma conta nos dois lados

A regra "coluna de mar aberto" (`world.OceanColumns.isOpenSea`: fonte de água, metadado 0, ar em cima, no nível do
mar) e a transformada são as mesmas no servidor (API `OceanWaves.shore`) e no cliente (`OceanChunks`): física e
desenho concordam sobre os mesmos blocos.

## 6. A malha do cliente

### 6.1 Avaliação em grade por fasores tabelados

Num vértice (i, j) de uma grade de espaçamento s com origem **o**,

$$e^{i\theta} = e^{i\theta_{\mathbf o}}\cdot e^{i k_x i s}\cdot e^{i k_z j s}.$$

Os fatores Tx[i] = e^{ikₓis} e Tz[j] = e^{ik_zjs} são tabelados uma vez por espaçamento (não dependem do tempo nem do
lugar). O fator da origem também é tabelado por quadro: e^{iθ_o} = e^{i(−ωt+φ)}·e^{ikₓ·16·cx}·e^{ik_z·16·cz}, com uma
tabela por eixo de chunks (`GridEvaluator.beginFrame`). Resultado: **nenhuma trigonometria por chunk nem por
vértice**; por vértice e componente, um produto complexo e três somas (η, Dₓ, D_z). Isso importou: no Java 8 dos
clientes um par seno+cosseno custa ~290 ns aqui (56 mil pares = 16 ms), e só as tabelas por quadro tiraram 30% do
custo de uma cena.

As normais e o jacobiano (espuma) do caminho do desenho saem de diferenças centrais das posições já deslocadas,
numa grade com um vértice de borda: a normal é a da superfície que se desenha (sem detalhe que a malha não
representa, que cintilaria) e o custo cai pela metade em relação às 8 somas da forma analítica (que continua no
`GridEvaluator.evaluate`, para testes e para uma camada futura em GPU). `GridEvaluatorTest` confere as posições
contra as consultas pontuais (2·10⁻⁴ m) e as normais contra as analíticas (3%, ondas ≥ 16 s).

### 6.2 Nível de detalhe espectral (sem frestas, sem estalos)

Longe da câmera as ondas curtas somem aos poucos em vez de a malha ficar grossa e serrilhar. Num ponto a distância
d da câmera, o espaçamento que ele merece é s(d) = max(s_min, f·d) (padrão s_min = 1, f = 0,02) e o peso de uma
componente de comprimento λ é smoothstep((λ/s − 2,5)/2,5): some com menos de 2,5 amostras por comprimento de onda
(Nyquist é 2) e entra inteira com 5.

* O peso é função **só da posição no mundo e da câmera**, nunca do pedaço de malha a que o vértice pertence. Dentro
  de um chunk ele é interpolado bilinearmente dos quatro cantos; ao longo de uma aresta comum só contam os dois
  cantos dela, iguais para os dois vizinhos. Chunks vizinhos de resoluções diferentes dão o mesmo vértice na aresta
  (`neighboursOfDifferentResolutionShareTheirEdge`, 10⁻³ m): **sem fresta**.
* Cada chunk usa a maior potência de 2 menor ou igual ao s do seu ponto mais próximo da câmera: o que sobra do
  espectro sempre tem 2,5 amostras por comprimento de onda.
* **Junções em T.** O peso igual não basta: na aresta entre um chunk de espaçamento 1 e um de 4, os vértices do meio
  da aresta fina saem da superfície exata e a aresta grossa é a reta entre os cantos dela. Visto no jogo como uma
  linha escura fina no mar de tempestade (fresta de até 7,7 cm medida). O mesher põe esses vértices na reta da
  vizinha grossa (`OceanMesher.stitch`): `stitchingClosesTJunctions` mede fresta < 10⁻⁴ m (zero na prática) e a
  linha sumiu das capturas.
* Como o peso varia suavemente com a distância, mover a câmera não faz a superfície estalar entre níveis
  ("geomorphing" no espaço das frequências).
* Componentes de peso zero num chunk param o laço (estão ordenadas por λ): longe, menos componentes = mais barato.

### 6.3 A borda do mar

Com células de até 1 bloco cada célula fica numa coluna e sai conforme a coluna. Com células maiores, uma célula
toda de mar sai como um quad; uma que cobre costa é dividida nas colunas de mar, com os cantos interpolados dos
quatro vértices da célula (`OceanMesher`). `OceanMesherTest` confere que a área coberta é exatamente a das colunas de
mar em todos os espaçamentos.

## 7. O relógio

O tempo do mar é o **tempo total do mundo** (`World.getTotalWorldTime`, monotônico; `/time set` não o muda) em
segundos. O servidor vanilla manda-o a cada 20 ticks (`S03PacketTimeUpdate`, conferido em
`MinecraftServer.updateTimeLightAndEntities` do 1.7.10) e o cliente vanilla salta para ele. Saltos a cada segundo
fariam as ondas tremerem quando o servidor está abaixo de 20 TPS ou a latência varia.

`OceanClock` é uma reta re-ancorada a cada observação: a inclinação é a frequência estimada do servidor (média móvel
das observações espaçadas de mais de 0,2 s) mais uma correção que absorve o erro em 1,5 s. Inclinação sempre
positiva: o mar nunca anda para trás. Erro acima de 40 ticks: salta. `ClockAndFitTest`: servidor a 15 TPS com 0 a
120 ms de atraso variável por pacote: o relógio nunca recua, estima 15 ± 0,3 TPS, e erra menos de 3,5 ticks.

O atraso de meia latência das observações fica embutido; as poses dos navios do Expeditions chegam com o mesmo
atraso, então navio e onda andam juntos (ver ARQUITETURA.md, "Navios").

## 8. Corpos pequenos: plano de mínimos quadrados

Para barcos, itens e entidades: alturas em alguns pontos sob o corpo, plano η ≈ h + a·u + b·v por mínimos quadrados
(`SurfaceFit`), e o corpo puxado para (h, caturro = −atan b, balanço = atan a) por uma mola amortecida. É a técnica
do Atlas para servidores lentos (forças ponto a ponto ficam ruidosas a 20 Hz). Navios grandes usam as colunas do
casco (o `FloatingBody` do Expeditions) com o `HeightPatch`.

## Referências

* Hasselmann et al. (1973), *Measurements of wind-wave growth and swell decay during JONSWAP*.
* Bouws, Günther, Rosenthal e Vincent (1985), *Similarity of the wind wave spectrum in finite depth water* (TMA).
* Thompson e Vincent (1983), aproximação do fator de Kitaigorodskii.
* Donelan, Hamilton e Hui (1985); Banner (1990): espalhamento direcional.
* Horvath (2015), *Empirical directional wave spectra for computer graphics*, DigiPro.
  [PDF](https://cdn.shopify.com/s/files/1/0134/1131/9867/files/EmpiricalDirectionalWaveSpectra_DigiPro2015_Jun20_2015_Optimized.pdf)
* Tessendorf (2001/2004), *Simulating Ocean Water*.
  [PDF](https://people.computing.clemson.edu/~jtessen/reports/papers_files/coursenotes2004.pdf)
* Fournier e Reeves (1986), *A simple model of ocean waves*; Finch (2004), GPU Gems cap. 1.
  [link](https://developer.nvidia.com/gpugems/gpugems/part-i-natural-effects/chapter-1-effective-water-simulation-physical-models)
* Grapeshot Games e NVIDIA (GDC 2019), *Wakes, explosions and lighting: interactive water simulation in Atlas*.
  [PDF](https://gpuopen.com/gdc-presentations/2019/gdc-2019-agtd6-interactive-water-simulation-in-atlas.pdf)
* Crest Ocean System, consultas de deslocamento.
  [fonte](https://github.com/wave-harmonic/crest/blob/master/crest/Assets/Crest/Crest/Shaders/Resources/QueryDisplacements.compute)
* Felzenszwalb e Huttenlocher (2012), *Distance Transforms of Sampled Functions*.
* Fenton e McKee (1990), *On calculating the lengths of water waves*.
* Steele, Lea e Flood (2014), *Fast splittable pseudorandom number generators* (SplitMix64).
