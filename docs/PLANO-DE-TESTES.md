# Plano de testes e resultados

Três camadas: o núcleo (JUnit, sem Minecraft), o cliente de desenvolvimento sem tela (o harness), e o servidor TEOF
de verdade. O que só a última pode dizer está marcado **[a verificar]**.

## 1. Núcleo (JUnit): 48 testes, todos passando

`./gradlew :core:test` (ou `build`). Passam também no **Java 8** (Temurin 8u512) e no Java 21.

| Teste | O que prova |
|---|---|
| `PhysicsBasicsTest` | dispersão (ida e volta, os dois limites); pico do JONSWAP em ω_p; ordem de grandeza da lei de crescimento; teto do mar totalmente desenvolvido; marulho com o Hs pedido; TMA contínuo; espalhamento normalizado com inversa exata; integração contra a forma fechada de Pierson-Moskowitz |
| `SynthesisTest` | determinismo bit a bit; **resumo fixado** (o mesmo no Java 8 e no 21); Hs preservado (< 6%); componentes ordenadas, comprimentos no intervalo, L ≤ limite; escala de Froude exata; prefixo por λ |
| `OceanFieldTest` | derivadas, normal e velocidade contra diferenças finitas; inversa com resíduo < 10⁻⁶ m em ≤ 5 iterações; partida quente em 1 iteração; **nunca dobra** (J ≥ (1 − L)²); nível médio euleriano = repouso; crista/vale de Stokes; atenuação zero = água parada |
| `GridEvaluatorTest` | grade = consultas pontuais (2·10⁻⁴); caminho rápido = analítico (posições) e normais a 3%; tabelas do quadro = sem tabelas; vizinhos de mesmo espaçamento com vértices e normais iguais; vizinhos de espaçamentos diferentes com o mesmo vértice na aresta; longe sobram só as ondas longas |
| `HeightPatchTest` | retalho do casco < 2 cm rms da inversa exata; fora da grade = NaN |
| `ShoreFieldTest` | transformada de distância exata (contra força bruta); costa = zero em toda a aresta; crescimento monótono; limite de quebra pela profundidade; **a costa nunca dobra a superfície**; o limite não come o mar aberto; **chunks vizinhos concordam na borda** (a fresta vista no ambiente real); **região de mar aberto fundo é `uniform()` e vale exatamente 1** (mesmo com o par log/exp do limite de gradiente), com costa ou fundo raso não é; o caminho uniforme da grade dá a mesma superfície que o caminho por ponto; um chunk uniforme e o vizinho calculado concordam exatamente na borda; **o contrato de `uniform()`**: dentro da janela o caminho uniforme é o por ponto, e fora dela (onde `at` dá 0) a consulta do `OceanField` devolve a onda cheia do mar aberto e a amostra grava `attenuation = 0` |
| `OceanMesherTest` | área coberta = colunas de mar, em todos os espaçamentos; poucos quads longe; espaçamento cresce com a distância; **junções em T fechadas** |
| `ClockAndFitTest` | relógio com servidor a 15 TPS e atraso variável: monotônico, estima o TPS, erro < 3,5 ticks; salta em erro grande; plano de mínimos quadrados exato |

Medidas: `./gradlew :core:bench` (`Bench`).

### 1b. Cliente sem Minecraft (JUnit em `src/test`)

`./gradlew test` (o `build` roda). As classes de lógica do cliente não importam o Minecraft, para poderem ser testadas
com chunks sintéticos (`SyntheticSea`):

