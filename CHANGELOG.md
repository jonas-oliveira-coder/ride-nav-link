# Changelog

## [2.4.4] - 2026-11-27

### 🇺🇸 English
- **Overhauled Google Maps icon recognition (`IconShapeClassifier`)**:
  - Dual-tone foreground extraction (`primaryMask` + `secondaryMask`) separates active routes from faded secondary branches for reliable Fork, Ramp, Keep, and Merge detection.
  - Hybrid 32×32 canonical template matching + arrow geometry descriptors accurately distinguish Straight, Slight (45°), Turn (90°), Sharp (135°), and U-Turn (180°) maneuvers while keeping 7-spoke roundabout detection intact.
  - 2D connected-component detection for composite Destination icons (Pin + Road left/right and centered Pin).
- **Improved destination arrival & startup navigation**:
  - Removed the false `1 km` straight-arrow fallback when Google Maps starts without a distance yet.
  - Displays a straight arrow with synced ETD while approaching the final destination (`> 40 m`), then automatically switches to the blinking arrival icon (`0 m`) for 25–35 s upon arrival or when the notification dismisses.
- **Calls & Bajaj LCD text fixes**:
  - Added US-ASCII transliteration (`á → a`, `ç → c`, `ã → a`, etc.) for calls, missed calls, alerts, and media so accented characters never render blank on the LCD.
  - Fixed stale phone numbers leaking into subsequent calls on Android 12+ and improved caller name extraction for WhatsApp and Dialer notifications.
- **F-Droid security & automated tests**:
  - Added `distributionSha256Sum` to `gradle-wrapper.properties` (resolving `insecure-gradlew`).
  - Added 21 JVM unit tests covering icon classification, ASCII transliteration, startup filtering, and destination arrival.

### 🇧🇷 Português (BR)
- **Nova engine de reconhecimento de ícones do Google Maps (`IconShapeClassifier`)**:
  - Extração Dual-Tone (`primaryMask` + `secondaryMask`) separa a rota ativa da via secundária esmaecida para detectar Bifurcação, Alça, Manter-se na faixa e Convergência.
  - Classificador híbrido (templates canônicos 32×32 + geometria da seta) diferencia com precisão Reto, Leve (45°), Curva (90°), Acentuada (135°) e Retorno (180°), mantendo a rotatória de 7 pontas intacta.
  - Detecção por componentes conectados 2D para ícones compostos de destino (Pino + Estrada à esquerda/direita e Pino central).
- **Chegada ao destino e início de rota corrigidos**:
  - Removido o bug de `1 km` linha reta ao iniciar a navegação antes de o Google Maps enviar a primeira distância real.
  - Exibe seta reta com ETD sincronizado na aproximação final (`> 40 m`) e muda automaticamente para o ícone de chegada piscando (`0 m`) por 25–35 s ao chegar ou quando a notificação some.
- **Correções em ligações e acentos no LCD Bajaj**:
  - Adicionada transliteração ASCII (`á → a`, `ç → c`, `ã → a`, etc.) em ligações, chamadas perdidas, alertas e mídia para não ficar espaço em branco no painel.
  - Corrigido reaproveitamento de número da ligação anterior no Android 12+ e melhorada a extração do nome real em ligações normais e WhatsApp.
- **F-Droid e testes automatizados**:
  - Adicionado `distributionSha256Sum` em `gradle-wrapper.properties` (removendo o aviso `insecure-gradlew`).
  - Adicionados 21 testes unitários cobrindo classificação de ícones, acentos, início de rota e chegada.

## [2.4.3] - 2026-11-23

### 🇺🇸 English
- **Reliable destination arrival detection**:
  - Arrival phrase checks now inspect all raw notification fields (`title`, `text`, `bigText`, `subText`, `textLines`) without discarding fields containing ETA/duration (e.g., `"0 min"`).
  - Automatic arrival detection on the final meters (`<= 25 m` remaining or `eta == 0` with `<= 40 m`) even when Google Maps shows only the destination name/address without an explicit *"arrived"* phrase.
  - GPS hybrid fallback now enters arrival state when total remaining distance reaches `<= 15 m`.
  - Ignored ambient summary notifications in `hasActiveGoogleMapsNotification()` so clearing the turn-by-turn notification near the destination triggers arrival reliably.

### 🇧🇷 Português (BR)
- **Detecção confiável de chegada ao destino**:
  - Verificação de frases de chegada agora lê todos os campos brutos da notificação (`title`, `text`, `bigText`, `subText`, `textLines`), sem descartar campos que contenham ETA/duração (ex.: `"0 min"`).
  - Detecção automática nos metros finais (`<= 25 m` restantes ou `eta == 0` com `<= 40 m`) mesmo quando o Google Maps exibe apenas o nome/endereço do local sem a palavra *"chegou"*.
  - Contagem regressiva por GPS no navegador híbrido agora dispara o estado de chegada quando a distância total restante atinge `<= 15 m`.
  - Notificações residuais/ambiente do Google Maps não bloqueiam mais o disparo de chegada ao encerrar a navegação próximo ao destino.

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
