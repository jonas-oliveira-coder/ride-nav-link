# Changelog

## [2.4.2] - 2026-11-23

### Inteligência de Rotatórias (7 posições angulares do LCD Bajaj)
- **Mapeamento angular de 7 pontas (45° em 45°)**: o parser de bitmap do Google Maps (`GoogleMapsIconParser`) agora lê o ângulo real da seta de saída ao redor do anel da rotatória e converte diretamente para a posição `1..7` do painel LCD da moto (ex.: `1` = 135°/4h30 fechada à direita, `2` = 90°/3h direita, `3` = 45°/1h30 leve à direita, `4` = 0°/12h em frente, `5` = 315°/10h30 leve à esquerda, `6` = 270°/9h esquerda, `7` = 225°/7h30 retorno/fechada à esquerda), com espelhamento automático para mão esquerda.
- **Prioridade visual independente de idioma**: a posição angular extraída do ícone da notificação tem prioridade sobre o número ordinal em texto, garantindo precisão em qualquer idioma do Google Maps; fallback por texto ampliado (PT, EN, ES, FR, DE) e alinhado ao disco de 7 posições (`1ª→2`, `2ª/em frente→4`, `3ª→6`, `4ª+/retorno→7`; padrão seguro `4`).
- **7 ícones estilo Google Maps no HUD do app**: adicionados vetores dedicados (`ic_arrow_roundabout_pos1` a `ic_arrow_roundabout_pos7`) e legendas traduzíveis (`roundabout_dir_1`..`roundabout_dir_7`) em `values/strings.xml` (EN) e `values-pt-rBR/strings.xml` (PT-BR), prontos para o Weblate.
- **Sentido de giro da rotatória (`'N'` vs `'U'`)**: mantido `'N'` (`ARROW_ROUNDABOUT_LEFT_SOLID`) para giro anti-horário (Brasil / mão direita) e `'U'` (`ARROW_ROUNDABOUT_RIGHT_SOLID`) para giro horário (mão esquerda), com documentação corrigida em `BajajProtocol.kt`.

### Corrigido (Google Maps & Navegação TBT)
- **Falso positivo de rotatória em curvas simples**: exigência de cobertura de `>= 7` de 8 octantes no anel do bitmap e bloqueio para que um ícone detectado como rotatória nunca sobrescreva uma instrução explícita de curva no texto (ex.: *"Vire à esquerda"*).
- **Curvas de 90° vs. curvas fechadas e retornos**: classificação de setas baseada na altura média da ponta da seta (`tipAvgNormY`) e detecção dedicada de retorno em U (`detectUTurnFromMask`).
- **Ícones com fundo opaco**: fallback por contraste de luminância para extrair o formato da seta quando o ícone da notificação não tem recorte alfa transparente.
- **Chegada ao destino**: detecção da silhueta de pino de destino no bitmap (`isDestinationPinShape`), ampliação das frases de chegada em português (*"O destino estará..."*, *"destino fica"*, *"chegou"*) e priorização do texto explícito de chegada.

## [2.4] (versionCode 6)
- Animação de barras de bateria carregando no painel.
- Revisão do parser de notificações e ícones de rotatória do Google Maps.
- Suporte a internacionalização (Inglês padrão + Português BR).
- Card de apoio ao projeto em Material Design 3 (PIX e Ko-fi).
