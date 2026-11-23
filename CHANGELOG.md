# Changelog

## [Unreleased] - 2026-11-23

### Corrigido (Google Maps & Navegação TBT)
- **Detecção de rotatórias por ícone (`GoogleMapsIconParser`)**: substituída a checagem de anel central fixo por busca em grade ao redor do centro da imagem, garantindo reconhecimento correto mesmo quando a seta de saída lateral (ex.: 1ª saída) ou a haste inferior deslocam o centro do bitmap.
- **Mapeamento de rotatória para o padrão Brasil/mão direita (`'U'`)**: corrigida a inversão entre `'N'` (mão esquerda) e `'U'` (mão direita / anti-horário) no parser de ícones, no parser de texto do Google Maps e no mapeamento OSM (`MainActivity`).
- **Curvas de 90° vs. curvas fechadas e retornos**: corrigida a classificação de setas no bitmap usando a altura média da ponta da seta (`tipAvgNormY`) e adicionada detecção dedicada para retorno em U (`detectUTurnFromMask`), evitando que curvas normais ou saídas de rotatória sejam exibidas como curva fechada/retorno no painel.
- **Ícones com fundo opaco**: adicionado fallback por contraste de luminância para extrair o formato da seta quando o ícone da notificação não possui recorte alfa transparente.
- **Chegada ao destino**: adicionada detecção da silhueta de pino de destino no bitmap (`isDestinationPinShape`), ampliação das frases de chegada em português (ex.: *"O destino estará..."*, *"destino fica"*, *"chegou"*) e priorização do texto explícito de chegada/rotatória/retorno sobre classificações ambíguas de bitmap.
- **Número da saída da rotatória**: priorização da saída explícita no texto (`1ª` a `7ª saída`), mantendo a saída inferida pelo ícone como fallback quando o texto omite o número.

## [2.4] (versionCode 6)
- Animação de barras de bateria carregando no painel.
- Revisão do parser de notificações e ícones de rotatória do Google Maps.
- Suporte a internacionalização (Inglês padrão + Português BR).
- Card de apoio ao projeto em Material Design 3 (PIX e Ko-fi).
