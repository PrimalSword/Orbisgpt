# Orbis Decision Terminal

Aplicativo Android de apoio à decisão, veto e gestão de risco para validação exclusivamente em conta demo.

O Terminal não executa ordens, não promete rentabilidade e não utiliza martingale. Ele foi concebido para operar menos, rejeitar entradas ruins e produzir evidência auditável sobre o que realmente funciona.

## O que esta versão contém

- Mercado aberto e OTC com amostras separadas.
- Memória multi-timeframe: contexto 15m, estrutura 5m e entrada 1m.
- Rastreamento temporal de candles.
- Estrutura de mercado e classificação de regime.
- Playbooks de pullback, rompimento, retorno à média e reversão.
- Etapas de setup: contexto, formação, armado, válido, perdido e invalidado.
- Qualidade da entrada, atraso, extensão e espaço até obstáculo.
- Probabilidade condicionada, intervalo conservador, break-even e expectativa.
- Motor de veto superior ao motor de sinal.
- Política de risco da sessão sem martingale.
- Modos Observador, Assistido e Cego.
- Overlay arrastável e recolhível.
- Diário local SQLite, comparação humano × sistema e exportação CSV.
- Métricas de profit factor, drawdown, aderência e perdas bloqueadas.

## Como obter o APK

Abra **Actions**, escolha a execução verde mais recente e baixe o artifact:

`Orbis-Decision-Terminal-v1.0.0-RC1-debug`

Extraia o ZIP e instale o APK. O `applicationId` é próprio, portanto ele pode coexistir com o Orbis Trade AI.

## Protocolo multi-timeframe

Para o mesmo ativo e modalidade:

1. Selecione **Contexto 15m** no aplicativo e deixe o gráfico em 15 minutos até a memória aparecer como `OK`.
2. Selecione **Estrutura 5m** e altere o gráfico para 5 minutos.
3. Selecione **Entrada 1m** e altere o gráfico para 1 minuto.
4. Somente candidatos detectados no timeframe de entrada são gravados no diário.

A memória expira automaticamente. Contexto contrário, ausente ou vencido impede autorização operacional.

## Protocolo de validação

- Use apenas conta demo.
- Não altere parâmetros durante a janela de teste.
- Registre a escolha humana antes de revelar o sistema no modo Cego.
- Marque cada candidato como `WIN`, `LOSS`, `DRAW` ou `INVALIDATED`.
- Informe se seguiu o plano.
- Exporte o CSV diariamente.
- Não interprete amostras pequenas como vantagem comprovada.

## Desenvolvimento

A arquitetura está congelada em [`ARCHITECTURE.md`](ARCHITECTURE.md). O GitHub Actions executa testes unitários antes de gerar o APK.
