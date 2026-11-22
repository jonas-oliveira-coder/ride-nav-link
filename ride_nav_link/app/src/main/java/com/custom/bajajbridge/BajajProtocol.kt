package com.custom.bajajbridge

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Calendar
import kotlin.math.max

object BajajProtocol {

    // ASCII codes for maneuvers supported by the BLE cluster
    const val ARROW_WRONG_WAY: Byte = 66                  // 'B'
    const val ARROW_SLIGHT_LEFT_SOLID: Byte = 67          // 'C'
    const val ARROW_SLIGHT_LEFT_BLINK: Byte = 99          // 'c'
    const val ARROW_SLIGHT_RIGHT_SOLID: Byte = 68         // 'D'
    const val ARROW_SLIGHT_RIGHT_BLINK: Byte = 100        // 'd'
    const val ARROW_SHARP_LEFT_SOLID: Byte = 69           // 'E'
    const val ARROW_SHARP_LEFT_BLINK: Byte = 101          // 'e'
    const val ARROW_SHARP_RIGHT_SOLID: Byte = 70          // 'F'
    const val ARROW_SHARP_RIGHT_BLINK: Byte = 102         // 'f'
    const val ARROW_STRAIGHT_SOLID: Byte = 71             // 'G'
    const val ARROW_STRAIGHT_BLINK: Byte = 103            // 'g'
    const val ARROW_DESTINATION_SOLID: Byte = 72          // 'H'
    const val ARROW_DESTINATION_BLINK: Byte = 98          // 'b'
    const val ARROW_LEFT_SOLID: Byte = 73                 // 'I'
    const val ARROW_LEFT_BLINK: Byte = 105                // 'i'
    const val ARROW_RIGHT_SOLID: Byte = 74                // 'J'
    const val ARROW_RIGHT_BLINK: Byte = 106               // 'j'
    const val ARROW_RAMP_LEFT_SOLID: Byte = 75            // 'K'
    const val ARROW_RAMP_LEFT_BLINK: Byte = 107           // 'k'
    const val ARROW_RAMP_RIGHT_SOLID: Byte = 76           // 'L'
    const val ARROW_RAMP_RIGHT_BLINK: Byte = 108          // 'l'
    const val ARROW_ROUNDABOUT_LEFT_SOLID: Byte = 78      // 'N'
    const val ARROW_ROUNDABOUT_LEFT_BLINK: Byte = 110     // 'n'
    const val ARROW_UTURN_LEFT_SOLID: Byte = 79           // 'O'
    const val ARROW_UTURN_LEFT_BLINK: Byte = 111          // 'o'
    const val ARROW_UTURN_RIGHT_SOLID: Byte = 80          // 'P'
    const val ARROW_UTURN_RIGHT_BLINK: Byte = 112         // 'p'
    const val ARROW_FORK_LEFT_SOLID: Byte = 81            // 'Q'
    const val ARROW_FORK_LEFT_BLINK: Byte = 113           // 'q'
    const val ARROW_FORK_RIGHT_SOLID: Byte = 82           // 'R'
    const val ARROW_FORK_RIGHT_BLINK: Byte = 114          // 'r'
    // Hardware note: The motorcycle's segmented LCD lacks dedicated segments for ferry/train.
    const val ARROW_FERRY_TRAIN_SOLID: Byte = 84          // 'T'
    const val ARROW_ROUNDABOUT_RIGHT_SOLID: Byte = 85     // 'U' (mão direita / Brasil)
    const val ARROW_ROUNDABOUT_RIGHT_BLINK: Byte = 117    // 'u'
    const val ARROW_MERGE: Byte = 86                      // 'V'
    const val ARROW_KEEP_RIGHT_SOLID: Byte = 88           // 'X'
    const val ARROW_KEEP_RIGHT_BLINK: Byte = 120          // 'x'
    const val ARROW_FERRY_SOLID: Byte = 89                // 'Y'
    const val ARROW_FERRY_BLINK: Byte = 121               // 'y'
    const val ARROW_KEEP_LEFT_SOLID: Byte = 90            // 'Z'
    const val ARROW_KEEP_LEFT_BLINK: Byte = 122           // 'z'

