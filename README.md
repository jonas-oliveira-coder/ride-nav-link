# RideNav Link

> **Aviso de Compatibilidade e Independência:**  
> Aplicativo independente compatível com motocicletas Bajaj Dominar e Pulsar equipadas com painel Bluetooth. Não é um produto oficial e não possui vínculo com a Bajaj Auto.

**RideNav Link** é um aplicativo Android nativo (Kotlin + Android SDK puro, sem dependências externas, sem React Native e sem telemetria em nuvem) que atua como ponte Bluetooth Low Energy (BLE GATT) entre o smartphone e o painel digital LCD da motocicleta.

---

## Principais Funcionalidades

- **Navegação Turn-by-Turn via Google Maps + GPS Híbrido:**
  - Detecta automaticamente rotas ativas do **Google Maps** via `NotificationListenerService`.
  - Captura instruções, distâncias, ETD, ETA e saídas de rotatória (`1ª` a `7ª`) e mantém a indicação de **chegada ao destino** no painel antes de encerrar a navegação (`NAV OFF`).
  - Refina a contagem de metros em tempo real usando `LocationManager` (`GPS_PROVIDER` com fallback para `FUSED`/`NETWORK`), mantendo o painel atualizado continuamente e alternando setas fixas para piscantes abaixo de `100 m` (e `< 40 m` na chegada).
- **Chamadas Nativas e VoIP:**
  - Detecta ligações recebidas, efetuadas, ativas, encerramento (`CALL_END`) e chamadas perdidas via `TelephonyCallback` / `PhoneStateListener` e notificações `CallStyle` (WhatsApp, WhatsApp Business e discador).
  - Resolve nomes via `ContactsContract` para exibição no painel.
- **Status do Telefone (`1 Hz`):**
  - Envia nível de bateria (`0..4` barras), intensidade de sinal celular (`0..4`), status de intercomunicador/headset HFP, volume atual (`0..10`) e heartbeat.
- **Alertas de SMS e Aplicativos:**
  - Encaminha SMS recebidos e notificações de aplicativos selecionáveis pelo usuário (WhatsApp, Telegram, Mensagens, etc.) com modo configurável (`Piscante WAPP`, `Piscante SMS` ou `Aceso Fixo`).
- **Mídia e Áudio:**
  - Integra-se ao `MediaSessionManager` para enviar título, álbum, artista, tempo decorrido, duração total e estado de reprodução (`PLAY` / `PAUSED` / `NONE`).
- **Comandos Bidirecionais do Guidão:**
  - Faz polling a cada `700 ms` na característica de controles do painel para executar no celular: ajuste de volume, atender/recusar chamada, recusar com SMS rápido configurável, abrir rota para casa no Google Maps, play/pause/próxima/anterior (`x2`)/stop e abrir player preferido.
- **Estabilidade em Segundo Plano e Privacidade:**
  - `ForegroundService` (`connectedDevice|location`) com `PARTIAL_WAKE_LOCK`, reconexão BLE automática com backoff, watchdog GATT, fixação por endereço MAC e `CompanionDeviceManager`.
  - **100% local/offline:** zero telemetria, analytics, rastreadores ou envio de dados para servidores externos.

---

## Arquitetura do Projeto

```text
.
├── bajaj_bridge/
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/custom/bajajbridge/
│   │   │   ├── AppPreferences.kt               # Preferências locais e apps selecionados
│   │   │   ├── BajajBleManager.kt              # Gerenciador BLE GATT, fila serial, telefonia e watchdog
│   │   │   ├── BajajBridgeApp.kt               # Application singleton
│   │   │   ├── BajajForegroundService.kt       # Serviço persistente em primeiro plano + WakeLock
│   │   │   ├── BajajProtocol.kt                # Codificação/decodificação exata dos frames BLE
│   │   │   ├── BootCompletedReceiver.kt        # Reinício automático pós-boot/atualização
│   │   │   ├── GoogleMapsNotificationParser.kt # Parser PT-BR/EN de notificações do Google Maps
│   │   │   ├── M3DesignSystem.kt               # Sistema visual Material 3 nativo
│   │   │   ├── MainActivity.kt                 # Interface principal, scanner BLE, testes e diagnóstico
│   │   │   ├── MapsHybridNavigator.kt          # Interpolação GPS entre âncoras do Google Maps
│   │   │   └── MapsNotificationListener.kt     # Listener de Maps, VoIP, alertas e MediaSession
│   │   └── res/                                # Vetores adaptativos e ícones de manobras
│   └── design_assets/                          # Conceitos vetoriais SVG da marca
├── build_clean.ps1                             # Script de compilação direta (Windows / PowerShell)
├── build_clean.sh                              # Script de compilação direta (Linux / macOS)
└── RELATORIO_COMUNICACAO_BAJAJ.md              # Documentação técnica detalhada dos protocolos BLE
```

---

## Protocolo BLE GATT Utilizado

Todos os canais operam sobre as 6 características GATT do cluster (`-676e-6972-6565-6e69-676e4543544f`):

| Canal | Prefixo UUID | Direção | Tamanho | Função |
|---|---|---|---|---|
| `CHAR_TBT` | `0110` | Celular → Painel | 48 bytes | Manobra ASCII, distância, saída de rotatória, ETD, ETA, checksum |
| `CHAR_STATUS` | `0210` | Celular → Painel | 55 bytes | Bateria, sinal, headset HFP, volume, chamada ativa e heartbeat |
| `CHAR_CALLS` | `0310` | Celular → Painel | 74 bytes | Alerta de chamada perdida / aviso fixo |
| `CHAR_ALERTS` | `0410` | Celular → Painel | 40 bytes | Alerta piscante de SMS (`tipo 1`) ou Mensagens/Apps (`tipo 2`) |
| `CHAR_MEDIA` | `0610` | Celular → Painel | 111 bytes | Música, álbum, artista, progresso/duração e status de mídia |
| `CHAR_CONTROLS` | `0A10` | Painel → Celular | ≥ 14 bytes | Polling (`700 ms`) dos botões do punho esquerdo e ACKs |

Para detalhes bit a bit dos frames e comparação técnica completa, consulte [`RELATORIO_COMUNICACAO_BAJAJ.md`](RELATORIO_COMUNICACAO_BAJAJ.md).

---

## Como Compilar e Instalar

O projeto compila diretamente com **Android SDK Build Tools + `kotlinc` + `d8` + `apksigner`**, sem necessidade de Gradle.

### Windows (PowerShell)
Ajuste os caminhos do Android SDK / `kotlinc` / keystore no topo de `build_clean.ps1` se necessário e execute:

```powershell
.\build_clean.ps1
```

### Linux / macOS
```bash
chmod +x build_clean.sh
./build_clean.sh
```

### Instalação via ADB
```bash
adb install -r bajaj_bridge/build/RideNavLink.apk
```

