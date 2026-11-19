# ADR-0003: a superfície é desenhada na CPU pelo Tessellator, no fim do passe translúcido, escondendo a face vanilla

**Estado:** aceita (19/11/2026); a camada em GPU fica como opcional futura.

## Contexto

No 1.7.10 a água é feita de blocos; a face de cima de cada fonte é montada no chunk (estática) a 8/9 do bloco. Para
uma superfície que se mexe é preciso (a) esconder essa face onde há ondas e (b) desenhar outra a cada quadro. O pack
roda com o Angelica (Celeritas + Iris, OpenGL 3.3 core, `GLStateManager`), às vezes sem ele (modo de compatibilidade,
DragonAPI), com shaderpacks (BSL, Complementary) e com o Mirai (água distante).

## Decisão

* **Esconder**: `BlockLiquid.shouldSideBeRendered` no lado 1 devolve falso para as colunas de mar aberto (a mesma
  regra do servidor), só com o desenho ligado. É a pergunta que `renderBlockLiquid` faz no vanilla e no Angelica
  (que monta os chunks pelo caminho vanilla, em threads: a regra só lê o `IBlockAccess` recebido e campos voláteis).
* **Desenhar** no fim de `RenderGlobal.sortAndRender` passe 1 (onde a água vanilla seria, antes das entidades
  translúcidas, das partículas e da chuva; o Mirai pendura a água distante no mesmo método), pelo `Tessellator`, com
  normal, cor, textura e (com shaderpack) o mapa de luz. Profundidade escrita, chunks do mais perto para o mais longe.
* **Malha por chunk**, alinhada à grade do mundo (sem "nadar" quando a câmera anda), cobrindo exatamente as colunas
  de mar dos chunks carregados (sem a costura do Physics Mod com o Distant Horizons).

## Por que não um shader próprio

Um programa GLSL próprio no meio do mundo brigaria com o Iris quando há shaderpack (que espera os programas dele e
escreve vários alvos) e não roda no modo de compatibilidade. O `Tessellator` é o caminho que o Angelica garante e que
todo shaderpack recebe.

## Consequências

* Custo de CPU por quadro (medido: ~8 ms de CPU com 8 chunks num contêiner fraco); cortes planejados: threads,
  LOD temporal, VBO, camada GPU (ARQUITETURA.md).
* Sem iluminação por pixel sem shaderpack: a luz é por vértice (com relevo exagerado e a textura animada para o
  detalhe).
* **[a verificar]** com o Angelica real: que a face some na montagem do Celeritas; que o Iris aplica o programa de
  água do pack ao que é desenhado nesse ponto; que a ordem com o passe adiado dos shaders é a esperada.
