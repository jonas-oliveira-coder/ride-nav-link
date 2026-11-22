# Relatório Técnico Completo de Comunicação — RideNav Link

Este documento descreve a arquitetura de comunicação Bluetooth Low Energy (BLE GATT) e os canais de integração Android implementados no **RideNav Link** (`RideNavLink.apk`).

---

## 1. Visão Geral da Arquitetura

| Aspecto | Especificação no **RideNav Link** |
|---|---|
| **SDK / Plataforma** | Android SDK puro em Kotlin (`minSdk = 26`, `targetSdk = 34`), sem frameworks híbridos nem dependências externas |
| **Interface e Tema** | Sistema visual Material Design 3 com suporte a Material You (`DynamicColors` / esquema adaptativo claro e escuro) em [M3DesignSystem.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/M3DesignSystem.kt) |
| **Navegação Turn-by-Turn** | Captura de notificações do **Google Maps** ([MapsNotificationListener.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MapsNotificationListener.kt)) + parser dedicado ([GoogleMapsNotificationParser.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/GoogleMapsNotificationParser.kt)) + refinamento GPS híbrido ([MapsHybridNavigator.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MapsHybridNavigator.kt)) |
| **Comunicação BLE GATT** | [BajajBleManager.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajBleManager.kt) + codificador de frames [BajajProtocol.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt) + serviço persistente [BajajForegroundService.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajForegroundService.kt) |
| **Privacidade / Rede** | **100% local e offline** — sem coleta em nuvem, sem analytics e sem serviços de terceiros |

---

## 2. Camada de Transporte BLE GATT

O **RideNav Link** comunica-se com o painel digital da motocicleta através de **6 características GATT customizadas**, identificadas pelo sufixo UUID `-676e-6972-6565-6e69-676e4543544f`:

| Canal | UUID Completo | Direção | Tamanho | Frequência / Gatilho | Função |
|---|---|---|---|---|---|
| `CHAR_TBT` (`0110`) | `0110676e-6972-6565-6e69-676e4543544f` | Celular → Painel (Write) | **48 bytes** | A cada `1000 ms` (em rota) e imediato na mudança de manobra/distância | Manobra, distância, saída de rotatória, ETD, ETA, rua e checksum |
| `CHAR_STATUS` (`0210`) | `0210676e-6972-6565-6e69-676e4543544f` | Celular → Painel (Write) | **55 bytes** | A cada `1000 ms` + imediato em mudança de chamada/volume/headset | Bateria, sinal, headset HFP, volume, estado/nome da chamada e heartbeat |
| `CHAR_CALLS` (`0310`) | `0310676e-6972-6565-6e69-676e4543544f` | Celular → Painel (Write) | **74 bytes** | Sob demanda (evento) | Chamada perdida (`RINGING → IDLE`) / alerta fixo |
| `CHAR_ALERTS` (`0410`) | `0410676e-6972-6565-6e69-676e4543544f` | Celular → Painel (Write) | **40 bytes** | Sob demanda (evento) | Alerta piscante de SMS nativo (`tipo 1`) ou Mensagens/Apps (`tipo 2`) |
| `CHAR_MEDIA` (`0610`) | `0610676e-6972-6565-6e69-676e4543544f` | Celular → Painel (Write) | **111 bytes** | Mudança de faixa/estado + ticker de `1000 ms` enquanto toca | Título, álbum, artista, tempo decorrido/total e `PlayStatus` (`0/1/2`) |
| `CHAR_CONTROLS` (`0A10`) | `0a10676e-6972-6565-6e69-676e4543544f` | Painel → Celular (Read Polling) | **≥ 14 bytes** (`0..13`) | Polling a cada `700 ms` | Comandos do punho/guidão (volume, chamada, mídia, Maps, ACKs) |

### Detalhes de Estabilidade do Link BLE ([BajajBleManager.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajBleManager.kt))

