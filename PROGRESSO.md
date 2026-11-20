# Progresso: ondas que levam barcos e navios

Coordenação do trabalho pedido em 2026-11-20: o barco (e a fragata do Expeditions) devem ser levados pelo mar do
OceanWaves, com queda de FPS de no máximo 10% em relação à linha de base. Cada entrega só entra aqui depois de
conferida (cumpre o pedido, foi executada com evidência, segue o estilo, e as partes críticas foram revisadas por quem
não as construiu).

## Decisões do usuário

* A linha de base de FPS é medida nos dois casos: sem o OceanWaves e com a versão atual.
* A fragata tem de flutuar no mar do OceanWaves de verdade: o movimento sai da física das ondas (hidrostática e
  dinâmica), sem animação feita para enganar o olho. Pode mexer no Expeditions (e no Kunftig) com liberdade.
* Barcos: os do Expeditions; o barco do Et Futurum foi o teste. As entidades em geral devem perceber o mar (nadar etc.).

## Etapas

| # | Etapa | Estado |
|---|---|---|
| 1 | Auditoria sem alterar código → `DIAGNOSTICO.md` | **feita**: 8 frentes (`docs/auditoria/`), 32 achados graves revistos por céticos que não os escreveram, nenhum refutado por inteiro, 25 correções aplicadas |
| 2 | Linha de base de FPS (medida pelo usuário: roteiro no `DIAGNOSTICO.md`) | **aguardando os números do usuário**; jar `c56f672` entregue |
| 3 | Priorização das correções | **feita**: `DIAGNOSTICO.md` §4 e as decisões do engenheiro-chefe |
| 4 | Correções, cada uma compilada, testada e revisada | pendente |
| 5 | Meta: barco/fragata seguem altura e inclinação da onda, FPS ≥ 90% da linha de base | pendente |

## Registro das entregas

* 2026-11-20, etapa 1. Auditoria com Opus (barco, desempenho, ondas, Expeditions), Sonnet (visual, Kunftig, Neo States)
  e Haiku (inventário). Conferida: cada achado grave tem evidência em arquivo e linha ou em bytecode, e foi revisado
  por outro agente (Sonnet). As medidas fora do jogo foram reexecutadas pelos revisores (`PerfBench`, sondas do
  Expeditions e do Kunftig). Nenhum FPS foi inventado.

## Pendências

* Etapa 2: os números de FPS (i) sem o OceanWaves e (ii) com o `c56f672`, medidos pelo usuário.
* Etapa 4 inteira (as correções).
* Pergunta ao usuário: a escala de Froude 0,5 do mar com o navio a 1 bloco = 1 m é intencional? (`DIAGNOSTICO.md` §5, 6)
