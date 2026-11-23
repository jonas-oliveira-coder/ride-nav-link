# Changelog

## [2.4.2] - 2026-11-23

### 🇺🇸 English

#### Roundabout Intelligence (7-Spoke Bajaj LCD Wheel)
- **7-spoke angular mapping (45° steps)**: `GoogleMapsIconParser` now reads the real geometric exit angle from the Google Maps notification icon and maps it directly to the Bajaj LCD's 7 radial arrows (`1` = 135°/4:30 sharp right, `2` = 90°/3:00 right, `3` = 45°/1:30 slight right, `4` = 0°/12:00 straight, `5` = 315°/10:30 slight left, `6` = 270°/9:00 left, `7` = 225°/7:30 U-turn/sharp left), automatically mirrored for left-hand traffic.
- **Language-independent visual priority**: The geometric exit angle extracted from the icon takes priority over ordinal exit numbers in text, working across any Google Maps language; expanded multilingual text fallback (EN, PT, ES, FR, DE) mapped to the 7-spoke wheel (`1st→2`, `2nd/straight→4`, `3rd→6`, `4th+/U-turn→7`; safe default `4`).
- **7 Google Maps–style HUD icons & i18n**: Added dedicated vector icons (`ic_arrow_roundabout_pos1`..`pos7`) and translatable exit direction labels (`roundabout_dir_1`..`7`) in `values/strings.xml` (EN) and `values-pt-rBR/strings.xml` (PT-BR), ready for Weblate.
- **Roundabout rotation direction (`'N'` vs `'U'`)**: Preserved `'N'` (`ARROW_ROUNDABOUT_LEFT_SOLID`) for counter-clockwise (right-hand traffic / Brazil) and `'U'` (`ARROW_ROUNDABOUT_RIGHT_SOLID`) for clockwise (left-hand traffic), with corrected protocol comments in `BajajProtocol.kt`.

#### Fixed (Google Maps & TBT Navigation)
- **False roundabout on standard turns**: Required `>= 7` of 8 ring octants in bitmap detection and prevented a roundabout icon heuristic from overriding explicit turn instructions (e.g., *"Turn left"*).
- **90° turns vs. sharp turns & U-turns**: Improved arrow classification using arrowhead tip height (`tipAvgNormY`) and added dedicated U-turn detection (`detectUTurnFromMask`).
- **Opaque-background notification icons**: Added luminance-contrast fallback to extract arrow geometry when the notification icon lacks a transparent alpha cutout.
- **Destination arrival detection**: Added destination pin silhouette detection (`isDestinationPinShape`), expanded localized arrival phrases, and prioritized explicit arrival text over ambiguous bitmaps.

---

### 🇧🇷 Português (BR)

#### Inteligência de Rotatórias (7 posições angulares do LCD Bajaj)
- **Mapeamento angular de 7 pontas (45° em 45°)**: o `GoogleMapsIconParser` agora lê o ângulo real da seta de saída no ícone da notificação do Google Maps e converte diretamente para a posição `1..7` do painel LCD da moto (`1` = 135°/4h30 fechada à direita, `2` = 90°/3h direita, `3` = 45°/1h30 leve à direita, `4` = 0°/12h em frente, `5` = 315°/10h30 leve à esquerda, `6` = 270°/9h esquerda, `7` = 225°/7h30 retorno/fechada à esquerda), com espelhamento automático para mão esquerda.
- **Prioridade visual independente de idioma**: a posição angular extraída do ícone tem prioridade sobre o número ordinal em texto, funcionando em qualquer idioma do Google Maps; fallback por texto ampliado (PT, EN, ES, FR, DE) alinhado ao disco de 7 posições (`1ª→2`, `2ª/em frente→4`, `3ª→6`, `4ª+/retorno→7`; padrão seguro `4`).
- **7 ícones estilo Google Maps no HUD + Multilíngue**: adicionados vetores dedicados (`ic_arrow_roundabout_pos1`..`pos7`) e legendas traduzíveis (`roundabout_dir_1`..`7`) em `values/strings.xml` (EN) e `values-pt-rBR/strings.xml` (PT-BR), prontos para o Weblate.
- **Sentido de giro da rotatória (`'N'` vs `'U'`)**: mantido `'N'` (`ARROW_ROUNDABOUT_LEFT_SOLID`) para giro anti-horário (Brasil / mão direita) e `'U'` (`ARROW_ROUNDABOUT_RIGHT_SOLID`) para giro horário (mão esquerda), com documentação corrigida em `BajajProtocol.kt`.

#### Corrigido (Google Maps & Navegação TBT)
- **Falso positivo de rotatória em curvas simples**: exigência de cobertura de `>= 7` de 8 octantes no anel do bitmap e bloqueio para que um ícone detectado como rotatória nunca sobrescreva uma instrução explícita de curva no texto (ex.: *"Vire à esquerda"*).
- **Curvas de 90° vs. curvas fechadas e retornos**: classificação de setas baseada na altura média da ponta da seta (`tipAvgNormY`) e detecção dedicada de retorno em U (`detectUTurnFromMask`).
- **Ícones com fundo opaco**: fallback por contraste de luminância para extrair o formato da seta quando o ícone da notificação não tem recorte alfa transparente.
- **Chegada ao destino**: detecção da silhueta de pino de destino no bitmap (`isDestinationPinShape`), ampliação das frases de chegada em português (*"O destino estará..."*, *"destino fica"*, *"chegou"*) e priorização do texto explícito de chegada.

## [2.4] (versionCode 6)

### 🇺🇸 English
- Animated battery charging bar indicator on the cluster.
- Improved Google Maps notification & roundabout icon parsing.
- Internationalization support (English default + Brazilian Portuguese, Weblate ready).
- Material Design 3 project support card (PIX & Ko-fi).

### 🇧🇷 Português (BR)
- Animação de barras de bateria carregando no painel.
- Revisão do parser de notificações e ícones de rotatória do Google Maps.
- Suporte a internacionalização (Inglês padrão + Português BR).
- Card de apoio ao projeto em Material Design 3 (PIX e Ko-fi).