1. **Conexão e MTU:** Interrompe o scan antes de `connectGatt(..., TRANSPORT_LE)`, solicita `CONNECTION_PRIORITY_HIGH` e `requestMtu(256)` em `onServicesDiscovered`, com **fallback automático de `1500 ms`** caso o stack Bluetooth do aparelho não dispare `onMtuChanged`.
2. **Fila Serializada e Watchdog:** Todas as leituras e escritas GATT passam por uma fila única (`gattQueue`) com timeout de **`1200 ms`** por operação e watchdog a cada **`3000 ms`** para evitar travamentos no barramento BLE.
3. **Prioridade e Deduplicação:** Escritas periódicas (`CHAR_STATUS`, `CHAR_TBT`, `CHAR_MEDIA`) substituem pacotes pendentes obsoletos do mesmo UUID (`replacePending = true`). Escritas críticas (`CHAR_ALERTS`, `CHAR_CALLS`, `CHAR_TBT`) descartam leituras `CHAR_CONTROLS` pendentes na fila para entrega imediata.
4. **Compatibilidade Multi-API e Reconexão:** Suporta tanto a API legada quanto a API 33+ de `BluetoothGatt.writeCharacteristic`, executa reconexão automática com backoff progressivo (`1,5s → 3s → 5s → 8s`), fixação por MAC (`AppPreferences`), integração com `CompanionDeviceManager` e aplicação opcional de apelido no sistema via `BluetoothDevice.setAlias` (API 30+).

---

## 3. Canal 1 — Navegação Turn-by-Turn (`0110` — `CHAR_TBT`)

### 3.1 Estrutura do Pacote de 48 Bytes

Implementado em [`createTbtPacket`](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt):

| Byte(s) | Campo | Codificação / Regra |
|---|---|---|
| `0` | Flags principais + unidade próxima curva + AM/PM | `bit 0 = 1` (ativo); `bits 4..6 = nextDist[0]` (`1` se `< 1000 m`, `0` se km); `bit 7 = 1` se PM (`!isAm`) |
| `1` | Código ASCII da manobra | Letra maiúscula (fixa) ou minúscula (piscante), conforme tabela abaixo |
| `2..5` | Distância até a próxima manobra | Formato `formatDistance` em ordem `[fracLSB, fracMSB, intLSB, intMSB]` |
| `6` | Minutos do ETA | `0..59` |
| `7` | Saída de rotatória + Hora do ETA | `nibble alto (bits 4..6) = roundaboutExit (1..7)`; `nibble baixo (bits 0..3) = hora 1..12` |
| `8..11` | Distância total restante (ETD) | Mesmo formato de `2..5`: `[fracLSB, fracMSB, intLSB, intMSB]` |
| `12` | Unidade ETD + flag de validade | `(totalDist[0] & 0x0F) \| 4` (`bit 2 = 1` indica ETD válido) |
| `13` | Reservado | `0` |
| `14` | Comprimento do texto da via | `0..32` bytes |
| `15..46` | Nome da via (US-ASCII) | Normalizado sem acentos, apenas `[0-9a-zA-Z.]`, truncado em até `31 + "."` |
| `47` | **Checksum** | Soma simples de `bytes[0..46]` com máscara `& 0xFF` |

- **Encerramento da navegação:** [`createTbtEndPacket`](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt) envia um pacote de **48 bytes zerados** para limpar a tela TBT do painel.
- **Regra de piscar (`maneuverForDistance`):**
  - Destino (`'H'`, `72`): alterna para `'b'` (`98`) quando `< 40 m`.
  - Demais manobras com forma piscante: somam `+32` (maiúscula → minúscula ASCII) quando `< 100 m`.
  - `'B'` (`66`), `'T'` (`84`) e `'V'` (`86`) possuem apenas forma fixa.

### 3.2 Tabela de Códigos ASCII de Manobra