| Teste | O que prova |
|---|---|
| `RendererHealthTest` | **VIS-4**: com o renderizador falho a face de cima da água do terreno não é mais escondida (e só some com o mar ativo); a falha pede **uma** remontagem, consumida no tick do cliente (não no `catch`); a troca de mundo recomeça. **VIS-5**: um pedido de remontagem por tick, juntando ligar, região, nível e falha; mudança de nível só remonta com o mar ativo; um pedido feito sem `RenderGlobal` espera (não é consumido) e é atendido no primeiro tick em que dá; sem mundo os pedidos são esquecidos. |
| `VertexEmitterTest` | o laço de envio dos vértices (o mesmo que o `OceanRenderer` usa) sobre um gravador no lugar do `Tessellator`: **quadros de tamanhos diferentes** (40, 50, 10, 200, 3, 0 chunks...) emitem exatamente os chunks do quadro, qualquer que seja o comprimento do vetor de inícios (o defeito que desligava o mar: o laço ia até o fim do vetor e lia chunks que não existem; com o laço antigo reposto o teste lança `IndexOutOfBoundsException`); cada vértice chega com a cor, a luz, a normal, a posição e a textura do buffer, mesmo com as chamadas repetidas puladas; com luz própria nem a luz nem a normal são mandadas |
| `FrameReuseTest` | **PERF-7**: `ChunkSpans` cresce dobrando, nunca encolhe e a contagem é a do passe; `LodMemo` devolve o mesmo `SpectralLod` com a câmera parada e um novo, com os valores novos, quando qualquer parâmetro muda; `NearestFirst` ordena sem perder chunk |
| `StateMemoTest` | **PERF-8**: o estado do GL que se devolve é lido uma vez, de novo quando a situação muda (shaderpack, passagem de sombra, gráficos) e a cada 120 usos, nunca todo quadro |
| `ColumnRingTest` | o anel de chunks: criar uma vez, `peek` não cria, dois chunks vivos nunca dividem o lugar (com coordenadas grandes e negativas), `prune`, crescer sem perder, a marcação dos 5 × 5 |
| `ShoreWindowTest` | a janela da costa lida chunk a chunk dá **exatamente** a da leitura coluna a coluna de antes (com chunk não chegado e com região); a janela de mar aberto fundo é a sentinela `OPEN_SEA` e a conta completa dá 1 nela (dos dois lados do limite de profundidade); costa ou fundo raso não é pulada, e nunca devolve null |
| `ShoreSchedulerTest` | **PERF-3**: mar aberto fundo não gasta orçamento e sai todo num quadro; costas de verdade do mais perto para o mais longe e no máximo `orçamento` por quadro; **nenhum par de chunks desenhados discorda na aresta ou no canto comum em nenhum quadro**, nem durante uma costa nova construída em vários quadros (e o resultado final é o de refazer tudo do zero); o controle: trocar cada costa assim que sai, sem conferir a borda, **abre** fresta; chunk sem costa não é desenhado; troca forçada depois de 240 quadros de espera |

## 2. Cliente de desenvolvimento (harness)

Com o arquivo `run/client/oceanwaves-harness` (a primeira linha escolhe os casos; vazia = todos):

```bash
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient
```

(No contêiner foi preciso instalar o `xrandr`: o LWJGL 2 o executa para listar os modos de vídeo.)

O harness abre um mundo plano de oceano (água de y = 55 a 62, bioma oceano), ergue uma ilha de areia com praia em
rampa e um pilar, passa por 10 casos de 7 s (2 s de aquecimento), mede cada quadro e o custo do mar, tira uma captura
de cada caso (`run/client/screenshots/ondas-<caso>.png`) e escreve `run/client/oceanwaves-harness.txt`.

### Resultado (19/11/2026, Java 8u512, 854 × 480, distância 8 chunks, Mesa llvmpipe: **sem GPU**)

