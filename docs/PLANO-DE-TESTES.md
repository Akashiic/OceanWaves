# Plano de testes e resultados

Três camadas: o núcleo (JUnit, sem Minecraft), o cliente de desenvolvimento sem tela (o harness), e o servidor TEOF
de verdade. O que só a última pode dizer está marcado **[a verificar]**.

## 1. Núcleo (JUnit): 41 testes, todos passando

`./gradlew :core:test` (ou `build`). Passam também no **Java 8** (Temurin 8u512) e no Java 21.

| Teste | O que prova |
|---|---|
| `PhysicsBasicsTest` | dispersão (ida e volta, os dois limites); pico do JONSWAP em ω_p; ordem de grandeza da lei de crescimento; teto do mar totalmente desenvolvido; marulho com o Hs pedido; TMA contínuo; espalhamento normalizado com inversa exata; integração contra a forma fechada de Pierson-Moskowitz |
| `SynthesisTest` | determinismo bit a bit; **resumo fixado** (o mesmo no Java 8 e no 21); Hs preservado (< 6%); componentes ordenadas, comprimentos no intervalo, L ≤ limite; escala de Froude exata; prefixo por λ |
| `OceanFieldTest` | derivadas, normal e velocidade contra diferenças finitas; inversa com resíduo < 10⁻⁶ m em ≤ 5 iterações; partida quente em 1 iteração; **nunca dobra** (J ≥ (1 − L)²); nível médio euleriano = repouso; crista/vale de Stokes; atenuação zero = água parada |
| `GridEvaluatorTest` | grade = consultas pontuais (2·10⁻⁴); caminho rápido = analítico (posições) e normais a 3%; tabelas do quadro = sem tabelas; vizinhos de mesmo espaçamento com vértices e normais iguais; vizinhos de espaçamentos diferentes com o mesmo vértice na aresta; longe sobram só as ondas longas |
| `HeightPatchTest` | retalho do casco < 2 cm rms da inversa exata; fora da grade = NaN |
| `ShoreFieldTest` | transformada de distância exata (contra força bruta); costa = zero em toda a aresta; crescimento monótono; limite de quebra pela profundidade; **a costa nunca dobra a superfície**; o limite não come o mar aberto; **chunks vizinhos concordam na borda** (a fresta vista no ambiente real) |
| `OceanMesherTest` | área coberta = colunas de mar, em todos os espaçamentos; poucos quads longe; espaçamento cresce com a distância; **junções em T fechadas** |
| `ClockAndFitTest` | relógio com servidor a 15 TPS e atraso variável: monotônico, estima o TPS, erro < 3,5 ticks; salta em erro grande; plano de mínimos quadrados exato |

Medidas: `./gradlew :core:bench` (`Bench`).

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