| Manobra | Fixo (ASCII / Dec) | Piscante (ASCII / Dec) | Observação de Exibição no LCD |
|---|---|---|---|
| Contramão / Recalculando | `'B'` (`66`) | — | Apenas fixo |
| Leve à esquerda | `'C'` (`67`) | `'c'` (`99`) | Pisca `< 100 m` |
| Leve à direita | `'D'` (`68`) | `'d'` (`100`) | Pisca `< 100 m` |
| Acentuada à esquerda | `'E'` (`69`) | `'e'` (`101`) | Pisca `< 100 m` |
| Acentuada à direita | `'F'` (`70`) | `'f'` (`102`) | Pisca `< 100 m` |
| Seguir em frente | `'G'` (`71`) | `'g'` (`103`) | Pisca `< 100 m` |
| Destino | `'H'` (`72`) | `'b'` (`98`) | Pisca `< 40 m` |
| Virar à esquerda | `'I'` (`73`) | `'i'` (`105`) | Pisca `< 100 m` |
| Virar à direita | `'J'` (`74`) | `'j'` (`106`) | Pisca `< 100 m` |
| Alça / Saída à esquerda | `'K'` (`75`) | `'k'` (`107`) | Pisca `< 100 m` |
| Alça / Saída à direita | `'L'` (`76`) | `'l'` (`108`) | Pisca `< 100 m` |
| Rotatória (mão esquerda) | `'N'` (`78`) | `'n'` (`110`) | Exige saída `1..7` no nibble alto do byte 7 |
| Retorno à esquerda | `'O'` (`79`) | `'o'` (`111`) | Pisca `< 100 m` |
| Retorno à direita | `'P'` (`80`) | `'p'` (`112`) | Pisca `< 100 m` |
| Bifurcação à esquerda | `'Q'` (`81`) | `'q'` (`113`) | Pisca `< 100 m` |
| Bifurcação à direita | `'R'` (`82`) | `'r'` (`114`) | Pisca `< 100 m` |
| Balsa / Trem | `'T'` (`84`) | — | Código reservado no protocolo (sem ícone segmentado dedicado no LCD) |
| Rotatória (mão direita / Brasil) | `'U'` (`85`) | `'u'` (`117`) | Exige saída `1..7` no byte 7 (padrão `2` se omitido pelo mapa) |
| Convergência (Merge) | `'V'` (`86`) | — | Apenas fixo |
| Manter-se à direita | `'X'` (`88`) | `'x'` (`120`) | Pisca `< 100 m` |
| Balsa | `'Y'` (`89`) | `'y'` (`121`) | Código reservado no protocolo (sem ícone segmentado dedicado no LCD) |
| Manter-se à esquerda | `'Z'` (`90`) | `'z'` (`122`) | Pisca `< 100 m` |

### 3.3 Captura no Google Maps + Interpolação GPS Híbrida

Como o Google Maps emite notificações em marcos discretos (ex.: `300 m`, `200 m`, `100 m`), o **RideNav Link** mantém a contagem fluida e precisa em duas etapas:
1. **[GoogleMapsNotificationParser.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/GoogleMapsNotificationParser.kt):**
   - Extrai distância da próxima manobra (`km`, `m`, `mi`, `ft`, `yd`), distância restante total (ETD) e ETA (PT-BR e EN, relógio `HH:mm` ou duração `Xh Ymin`).
   - Isola a cláusula principal da instrução e remove dicas secundárias entre parênteses para evitar leituras erradas de distância ou manobra (como saídas de rotatória).
   - Detecta explicitamente frases de **chegada ao destino**, recálculo e saída da rotatória (`1ª` a `7ª`, com inferência direcional quando omitida).
2. **[MapsHybridNavigator.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MapsHybridNavigator.kt):**
   - Usa a notificação do Google Maps como âncora oficial e ativa o `LocationManager` (`GPS_PROVIDER` a `900 ms`, com fallback para `FUSED_PROVIDER`/`NETWORK_PROVIDER` após `3,5 s` sem GPS) apenas enquanto há rota ativa.
   - Desconta os metros percorridos entre marcos, filtra ruído parado (`speed < 0.8 m/s`) e saltos irreais (`> 65 m/s`), evitando regressão de distância se o Google Maps re-postar a mesma âncora apenas para atualizar o ETA.
   - **Retenção de chegada ao destino:** ao detectar chegada (ou encerramento da notificação já próximo ao destino), mantém a indicação de chegada (`0 m`, bandeira piscante `'b'`) no painel por um intervalo de retenção (`22 s` a `35 s`) antes de enviar `createTbtEndPacket()`.

