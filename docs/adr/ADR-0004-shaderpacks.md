# ADR-0004: o mar passa pelo programa de água do shaderpack (Iris do Angelica)

**Estado:** aceita (19/11/2026), vista no cliente TEOF 6.3 com o BSL 8.2.09 e o Complementary Unbound r5.9.3.

## Contexto

Com shaderpack, o mar saía em placas claras, sem a luz, os reflexos e a refração do pack (teste no ambiente real).
Lido no código do Angelica 2.2.43 (decompilado com o CFR):

1. **O Angelica reescreve `RenderGlobal.sortAndRender` inteiro** (`@Overwrite` em `celeritas.terrain.MixinRenderGlobal`)
   e termina com `pipeline.setPhase(WorldRenderingPhase.NONE)`. O mixin do mar desenhava no `RETURN`, ou seja, já
   na fase `NONE`: o pipeline escolhia o programa padrão (`RenderCondition.DEFAULT`), não o `gbuffers_water`.
2. **O programa é escolhido pela fase e pelo que está ligado** (`DeferredWorldRenderingPipeline.matchPass`): a fase
   `TERRAIN_TRANSLUCENT` (ou um override dela, `GbufferPrograms.pushOverridePhase`) leva ao `gbuffers_water`, com a
   variante de textura + mapa de luz quando os dois estão ligados.
3. **O pack reconhece a água pelo `mc_Entity`**, o atributo de vértice 11. Na montagem de chunks ele vem do
   `block.properties`; num desenho imediato pelo `Tessellator` ele é o valor corrente do atributo, posto por
   `BlockMaterialAttribute.set(bloco, meta)`. É exatamente como o Angelica desenha o bloco caindo
   (`MixinRenderFallingBlock`: override de fase + material). O `Iris.setShaderMaterialOverride` (o caminho do Neo
   States) só vale na montagem de chunks.
4. **O frustum vanilla não serve com shaderpack**: o recorte pelo `ClippingHelperImpl` descartava quase todos os
   chunks (9 a 17 de 212).
5. **A passagem de sombra também chama o passe 1** (`ShadowRenderer`, com `isRenderingShadowPass()` verdadeiro).

## Decisão

`IrisBridge` e `ShaderPacks`, por reflexão (sem o Angelica nada muda):

* em volta do desenho do mar com shaderpack: `pushOverridePhase(TERRAIN_TRANSLUCENT)` e
  `BlockMaterialAttribute.set(Blocks.water, 0)`; no `finally`, `reset` e `popOverridePhase` (o Iris confere o
  equilíbrio das fases);
* a cor do vértice é só a da água (bioma × tom da textura) e o mapa de luz fica ligado: o pack ilumina;
* recorte por **cone de visão** próprio (`ViewCone`) com shaderpack;
* na passagem de sombra o mar é desenhado também (o pack ganha a superfície da água no `shadowtex`: sombra colorida e
  cáusticas), com uma **malha grossa própria** (células de 4 blocos ou mais) e sem recorte da câmera
  (`desenho.sombraDaAgua`). Tentado antes: fazer a malha cheia na sombra e reaproveitá-la na passagem principal. Não
  compensa: o caro com shaderpack é enviar cada vértice pelo `Tessellator` do Angelica (que monta o formato estendido
  do Iris), não calcular a malha, e a sombra não precisa da malha cheia.

## Consequências

* **Custo**: com shaderpack, enviar um vértice pelo `Tessellator` do Angelica sai muito mais caro que sem (medido aqui,
  em GL por software e com a CPU disputada: ~160 a 230 ms por ~18 mil quads, contra ~10 ms por ~16 mil sem shader).
  Em placa de vídeo **[a verificar]**. Se pesar, o caminho é um VBO próprio no formato do Iris, ou células maiores
  perto da câmera com shaderpack (o pack já põe o detalhe fino por pixel).

* Vale para qualquer pack que siga o contrato do Iris (`gbuffers_water` + `mc_Entity` do `block.properties`): visto
  no BSL e no Complementary. **[a verificar]** com outros packs do Iris 1.7.10 e com o Angelica de outras versões (se
  `BlockMaterialAttribute` sumir, cai para o `setShaderMaterialOverride`).
* O pack pode pôr as próprias ondinhas por cima (o `gbuffers_water` do BSL desloca vértices de água): somam-se às do
  mod como detalhe fino.
* Sem shaderpack nada mudou: a luz continua feita pelo mod (`SurfaceShader`).