| Caso | Quadro médio | p99 | Malha + cor | Vértices | GL | Chunks | Quads |
|---|---|---|---|---|---|---|---|
| moderado-alto | 32,5 ms | 47,5 | 4,8 ms | 4,1 ms | 10,0 ms | 78 | 16.618 |
| moderado-rente | 34,1 | 52,2 | 5,5 | 3,5 | 8,9 | 75 | 15.850 |
| fresco-rente | 33,4 | 50,2 | 4,7 | 3,5 | 8,9 | 75 | 15.850 |
| tempestade-alto | 36,4 | 52,2 | 6,6 | 3,6 | 9,8 | 77 | 16.362 |
| tempestade-rente | 34,7 | 51,7 | 5,7 | 3,4 | 8,9 | 75 | 15.850 |
| costa | 35,6 | 81,2 | 5,3 | 3,5 | 9,7 | 81 | 15.987 |
| por-do-sol | 37,2 | 51,2 | 6,3 | 4,1 | 9,2 | 73 | 16.192 |
| calmo | 34,6 | 53,8 | 5,0 | 3,2 | 9,0 | 75 | 15.850 |
| de-cima | 45,8 | 64,5 | 6,4 | 5,6 | 11,2 | 98 | 20.722 |
| **vanilla-alto** (sem o mar do mod, mesma câmera de moderado-alto) | **22,8** | 29,8 | — | — | — | — | — |

* **Custo do mar neste contêiner: ~8 a 10 ms por quadro** com 8 chunks (moderado-alto contra vanilla-alto, e ±2 ms
  de ruído entre rodadas). "GL" é o `Tessellator.draw()`, que aqui inclui a rasterização em software; numa placa de
  vídeo deve ser uma fração **[a verificar]**. A parte de CPU (malha + cor + vértices) é ~8 a 9 ms.
* Evolução nesta sessão: 12,3 ms → 7,6 ms de sobrecarga ao passar o sombreamento dos cantos dos quads para os vértices
  da grade (3,5× menos contas).
* Sem erros de GL no log; nenhuma exceção do mod.

### O que as capturas mostram (verificado)

* A superfície ondulada vai até o horizonte e o perfil das cristas aparece contra o céu.
* **A face de cima da água vanilla some** onde o mod desenha (nenhum plano chato cortando os vales).
* **A costa**: o mar acalma no anel raso em volta da ilha e encosta na areia no nível da água parada, sem fresta.
* **Sem frestas entre resoluções** (havia uma linha escura na tempestade antes da costura das junções em T).
* Luz: o brilho do sol, o reflexo do céu mais forte nas faces viradas para longe (Fresnel), o tom turquesa nas cristas,
  e no pôr do sol o reflexo laranja nas faces viradas para o sol.

### 2b. Custo do caminho do renderizador fora do jogo (`./gradlew clientBench`)

`ClientPathBench` (em `src/test`) mede, na mesma execução, o que existia ("antes") e o código de agora ("depois"), sem
Minecraft: a cena é a do `PerfBench` da auditoria (distância 8, 78 chunks no leque de ±62° da câmera, moderado, 64
componentes). Container sem GPU (4 núcleos); melhor de várias séries; rodado no Java 8u514 e no Java 21 (a diferença
entre eles é pequena; `./gradlew clientBench` usa o Java 17 do Gradle, com os mesmos números).

O "antes" é sempre uma réplica do código antigo. O "depois" de **C** e **D** chama as classes de verdade do renderizador
(`ColumnRing`, `NearestFirst`, `ChunkSpans`, `VertexEmitter`, `LodMemo`), e não uma cópia: uma versão anterior deste
bench media uma réplica do laço de envio, e por isso não viu o defeito do vetor de inícios (o laço ia até o fim do
vetor, que só cresce, e lançava no meio do desenho). Agora o laço é o mesmo que o jogo roda e o `VertexEmitterTest` o
exercita com quadros de tamanhos diferentes.