    // Default maneuver aliases for standard traffic flow
    const val ARROW_UTURN_SOLID: Byte = ARROW_UTURN_LEFT_SOLID
    const val ARROW_UTURN_BLINK: Byte = ARROW_UTURN_LEFT_BLINK
    const val ARROW_ROUNDABOUT_SOLID: Byte = ARROW_ROUNDABOUT_LEFT_SOLID
    const val ARROW_ROUNDABOUT_BLINK: Byte = ARROW_ROUNDABOUT_LEFT_BLINK

    // Call states recognized by cluster BLE protocol
    const val CALL_NO_CALL: Int = 0
    const val CALL_INCOMING: Int = 1
    const val CALL_OUTGOING: Int = 2
    const val CALL_ACTIVE: Int = 3
    const val CALL_END: Int = 4

    const val ALERT_SMS: Byte = 1
    const val ALERT_WHATSAPP: Byte = 2
    const val MAX_ALERT_BYTES: Int = 32

    // Media playback states in BLE protocol
    const val PLAY_STATUS_NONE: Byte = 0
    const val PLAY_STATUS_PAUSED: Byte = 1
    const val PLAY_STATUS_PLAY: Byte = 2

    data class ClusterControlsPacket(
        val volumeToSet: Int,
        val callAccept: Int,
        val callReject: Int,
        val callRejectWithSms: Int,
        val pagePlaylist: Int,
        val takeMeHome: Int,
        val resumeSong: Int,
        val pauseSong: Int,
        val skipToNext: Int,
        val skipToPrev: Int,
        val stopSong: Int,
        val missedCallGet: Int,
        val alertGet: Int,
        val launchMediaPlayer: Int
    )

    fun toBlinkCode(code: Byte): Byte = when (code) {
        ARROW_SLIGHT_LEFT_SOLID -> ARROW_SLIGHT_LEFT_BLINK
        ARROW_SLIGHT_RIGHT_SOLID -> ARROW_SLIGHT_RIGHT_BLINK
        ARROW_SHARP_LEFT_SOLID -> ARROW_SHARP_LEFT_BLINK
        ARROW_SHARP_RIGHT_SOLID -> ARROW_SHARP_RIGHT_BLINK
        ARROW_STRAIGHT_SOLID -> ARROW_STRAIGHT_BLINK
        ARROW_DESTINATION_SOLID -> ARROW_DESTINATION_BLINK
        ARROW_LEFT_SOLID -> ARROW_LEFT_BLINK
        ARROW_RIGHT_SOLID -> ARROW_RIGHT_BLINK
        ARROW_RAMP_LEFT_SOLID -> ARROW_RAMP_LEFT_BLINK
        ARROW_RAMP_RIGHT_SOLID -> ARROW_RAMP_RIGHT_BLINK
        ARROW_ROUNDABOUT_LEFT_SOLID -> ARROW_ROUNDABOUT_LEFT_BLINK
        ARROW_UTURN_LEFT_SOLID -> ARROW_UTURN_LEFT_BLINK
        ARROW_UTURN_RIGHT_SOLID -> ARROW_UTURN_RIGHT_BLINK
        ARROW_FORK_LEFT_SOLID -> ARROW_FORK_LEFT_BLINK
        ARROW_FORK_RIGHT_SOLID -> ARROW_FORK_RIGHT_BLINK
        ARROW_ROUNDABOUT_RIGHT_SOLID -> ARROW_ROUNDABOUT_RIGHT_BLINK
        ARROW_KEEP_RIGHT_SOLID -> ARROW_KEEP_RIGHT_BLINK
        ARROW_FERRY_SOLID -> ARROW_FERRY_BLINK
        ARROW_KEEP_LEFT_SOLID -> ARROW_KEEP_LEFT_BLINK
        else -> code
    }