---

## 4. Canal 2 — Status do Telefone, Telefonia e VoIP (`0210` & `0310`)

### 4.1 Pacote `CHAR_STATUS` (`0210` — 55 bytes, a cada 1000 ms)

Implementado em [`createPhoneStatusPacket`](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt):

| Byte(s) | Campo | Codificação |
|---|---|---|
| `0` | Headset HFP + Volume + flags | `((isHeadsetConnected ? 1 : 0) << 4) \| (volumeLevel & 0x0F) \| 0xC0` (`volumeLevel` `0..10`) |
| `1` | Estado da Chamada + Barras de Bateria | `(callState & 0x07) \| (battBars << 3)` (`battBars` `0..4`: `<20=0`, `<40=1`, `<60=2`, `<80=3`, `>=80=4`) |
| `2` | Força do Sinal Celular | `0..4` (`SignalStrength.level`) |
| `3` | Chamada Ativa (bool) | `1` se `callState == CALL_ACTIVE (3)`, senão `0` |
| `4` | Não em Chamada Ativa (bool inverso) | `0` se `callState == CALL_ACTIVE (3)`, senão `1` |
| `20` | Comprimento do nome do contato | `0..30` (preenchido quando `callState` é `CALL_INCOMING (1)` ou `CALL_ACTIVE (3)`) |
| `21..50` | Nome do contato (UTF-8) | Sanitizado (sem emojis/controle) e truncado em até 30 bytes respeitando codepoints UTF-8 |
| `53` | Heartbeat | Contador incremental `0..255` (`heartbeatCounter and 0xFF`) |

Valores de estado: `0 = NO_CALL`, `1 = INCOMING`, `2 = OUTGOING`, `3 = ACTIVE`, `4 = CALL_END`.

### 4.2 Pacote `CHAR_CALLS` (`0310` — 74 bytes)

Implementado em [`createMissedCallPacket`](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt):
- `byte[0] = 1`
- `byte[1]` = tamanho do nome (`0..32` bytes UTF-8), `bytes[2..33]` = nome
- `byte[34]` = tamanho do número (`0..18` bytes UTF-8), `bytes[35..52]` = número

### 4.3 Fontes de Telefonia, VoIP e Áudio

1. **Telefonia Nativa (`TelephonyCallback` API 31+ / `PhoneStateListener` API < 31 + Broadcast `ACTION_PHONE_STATE_CHANGED`):**
   - Monitora nível de sinal celular e transições `IDLE / RINGING / OFFHOOK`.
   - Identifica chamada efetuada (`IDLE → OFFHOOK`) como `CALL_OUTGOING (2)` e chamada perdida (`RINGING → IDLE`) enviando `CHAR_CALLS`.
   - Ao encerrar chamada ativa (`OFFHOOK → IDLE`), emite pulso `CALL_END (4)` por **`1200 ms`** antes de voltar a `CALL_NO_CALL (0)`.
   - Resolve nomes via `ContactsContract.PhoneLookup` (`READ_CONTACTS`).
2. **Chamadas VoIP e Dialer ([MapsNotificationListener.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MapsNotificationListener.kt)):**
   - Monitora notificações `CATEGORY_CALL`, `Notification.CallStyle`, WhatsApp, WhatsApp Business e apps de discador (`dialer`/`telecom`/`incallui`), extraindo interlocutor (`Person`, `EXTRA_TITLE`) e estado (`CALL_INCOMING` vs. `CALL_ACTIVE`).