| Medida | Antes | Depois |
|---|---|---|
| **A** avaliação da grade, 78 chunks em alto-mar (costa `uniform()`) | 2,62 a 2,66 ms (Java 8), 2,56 a 2,64 ms (21) | 2,25 ms (8), 2,20 a 2,22 ms (21): **−0,35 a −0,4 ms por quadro** |
| **A** o mesmo, com uma ilha a 40 blocos (66 de 78 chunks uniformes) | 2,63 a 2,81 ms (8), 2,57 ms (21) | 2,32 a 2,42 ms (8), 2,28 a 2,30 ms (21) |
| **B** um chunk chega e marca os 5 × 5: costas refeitas num quadro só | 4,6 a 5,3 ms (8), 5,4 a 6,0 ms (21), em mar aberto e junto à ilha | mar aberto: 25 janelas sem conta em 1 quadro, **0,07 a 0,12 ms**. Junto à ilha: 9 costas de verdade + 16 janelas de mar aberto, em 5 quadros (orçamento 2), **pior quadro 0,34 a 0,42 ms**, total 1,5 a 1,9 ms |
| **C1** lixo por quadro da consulta de chunks (213 na espiral, 190 visíveis), ordenação e vetor de inícios | 16,6 a 21,7 KB, 15 a 25 µs | **0 byte**, 10 µs |
| **C2** lixo por quadro do laço de envio dos vértices (`VertexEmitter`, 12.160 vértices, com e sem luz própria) | (não medido: o laço antigo era interno ao `submit`) | **0 byte** |
| **C3** o `SpectralLod` de cada passe (imutável, do núcleo: o desenho não o muda) | 80 bytes por passe, sempre | câmera parada: **0 byte** (`LodMemo`); câmera andando: **80 bytes por passe** (um objeto novo; a regra do "nada alocado" não se cumpre aqui sem mexer no núcleo, que esta tarefa não mexe) |
| **D** emissão de 61.440 vértices no Tessellator **emulado** (8 ints por vértice, sem o Angelica), com luz própria | 184.320 chamadas, 0,34 a 0,54 ms | 72.015 chamadas (−61%), 0,37 a 0,47 ms |
| **D** o mesmo, com shaderpack (cor e luz do chunk quase sempre iguais de um vértice ao outro) | 245.760 chamadas, 0,50 a 0,62 ms | 122.882 chamadas (−50%), 0,43 a 0,65 ms |
| **E** malha de 78 chunks, sem shaderpack (espaçamento 1) | — | 2,5 ms, 15.360 quads |
| **E** a mesma com shaderpack: espaçamento mínimo 2 (o novo padrão) | (era o 1: 2,5 ms, 15.360 quads) | 1,03 a 1,09 ms, 4.992 quads (−68% dos vértices que o Tessellator do Angelica recebe) |
| **E** a passagem de sombra (213 chunks, células ≥ 4, sem recorte): desligada por padrão | 1,2 ms de malha + 3.408 quads no formato do Iris, **por quadro** | 0 |

Os tempos de **D** estão dentro do ruído de uma rodada a outra (±0,1 ms): o que o corte prova é o número de chamadas;
o tempo por chamada é o do `Tessellator` real (ver abaixo). O ganho de **C3** com a câmera andando é nenhum, e está dito.

O que isto **não** mede: o `Tessellator` real (3,5 ms no vanilla e 9 a 11 ms no Angelica, GL por software, nos números
do `PerfBench` da auditoria): o ganho de **D** é no número de chamadas, e o tempo por chamada do Angelica é de 3 a 5
vezes o do emulado **[a verificar numa GPU e com o Angelica]**; a releitura em rodízio (precisa de `World` e dos
eventos de cor da água); nem os `glGet*` (de graça com o Angelica, que guarda esses estados; sem ele, hipótese). Nenhum
número de FPS aqui foi medido em jogo.

## 3. No jogo de verdade

Feito em 19/11/2026 com os arquivos reais do servidor e do cliente: [TESTES-AMBIENTE-REAL.md](TESTES-AMBIENTE-REAL.md).
Verificados: 2 (Angelica sem shader), 5 (Neo States, em parte), 6 (multijogador), 7 (servidor sem o mod: o mar local
foi o do mundo integrado). Não integra: 3 (BSL). Faltam: 1 (numa GPU), 4, 8 e 9.