    fun toSolidCode(code: Byte): Byte = when (code) {
        ARROW_SLIGHT_LEFT_BLINK -> ARROW_SLIGHT_LEFT_SOLID
        ARROW_SLIGHT_RIGHT_BLINK -> ARROW_SLIGHT_RIGHT_SOLID
        ARROW_SHARP_LEFT_BLINK -> ARROW_SHARP_LEFT_SOLID
        ARROW_SHARP_RIGHT_BLINK -> ARROW_SHARP_RIGHT_SOLID
        ARROW_STRAIGHT_BLINK -> ARROW_STRAIGHT_SOLID
        ARROW_DESTINATION_BLINK -> ARROW_DESTINATION_SOLID
        ARROW_LEFT_BLINK -> ARROW_LEFT_SOLID
        ARROW_RIGHT_BLINK -> ARROW_RIGHT_SOLID
        ARROW_RAMP_LEFT_BLINK -> ARROW_RAMP_LEFT_SOLID
        ARROW_RAMP_RIGHT_BLINK -> ARROW_RAMP_RIGHT_SOLID
        ARROW_ROUNDABOUT_LEFT_BLINK -> ARROW_ROUNDABOUT_LEFT_SOLID
        ARROW_UTURN_LEFT_BLINK -> ARROW_UTURN_LEFT_SOLID
        ARROW_UTURN_RIGHT_BLINK -> ARROW_UTURN_RIGHT_SOLID
        ARROW_FORK_LEFT_BLINK -> ARROW_FORK_LEFT_SOLID
        ARROW_FORK_RIGHT_BLINK -> ARROW_FORK_RIGHT_SOLID
        ARROW_ROUNDABOUT_RIGHT_BLINK -> ARROW_ROUNDABOUT_RIGHT_SOLID
        ARROW_KEEP_RIGHT_BLINK -> ARROW_KEEP_RIGHT_SOLID
        ARROW_FERRY_BLINK -> ARROW_FERRY_SOLID
        ARROW_KEEP_LEFT_BLINK -> ARROW_KEEP_LEFT_SOLID
        else -> code
    }

    fun isBlinkCode(code: Byte): Boolean = code != toSolidCode(code)

    /**
     * Regra de piscar do visor:
     * - Destino ('H') pisca ('b') quando < 40 m
     * - Demais manobras piscam quando < 100 m
     * - 'B', 'T' e 'V' possuem apenas forma fixa
     */
    fun maneuverForDistance(maneuverCode: Byte, distanceMeters: Int): Byte {
        val solid = toSolidCode(maneuverCode)
        val shouldBlink = if (solid == ARROW_DESTINATION_SOLID) {
            distanceMeters < 40
        } else {
            distanceMeters < 100
        }
        return if (shouldBlink) toBlinkCode(solid) else solid
    }

    fun utf8ByteLength(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size

    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        if (maxBytes <= 0 || text.isEmpty()) return ByteArray(0)
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        if (raw.size <= maxBytes) return raw

        var byteCount = 0
        var endCharIndex = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            val cpBytes = text.substring(i, i + charCount).toByteArray(StandardCharsets.UTF_8).size
            if (byteCount + cpBytes > maxBytes) break
            byteCount += cpBytes
            endCharIndex = i + charCount
            i += charCount
        }
        return text.substring(0, endCharIndex).toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Codificação de distância para o frame TBT:
     * - byte 0: flag de unidade (1 = metros < 1000m, 0 = km >= 1000m)
     * - byte 1..2: parte inteira (short big-endian)
     * - byte 3..4: parte fracionária (0..99, short big-endian)
     */
    private fun formatDistance(distanceMeters: Double): ByteArray {
        val result = ByteArray(5)
        var d = distanceMeters.coerceAtLeast(0.0)
        val isMeters: Boolean
        val intPart: Int
        val fracPart: Int

        if (d < 1000.0) {
            isMeters = true
            intPart = d.toInt()
            fracPart = 0
        } else {
            isMeters = false
            d /= 1000.0
            intPart = d.toInt()
            fracPart = ((d - intPart) * 100.0).toInt()
        }

        result[0] = if (isMeters) 1 else 0
        result[1] = ((intPart shr 8) and 0xFF).toByte()
        result[2] = (intPart and 0xFF).toByte()
        result[3] = ((fracPart shr 8) and 0xFF).toByte()
        result[4] = (fracPart and 0xFF).toByte()

        return result
    }

