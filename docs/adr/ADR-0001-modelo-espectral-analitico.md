# ADR-0001: soma espectral analítica de ondas trocoidais, e não FFT

**Estado:** aceita (19/11/2026).

## Contexto

O mar precisa ser (1) o mesmo no servidor (física dos navios, Java 21) e nos clientes (desenho, Java 8), (2) contínuo
em qualquer distância e sem repetição visível (o horizonte do Minecraft é largo e a câmera baixa), (3) consultável em
pontos isolados pela física, com derivadas, e (4) barato na CPU (sem garantia de GPU moderna: o pack tem um modo
OpenGL 3.1 e jogadores no Android).

## Opções

1. **FFT de Tessendorf** (o padrão da indústria; o Physics Mod V3 diz ter): milhares de componentes por custo fixo,
   mas o resultado é um ladrilho periódico (repetição visível; o Atlas usa 4 bandas para disfarçar), a consulta num
   ponto é uma interpolação no ladrilho, e na CPU uma FFT 256² com 5 campos por quadro passa do orçamento.
2. **Soma de senoides estética** (o Physics Mod legado): barato, sem base física.
3. **Soma analítica de N ondas trocoidais sorteadas de um espectro físico** (escolhida).

## Decisão

Opção 3: 64 componentes (8 a 256 configurável) sorteadas por amostragem estratificada do JONSWAP direcional, cada uma
uma onda de Gerstner. Sem repetição (frequências incomensuráveis), contínua por definição, determinística por
construção, com derivadas fechadas, e a mesma função na consulta pontual e na malha. O custo da malha é controlado
pelas tabelas de fasores (nenhuma trigonometria por vértice nem por chunk) e pelo LOD espectral (longe, menos
componentes).

## Consequências

* Menos detalhe fino que uma FFT: o detalhe abaixo da malha vem da textura animada da água (e, com shaderpack, do
  pack). Uma camada em GPU poderia somar mais componentes só para as normais.
* Com N pequeno o mar pode parecer "regular"; a amostragem estratificada com tremor, as direções sorteadas da
  distribuição e o marulho de outra direção quebram isso. **[avaliar em jogo]**
* A garantia de não dobrar (Lipschitz < 1) só é possível porque a soma é analítica: o limite é uma conta sobre as
  componentes.