Num cliente TEOF com placa de vídeo, servidor Crucible com o mod, cópia isolada (a regra dos outros projetos):

1. **Desempenho**: F3 mostra malha e envio por quadro; comparar FPS com `desenho.ligado=false`. Distâncias 8, 12 e 16.
2. **Angelica 2.2.4x sem shader**: a face de cima some (a montagem de chunks do Celeritas pergunta
   `shouldSideBeRendered`?); o mar aparece no passe translúcido; nada piscando.
3. **Angelica com BSL e Complementary**: o mar é desenhado pelo programa de água do pack? Normal e textura usadas?
   Água distante do Mirai (DH) continua? `ShaderPacks.inUse()` detecta o pack?
4. **Modo de compatibilidade** (sem Angelica, com DragonAPI): a face some e o mar aparece.
5. **Neo States**: a cor da água por bioma chega ao mar (via `colorMultiplier`); a textura cinza vira o detalhe;
   waterlogging na costa não abre buraco.
6. **Multijogador**: dois clientes lado a lado veem a mesma onda no mesmo lugar (o resumo de `/ondas info` é o
   mesmo; no F3, "relógio do servidor"); com o servidor a 15 TPS as ondas não tremem.
7. **Servidor sem o mod**: o cliente desenha o mar da própria configuração, com o relógio do pacote vanilla.
8. **`/ondas aqui`**: a altura que o servidor calcula bate com a superfície desenhada no lugar do jogador.
9. **Embaixo d'água**: a superfície vista de baixo (desenhada nos dois lados) e a névoa (ainda a vanilla: dentro da
   crista a câmera está "fora d'água", ver ARQUITETURA.md, próximas fases).
10. **Falha do renderizador (VIS-4)** **[a verificar]**: com `-Doceanwaves.failRenderAt=600` o desenho lança uma
    exceção no 600º quadro, com o `Tessellator` já aberto. Esperado: um erro no log ("O desenho do mar falhou..."), o
    jogo segue (o `Tessellator` é fechado no `finally`), o F3 mostra "[Ondas] desligadas por erro", e **em até um tick**
    a água do terreno (com a face de cima) volta nos chunks de mar, sem buraco; trocar de mundo religa o mar.
11. **Nível do mar medido depois da entrada (VIS-5)** **[a verificar]**: servidor com `Akashic/Spawn` sem o `@` (mede o
    nível, 31, depois de o cliente já ter montado chunks com o 63), entrar logo e ficar no spawn. Esperado: ao chegar a
    mensagem com o nível novo, o log mostra "Mar do servidor...", o terreno é remontado uma vez e o mar aparece em
    y = 30 em todos os chunks, sem água vanilla plana nos já montados e sem buracos.
12. **Costa em pedaços (PERF-3)** **[a verificar]**: andar ao longo da costa do `Akashic/Spawn` e teleportar para perto
    dela; olhar as bordas de chunk (F3+G) à procura de fresta, e o tempo de quadro à procura de pico (o F3 só mostra a média). O teste
    JUnit garante que nenhum par de chunks desenhados discorda na borda; falta ver no jogo a espera de chunks novos
    junto à costa (ficam sem mar por alguns quadros).
13. **Padrões com shaderpack (PERF-5)** **[a verificar]**: com BSL, o mar sai com espaçamento 2 e sem a sombra da água;
    comparar o visual de perto (as ondinhas finas vêm do pack) e, com `desenho.sombraDaAguaComShader=true`, a sombra.
14. **Releitura no tick (PERF-4)** **[a verificar]**: pôr e tirar um bloco no mar longe da câmera (pelo Celeritas, que
    não manda o aviso): o mar acompanha em até alguns segundos (o rodízio é de 2 chunks por tick; com 213 chunks, uns 10
    s no pior caso), e a luz de uma tocha perto da água em até ~2 s.