    fun batteryPercentToBars(batteryPercent: Int): Int {
        return when {
            batteryPercent < 20 -> 0
            batteryPercent < 40 -> 1
            batteryPercent < 60 -> 2
            batteryPercent < 80 -> 3
            else -> 4
        }
    }

    /**
     * Pacote TBT de Navegação (48 bytes)
     */
    fun createTbtPacket(
        distanceMeters: Int,
        maneuverCode: Byte,
        text: String = "Siga em frente",
        totalDistanceMeters: Int = distanceMeters,
        etaSeconds: Int = -1,
        isAm: Boolean? = null,
        etaMinutes: Int = -1,
        etaHours: Int = -1,
        roundaboutExit: Int = 0
    ): ByteArray {
        val packet = ByteArray(48)

        val safeDist = distanceMeters.coerceAtLeast(0)
        val safeTotal = max(safeDist, totalDistanceMeters.coerceAtLeast(0))

        val nextDist = formatDistance(safeDist.toDouble())
        val totalDist = formatDistance(safeTotal.toDouble())

        // Dynamically compute ETA from current time if not provided
        val cal = Calendar.getInstance()
        val estSeconds = if (etaSeconds >= 0) etaSeconds else max(60, safeTotal / 12)
        cal.add(Calendar.SECOND, estSeconds)

        val resolvedIsAm = isAm ?: if (etaHours >= 0) {
            (etaHours % 24) < 12
        } else {
            cal.get(Calendar.AM_PM) == Calendar.AM
        }
        val resolvedHours = if (etaHours >= 0) {
            val h12 = etaHours % 12
            if (h12 == 0) 12 else h12
        } else {
            val h = cal.get(Calendar.HOUR)
            if (h == 0) 12 else h
        }
        val resolvedMinutes = if (etaMinutes >= 0) (etaMinutes % 60) else cal.get(Calendar.MINUTE)

        // Byte 0: bit 0 = active (1), bits 4..7 = meters flag (nextDist[0]), bit 7 = PM (!isAm)
        var b0 = 1
        b0 = b0 or ((nextDist[0].toInt() and 0x0F) shl 4)
        if (!resolvedIsAm) {
            b0 = b0 or (1 shl 7)
        }
        packet[0] = b0.toByte()

        // Byte 1: Maneuver ASCII code
        packet[1] = maneuverCode

        // Bytes 2..5: Distance to next maneuver (little-endian pairs)
        packet[2] = nextDist[4]
        packet[3] = nextDist[3]
        packet[4] = nextDist[2]
        packet[5] = nextDist[1]

        // Byte 6: ETA minutes
        packet[6] = resolvedMinutes.toByte()

        // Byte 7: Hora (1..12) | (roundaboutExit << 4)
        // Nota de hardware: o visor exige saída 1..7 para rotatórias ('U'/'u'/'N'/'n'); 0 deixa o painel vazio.
        val solidManeuver = toSolidCode(maneuverCode)
        val isRoundabout = solidManeuver == ARROW_ROUNDABOUT_RIGHT_SOLID ||
            solidManeuver == ARROW_ROUNDABOUT_LEFT_SOLID
        val safeExit = if (isRoundabout && roundaboutExit !in 1..7) 2 else roundaboutExit
        val clampedExit = safeExit.coerceIn(0, 7)
        packet[7] = ((clampedExit shl 4) or (resolvedHours and 0x0F)).toByte()

        // Bytes 8..12: Distância total restante (ETD) + flag de ETD válido (| 4)
        packet[8] = totalDist[4]
        packet[9] = totalDist[3]
        packet[10] = totalDist[2]
        packet[11] = totalDist[1]
        packet[12] = ((totalDist[0].toInt() and 0x0F) or 4).toByte()

        // Bytes 14..46: Texto da via sanitizado (US-ASCII)
        // Nota: O LCD segmentado da moto exibe apenas TURN, seta, ETD e ETA; não renderiza texto de rua.
        val asciiNormalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        val cleanText = asciiNormalized.replace("[^0-9a-zA-Z.]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
        val truncated = if (cleanText.length > 31) cleanText.substring(0, 31) + "." else cleanText
        val textBytes = truncated.toByteArray(StandardCharsets.US_ASCII)
        val safeLen = textBytes.size.coerceAtMost(32)
        packet[14] = safeLen.toByte()
        System.arraycopy(textBytes, 0, packet, 15, safeLen)

        // Byte 47: Checksum (soma simples dos bytes 0..46 & 0xFF)
        var sum = 0
        for (i in 0 until 47) {
            sum = (sum + (packet[i].toInt() and 0xFF)) and 0xFF
        }
        packet[47] = sum.toByte()

        return packet
    }

    /**
     * Pacote para encerrar modo de navegação no painel
     */
    fun createTbtEndPacket(): ByteArray = ByteArray(48)

    /**
     * Pacote de Status do Telefone e Chamada (55 bytes)
     */
    fun createPhoneStatusPacket(
        batteryPercent: Int,
        signalLevel: Int,
        heartbeatCounter: Int,
        isHeadsetConnected: Boolean = false,
        volumeLevel: Int = 10,
        callState: Int = CALL_NO_CALL,
        callerName: String = "",
        batteryBarsOverride: Int? = null
    ): ByteArray {
        val packet = ByteArray(55)

        val headsetBit = if (isHeadsetConnected) 1 else 0
        packet[0] = ((headsetBit shl 4) or (volumeLevel and 0x0F) or 0xC0).toByte()

        val battBars = (batteryBarsOverride ?: batteryPercentToBars(batteryPercent)).coerceIn(0, 4)
        packet[1] = ((callState and 0x07) or (battBars shl 3)).toByte()
        packet[2] = signalLevel.coerceIn(0, 4).toByte()
        packet[3] = if (callState == CALL_ACTIVE) 1 else 0
        packet[4] = if (callState == CALL_ACTIVE) 0 else 1

        if ((callState == CALL_INCOMING || callState == CALL_ACTIVE) && callerName.isNotEmpty()) {
            val cleanName = callerName
                .replace("[\\p{So}\\p{Cn}\\p{Cf}]".toRegex(), "")
                .replace("\\s+".toRegex(), " ")
                .trim()
            val nameBytes = truncateUtf8(cleanName, 30)
            packet[20] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, packet, 21, nameBytes.size)
        }

        packet[53] = (heartbeatCounter and 0xFF).toByte()
        return packet
    }