3. **Headset HFP e Volume:**
   - Monitora `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED`, eventos ACL, `VOLUME_CHANGED_ACTION` e `ContentObserver` em `Settings.System.CONTENT_URI`, normalizando `STREAM_VOICE_CALL` (em chamada) ou `STREAM_MUSIC` para `0..10`.

---

## 5. Canal 3 — SMS Nativo e Notificações de Aplicativos (`0410` — `CHAR_ALERTS`)

Implementado em [`createAlertPacket`](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt):
- **Tamanho total:** **40 bytes**
- `byte[0]` = `alertType` (`1` = `ALERT_SMS`, ícone SMS piscante; `2` = `ALERT_WHATSAPP`, ícone de mensagem/app piscante)
- `byte[1]` = tamanho do texto (`0..32` bytes UTF-8)
- `bytes[2..33]` = texto sanitizado e truncado com segurança UTF-8

**Fontes suportadas:**
1. **SMS Nativo (`SMS_RECEIVED_ACTION`):** agrega PDUs multiparte, resolve o contato, deduplica por `5000 ms` e envia `ALERT_SMS (1)` quando habilitado nas configurações.
2. **Aplicativos Selecionáveis:** permite escolher qualquer app instalado para encaminhamento de alertas, escolhendo o ícone no painel (`BLINK_WAPP` tipo `2` ou `BLINK_SMS` tipo `1`), filtrando resumos de grupo (`FLAG_GROUP_SUMMARY`), atualizações silenciosas (`FLAG_ONLY_ALERT_ONCE`) e notificações de status/backup.

---

## 6. Canal 4 — Mídia e Áudio (`0610` — `CHAR_MEDIA`)

Implementado em [`createMusicPacket`](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt):

| Byte(s) | Campo | Codificação |
|---|---|---|
| `0` | Ativo | `1` |
| `1` / `2..33` | Nome da música | Tamanho (`0..32`) + UTF-8 |
| `34` / `35..66` | Álbum | Tamanho (`0..32`) + UTF-8 |
| `67` / `68..99` | Artista | Tamanho (`0..32`) + UTF-8 |
| `100..101` | Posição decorrida (segundos) | `Short` **Big-Endian** |
| `102..103` | Duração total (segundos) | `Short` **Big-Endian** |
| `104` | `PlayStatus` | `0 = NONE`, `1 = PAUSED`, `2 = PLAY` |

O [MapsNotificationListener.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MapsNotificationListener.kt) registra `MediaSessionManager.OnActiveSessionsChangedListener` e `MediaController.Callback`, interpola a posição em tempo real pelo `playbackSpeed`, valida áudio ativo via `AudioManager.isMusicActive`, atualiza a cada `1000 ms` durante a reprodução e possui fallback via notificação contínua caso nenhum `MediaController` esteja vinculado.

---

## 7. Canal 5 — Comandos Bidirecionais do Guidão (`0A10` — `CHAR_CONTROLS`)

O painel disponibiliza o estado dos botões físicos do punho esquerdo por **polling de leitura a cada `700 ms`** na característica `0A10` ([`parseControlsPacket`](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt)), detectando comandos por mudança de estado (borda) após 2 leituras iniciais de baseline:

| Byte | Máscara / Bits | Campo | Ação executada no RideNav Link |
|---|---|---|---|
| `0` | `& 0x0F` (bits 0..3) | `volumeToSet` | Se `!= 0`, ajusta o volume (`STREAM_VOICE_CALL` ou `STREAM_MUSIC`) na escala `0..10` e reenvia `CHAR_STATUS` |
| `1` | `0..255` | `callAccept` | Se `!= 0`, chama `TelecomManager.acceptRingingCall()` (`ANSWER_PHONE_CALLS`) |
| `2` | `0..255` | `callReject` | Chama `TelecomManager.endCall()` e, se ativado, envia SMS rápido de recusa |
| `3` | `0..255` | `callRejectWithSms` | Chama `TelecomManager.endCall()` e envia SMS rápido configurável via `SmsManager` |
| `4` | `& 0x3F` (bits 0..5) | `pagePlaylist` | Registra evento de playlist/página no diagnóstico |
| `5` | `0..255` | `takeMeHome` | Abre rota para o endereço de casa configurado no Google Maps |
| `6` | `0..255` | `resumeSong` | `MediaController.TransportControls.play()` |
| `7` | `0..255` | `pauseSong` | `MediaController.TransportControls.pause()` |
| `8` | `0..255` | `skipToNext` | `MediaController.TransportControls.skipToNext()` |
| `9` | `0..255` | `skipToPrev` | Chama `skipToPrevious()` duas vezes para retornar à faixa anterior |
| `10` | `0..255` | `stopSong` | `MediaController.TransportControls.stop()` |
| `11` | `0..255` | `missedCallGet` | ACK do painel confirmando recebimento em `CHAR_CALLS` |
| `12` | `0..255` | `alertGet` | ACK do painel confirmando recebimento em `CHAR_ALERTS` |
| `13` | `0..255` | `launchMediaPlayer` | Abre o player ativo, o player preferido ou o primeiro player instalado (Spotify, YT Music, Deezer, Amazon Music, Samsung Music, Apple Music, SoundCloud, YouTube) |

---

## 8. Ciclo de Vida, Background e Permissões

- **[BajajForegroundService.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajForegroundService.kt):** serviço `START_STICKY` com `foregroundServiceType="connectedDevice|location"`, `PARTIAL_WAKE_LOCK` (`4h`), monitoramento de `BluetoothAdapter.ACTION_STATE_CHANGED` e agendamento de reinício em `1500 ms` via `AlarmManager` em `onTaskRemoved`.
- **[BootCompletedReceiver.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BootCompletedReceiver.kt):** reinicia o serviço e solicita rebind do listener de notificações após `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` e `QUICKBOOT_POWERON`.
- **Manifesto ([AndroidManifest.xml](bajaj_bridge/app/src/main/AndroidManifest.xml)):** declara apenas permissões necessárias aos recursos locais (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`/`COARSE`, `FOREGROUND_SERVICE_*`, `READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`, `ANSWER_PHONE_CALLS`, `CALL_PHONE`, `SEND_SMS`, `RECEIVE_SMS`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), feature opcional `companion_device_setup` e bloco `<queries>` para Google Maps e players de mídia.

---

## 9. Resumo dos Módulos Ativos

| # | Canal / Recurso | Implementação no RideNav Link |
|---|---|---|
| 1 | Descoberta, link BLE GATT, MTU 256, prioridade HIGH, watchdog e reconexão | [BajajBleManager.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajBleManager.kt) |
| 2 | Navegação TBT (`0110` — 48B + checksum + piscar + chegada ao destino) | [BajajProtocol.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt), [GoogleMapsNotificationParser.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/GoogleMapsNotificationParser.kt), [MapsHybridNavigator.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MapsHybridNavigator.kt) |
| 3 | Status do telefone (`0210` — 55B / 1000ms) e chamada perdida (`0310` — 74B) | [BajajProtocol.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajProtocol.kt), [BajajBleManager.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajBleManager.kt) |
| 4 | Chamadas VoIP (`CallStyle` / WhatsApp / Dialer), SMS nativo e alertas multi-app (`0410` — 40B) | [MapsNotificationListener.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MapsNotificationListener.kt), [BajajBleManager.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajBleManager.kt) |
| 5 | Mídia / Música (`0610` — 111B) e controles do guidão (`0A10` — polling `700 ms`) | [MapsNotificationListener.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MapsNotificationListener.kt), [BajajBleManager.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajBleManager.kt) |
| 6 | Serviço persistente, boot automático, UI Material 3 e diagnóstico ao vivo | [BajajForegroundService.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BajajForegroundService.kt), [BootCompletedReceiver.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/BootCompletedReceiver.kt), [MainActivity.kt](bajaj_bridge/app/src/main/java/com/custom/ridenavlink/MainActivity.kt) |
