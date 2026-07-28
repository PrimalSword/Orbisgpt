# Orbis Decision Terminal — arquitetura congelada

## Missão

Terminal Android de apoio à decisão para operação manual e validação em conta demo. O produto não executa ordens, não promete lucro e não utiliza martingale.

## Pipeline obrigatório

1. Captura e validação da tela atual.
2. Rastreamento temporal de candles.
3. Memória de contexto em 15m, estrutura em 5m e entrada em 1m.
4. Estrutura de mercado e regime.
5. Seleção de playbook compatível.
6. Qualidade da entrada e temporização.
7. Probabilidade condicionada, break-even e expectativa.
8. Motor de veto.
9. Política de risco da sessão.
10. Alerta explicável.
11. Diário auditável.
12. Comparação humano × sistema.

## Playbooks fechados

- Continuação por pullback.
- Rompimento com expansão.
- Retorno à média em lateralidade.
- Reversão em extremo.

## Etapas do setup

`CONTEXTO → FORMANDO → ARMADO → VÁLIDO`, ou `PERDIDO/INVALIDADO`.

## Modos

- **Observador:** registra candidatos sem recomendar execução.
- **Assistido:** mostra decisão, veto e risco antes da escolha humana.
- **Cego:** registra a escolha humana antes da leitura do sistema.

## Regras invioláveis

- Mercado aberto e OTC mantêm amostras separadas.
- O risco da sessão prevalece sobre qualquer sinal.
- Sem martingale ou aumento após perda.
- Tela não validada nunca gera decisão operacional.
- Score técnico não é apresentado como probabilidade.
- A primeira release não será reestruturada; somente defeitos objetivos poderão ser corrigidos.

## Critérios de aceite

- APK Android produzido pelo GitHub Actions.
- Instalação paralela ao Orbis Trade AI.
- Testes dos motores de estrutura, probabilidade, veto e risco.
- CSV completo de auditoria.
- Nenhuma tela alheia ao gráfico pode produzir operação permitida.
