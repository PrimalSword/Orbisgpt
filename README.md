# Orbis Atlas

Aplicativo Android de apoio a decisões de **swing em moedas**, desenhado para quem ainda não sabe interpretar gráficos.

O usuário escolhe o par de moedas e informa uma banca virtual. O Atlas baixa séries diárias oficiais do Banco Central Europeu, calcula tendência, momentum, volatilidade e distância da média, e responde em linguagem simples:

- **COMPRAR** a moeda-base;
- **VENDER** a moeda-base; ou
- **AGUARDAR**.

Quando existe um plano, o aplicativo informa faixa de entrada, stop, dois alvos, prazo estimado, risco máximo em reais e tamanho teórico da posição. A versão RC1 funciona somente em simulação e não envia ordens a corretoras.

## Princípios do produto

- Sem overlay, captura de tela ou dependência do layout de uma corretora.
- Sem opções binárias, martingale, scalping ou promessa de lucro rápido.
- Dados diários, com horizonte de 5 a 20 pregões.
- Risco máximo configurável entre 0,1% e 1% da banca virtual.
- Preferência explícita por `AGUARDAR` quando o cenário estiver misto, esticado, desatualizado ou excessivamente volátil.
- Diário de operações simuladas com fechamento por alvo, stop ou decisão manual.
- Notificação diária opcional.

## Fonte de dados

O provedor primário é o **ECB Data Portal**, usando séries `EXR` de taxas de referência diárias. O Atlas calcula pares cruzados localmente a partir das taxas publicadas contra o euro.

As taxas do BCE têm finalidade informativa e não representam necessariamente o preço executável de uma corretora. Por isso, esta versão não se apresenta como plataforma de execução em tempo real.

## APK

Abra **Actions**, escolha a execução verde mais recente e baixe:

`Orbis-Atlas-v1.0.0-RC1-debug`

O `applicationId` é `com.orbisgpt.atlas`, permitindo instalação paralela às versões anteriores do Orbis.

## Uso

1. Escolha a moeda-base e a moeda de comparação.
2. Defina uma banca virtual e o risco máximo por plano.
3. Toque em **Atualizar análise**.
4. Leia somente a decisão principal e as três instruções exibidas.
5. Quando houver plano, use **Simular este plano** antes de considerar qualquer aplicação prática.
6. Reavalie após a próxima publicação diária.

## Desenvolvimento

O módulo ativo é `:coach`. O GitHub Actions executa testes unitários e só depois gera o APK.

A arquitetura está documentada em [`ATLAS_ARCHITECTURE.md`](ATLAS_ARCHITECTURE.md).