    fun createMissedCallPacket(callerName: String, callerNumber: String): ByteArray {
        val packet = ByteArray(74)
        packet[0] = 1

        val nameBytes = truncateUtf8(callerName, 32)
        packet[1] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, packet, 2, nameBytes.size)

        val numBytes = truncateUtf8(callerNumber, 18)
        packet[34] = numBytes.size.toByte()
        System.arraycopy(numBytes, 0, packet, 35, numBytes.size)

        return packet
    }

    fun createAlertPacket(alertType: Byte, message: String): ByteArray {
        val packet = ByteArray(40)
        packet[0] = alertType

        val msgBytes = truncateUtf8(message.trim(), MAX_ALERT_BYTES)
        packet[1] = msgBytes.size.toByte()
        System.arraycopy(msgBytes, 0, packet, 2, msgBytes.size)

        return packet
    }

    /**
     * Pacote de Mídia (111 bytes)
     * Layout exato:
     * - b[0] = 1
     * - b[1] + b[2..33] = songName (até 32 bytes UTF-8)
     * - b[34] + b[35..66] = songAlbum (até 32 bytes UTF-8)
     * - b[67] + b[68..99] = songArtist (até 32 bytes UTF-8)
     * - b[100..101] = currentMusicDuration (segundos decorridos, short Big-Endian)
     * - b[102..103] = currentMusicTotalDuration (duração total em s, short Big-Endian)
     * - b[104] = PlayStatus (0 = NONE, 1 = PAUSED, 2 = PLAY)
     */
    fun createMusicPacket(
        title: String,
        artist: String = "",
        album: String = "",
        durationSec: Int = 0,
        positionSec: Int = 0,
        isPlaying: Boolean = true,
        playStatusOverride: Byte? = null
    ): ByteArray {
        val packet = ByteArray(111)
        packet[0] = 1

        val tBytes = truncateUtf8(title, 32)
        packet[1] = tBytes.size.toByte()
        System.arraycopy(tBytes, 0, packet, 2, tBytes.size)

        val albBytes = truncateUtf8(album, 32)
        packet[34] = albBytes.size.toByte()
        System.arraycopy(albBytes, 0, packet, 35, albBytes.size)

        val aBytes = truncateUtf8(artist, 32)
        packet[67] = aBytes.size.toByte()
        System.arraycopy(aBytes, 0, packet, 68, aBytes.size)

        val posShort = positionSec.coerceIn(0, 65535)
        packet[100] = ((posShort shr 8) and 0xFF).toByte()
        packet[101] = (posShort and 0xFF).toByte()

        val durShort = durationSec.coerceIn(0, 65535)
        packet[102] = ((durShort shr 8) and 0xFF).toByte()
        packet[103] = (durShort and 0xFF).toByte()

        packet[104] = playStatusOverride ?: if (isPlaying) PLAY_STATUS_PLAY else PLAY_STATUS_PAUSED

        return packet
    }

    /**
     * Parser de CHAR_CONTROLS (0A10) a partir do buffer lido do painel:
     * - byte[0] bits 0..3: volumeToSet (0..10; 0 é ignorado)
     * - byte[1]: callAccept
     * - byte[2]: callReject
     * - byte[3]: callRejectWithSms
     * - byte[4] bits 0..5: pagePlaylist
     * - byte[5]: takeMeHome
     * - byte[6]: resumeSong
     * - byte[7]: pauseSong
     * - byte[8]: skipToNext
     * - byte[9]: skipToPrev
     * - byte[10]: stopSong
     * - byte[11]: missedCallGet ACK
     * - byte[12]: alertGet ACK
     * - byte[13]: launchMediaPlayer
     */
    fun parseControlsPacket(data: ByteArray): ClusterControlsPacket? {
        if (data.size < 14) return null
        return ClusterControlsPacket(
            volumeToSet = data[0].toInt() and 0x0F,
            callAccept = data[1].toInt() and 0xFF,
            callReject = data[2].toInt() and 0xFF,
            callRejectWithSms = data[3].toInt() and 0xFF,
            pagePlaylist = data[4].toInt() and 0x3F,
            takeMeHome = data[5].toInt() and 0xFF,
            resumeSong = data[6].toInt() and 0xFF,
            pauseSong = data[7].toInt() and 0xFF,
            skipToNext = data[8].toInt() and 0xFF,
            skipToPrev = data[9].toInt() and 0xFF,
            stopSong = data[10].toInt() and 0xFF,
            missedCallGet = data[11].toInt() and 0xFF,
            alertGet = data[12].toInt() and 0xFF,
            launchMediaPlayer = data[13].toInt() and 0xFF
        )
    }
}
