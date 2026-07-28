# Orbis Atlas — arquitetura congelada

## Missão

Reduzir a complexidade de uma decisão de swing em moedas para uma pessoa iniciante, sem esconder risco e sem transformar um score técnico em promessa de rentabilidade.

## Pipeline

1. O usuário seleciona moeda-base, moeda de comparação, banca virtual e risco máximo.
2. `EcbFxRepository` obtém até 260 taxas diárias oficiais contra o euro.
3. O repositório alinha datas e calcula o par cruzado `quotePerEUR / basePerEUR`.
4. `MarketEngine` calcula médias de 20, 60 e 120 dias, RSI, volatilidade, extremos de 60 dias e distância estatística da média.
5. O motor classifica o cenário como tendência de alta, tendência de baixa, lateral, risco elevado ou dados insuficientes.
6. Apenas tendências ordenadas, não esticadas e com momentum compatível podem gerar compra ou venda.
7. O plano define entrada, invalidação, dois alvos, horizonte e tamanho teórico limitado pelo risco em reais.
8. `PaperTradeStore` registra apenas simulações e atualiza alvo/stop com a nova referência diária.
9. `DailyAnalysisWorker` atualiza o par selecionado uma vez por dia e pode emitir notificação.

## Regras invioláveis

- Sem integração de execução com corretoras.
- Sem opção binária, OTC sintético, martingale ou recuperação de perdas.
- Risco máximo limitado a 1% da banca virtual por plano.
- Dados desatualizados por mais de sete dias nunca geram entrada.
- Volatilidade anualizada superior a 28% bloqueia o plano conservador.
- Mercado lateral retorna `AGUARDAR`.
- Preço excessivamente afastado da média de 20 dias bloqueia perseguição do movimento.
- O aplicativo deixa explícito que as taxas do BCE são referências informativas, não cotações executáveis.

## Estratégia inicial

A RC1 utiliza somente continuação de tendência diária:

- compra: preço acima da média de 120 dias e médias 20 > 60 > 120;
- venda: preço abaixo da média de 120 dias e médias 20 < 60 < 120;
- RSI, momentum de cinco dias, volatilidade e z-score refinam a qualidade;
- pontuação mínima de 70/100;
- stop de aproximadamente 1,6 movimentos diários;
- primeiro alvo de 2R e segundo alvo de 3R;
- horizonte de cinco a vinte pregões.

## Interface

- **Hoje:** decisão principal, gráfico de 90 dias e três ações objetivas.
- **Plano:** entrada, stop, alvos, tamanho e regras imutáveis.
- **Simulador:** posições virtuais e resultado acumulado.
- **Aprender:** conceitos mínimos necessários para entender risco.
- **Ajustes:** banca virtual, risco e notificação.

## Critério de aceite

- Projeto Android compilado pelo GitHub Actions.
- Testes cobrindo mercado lateral, tendência acionável, RSI e volatilidade.
- APK independente com `applicationId=com.orbisgpt.atlas`.
- Nenhuma dependência de captura de tela ou API privada.
