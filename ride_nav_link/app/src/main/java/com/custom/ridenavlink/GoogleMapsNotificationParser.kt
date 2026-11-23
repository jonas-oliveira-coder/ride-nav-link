package com.custom.ridenavlink

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class NavProvider(
    val packageName: String,
    val displayName: String,
    val hybridDisplayName: String
) {
    GOOGLE_MAPS("com.google.android.apps.maps", "Google Maps", "Maps + GPS"),
    OPEN_STREET_MAP("org.openstreetmap", "OpenStreetMap", "OSM + GPS");

    companion object {
        fun fromPackage(pkg: String?): NavProvider? {
            if (pkg.isNullOrBlank()) return null
            return values().firstOrNull { it.packageName == pkg }
        }
    }
}

data class ParsedMapsNavigation(
    val distanceMeters: Int,
    val totalDistanceMeters: Int,
    val maneuverCode: Byte,
    val solidManeuverCode: Byte,
    val roundaboutExit: Int,
    val etaSeconds: Int,
    val etaHours: Int,
    val etaMinutes: Int,
    val isAm: Boolean?,
    val displayStreetText: String,
    val isTurnDistanceExact: Boolean,
    val isAmbientStatusOnly: Boolean,
    val isArrival: Boolean = false,
    val isRerouting: Boolean = false
)

data class ParsedSubTextInfo(
    val totalDistanceMeters: Int?,
    val etaSeconds: Int,
    val etaHours: Int,
    val etaMinutes: Int,
    val isAm: Boolean?
)

object GoogleMapsNotificationParser {

    private val PURE_DISTANCE_ONLY_REGEX =
        "^\\s*(?:em\\s+|in\\s+)?[0-9]+(?:[.,][0-9]+)?\\s*(?:km|m|mi|ft|yd)(?![\\p{L}\\p{N}])\\s*$".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val DISTANCE_REGEX =
        "([0-9]+(?:[.,][0-9]+)?)\\s*(km|m|mi|ft|yd)(?![\\p{L}\\p{N}])".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val CLOCK_ETA_REGEX =
        "(?<!\\d)([0-2]?\\d):([0-5]\\d)(?:\\s*(am|pm))?(?![\\p{L}\\p{N}])".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val DURATION_HM_REGEX =
        "(?<!\\d)(\\d+)\\s*h(?:oras?|rs?)?(?:\\s*(\\d+)\\s*min)?(?![\\p{L}\\p{N}])".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val DURATION_MIN_REGEX =
        "(?<!\\d)(\\d+)\\s*min(?:utos?)?(?![\\p{L}\\p{N}])".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val DURATION_SEC_REGEX =
        "(?<!\\d)(\\d+)\\s*s(?:ec|eg)?(?![\\p{L}\\p{N}])".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val ROUNDABOUT_NUMERIC_PT_REGEX =
        "([1-7])\\s*(?:\\.?\\s*[aª°º])?\\s*saida(?![\\p{L}\\p{N}])".toRegex(RegexOption.IGNORE_CASE)

    private val ROUNDABOUT_SAIDA_NUM_REGEX =
        "saida\\s*(?:n[oº.]?\\s*)?([1-7])(?![\\p{L}\\p{N}])".toRegex(RegexOption.IGNORE_CASE)

    private val ROUNDABOUT_ORDINAL_SHORT_PT_REGEX =
        "\\b(?:pegue|pegar|tome|tomar|use|usar|entre|saia|sair)\\s+(?:a|na|pela)\\s+([1-7])\\s*\\.?\\s*[aª°º](?![\\p{L}\\p{N}])".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val ROUNDABOUT_NUMERIC_EN_REGEX =
        "([1-7])\\s*(?:st|nd|rd|th)?\\s*exit(?![\\p{L}\\p{N}])".toRegex(RegexOption.IGNORE_CASE)

    private val ROUNDABOUT_EXIT_NUM_REGEX =
        "exit\\s*(?:no\\.?\\s*|number\\s*)?([1-7])(?![\\p{L}\\p{N}])".toRegex(RegexOption.IGNORE_CASE)

    private val PARENTHETICAL_REGEX = "\\([^)]*\\)".toRegex()

    private val LEADING_DISTANCE_PREFIX_REGEX =
        "^(?:(?:em|in)\\s+)?[0-9]+(?:[.,][0-9]+)?\\s*(?:km|m|mi|ft|yd)(?![\\p{L}\\p{N}])[,:·•|\\s-]+".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val TRAILING_HINT_REGEX =
        "(?:\\bvoce vera\\b|\\byou will see\\b|\\bdepois de\\b|\\bapos\\b|\\bpassando por\\b|\\bafter\\b|\\bpast\\b).*$".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val TRAILING_STREET_CLAUSE_REGEX =
        "(?<=.)\\s+(?:em direcao a|para entrar na|para acessar|para pegar|para|onto|toward|towards)\\s+.*$".toRegex(
            RegexOption.IGNORE_CASE
        )

    private val TRAILING_ON_STREET_REGEX =
        "(?<=.)\\s+(?:na|no|nas|nos|on)\\s+(?!(?:(?:the|a|o)\\s+)?(?:rotatoria|rotula|roundabout|traffic circle|bifurc|fork|saida|alca|rampa|balsa|ferry|retorno|contramao|faixa|esquerda|direita|left|right)\\b).*$".toRegex(
            RegexOption.IGNORE_CASE
        )

    fun normalizeForMatch(input: String): String {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.US)
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun extractPrimaryActionClause(norm: String): String {
        if (norm.isBlank()) return ""
        val firstLine = norm.split('\n', '•', '|')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(it) }
            ?: norm
        var clause = PARENTHETICAL_REGEX.replace(firstLine, " ")
        clause = clause.replace("\\s+".toRegex(), " ").trim()
        clause = LEADING_DISTANCE_PREFIX_REGEX.replace(clause, "").trim()
        clause = TRAILING_HINT_REGEX.replace(clause, "").trim()
        clause = TRAILING_STREET_CLAUSE_REGEX.replace(clause, "").trim()
        clause = TRAILING_ON_STREET_REGEX.replace(clause, "").trim()
        return clause.ifBlank { norm }
    }

    private fun stripParentheticalHints(norm: String): String {
        if (norm.isBlank()) return ""
        return PARENTHETICAL_REGEX.replace(norm, " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun isEtaSummaryField(raw: String): Boolean {
        if (raw.isBlank()) return false
        val norm = normalizeForMatch(raw)
        val hasClockOrDuration = CLOCK_ETA_REGEX.containsMatchIn(norm) ||
            DURATION_HM_REGEX.containsMatchIn(norm) ||
            DURATION_MIN_REGEX.containsMatchIn(norm)
        return hasClockOrDuration && !PURE_DISTANCE_ONLY_REGEX.matches(norm)
    }

    private fun cleanStreetDisplayText(raw: String): String {
        if (raw.isBlank()) return ""
        val firstChunk = raw.split('\n', '•', '|')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(it) }
            ?: raw.trim()
        return LEADING_DISTANCE_PREFIX_REGEX.replace(firstChunk, "").trim().ifBlank { firstChunk }
    }

    fun isAmbientSummaryOnly(
        title: String,
        text: String,
        bigText: String
    ): Boolean {
        val combined = normalizeForMatch("$title $text $bigText")
        if (combined.isEmpty()) return true
        val ambientPhrases = listOf(
            "navegacao em andamento",
            "navegando",
            "navigating",
            "navigation in progress",
            "toque para retornar a navegacao",
            "tap to return to navigation",
            "aguardando gps",
            "waiting for gps",
            "sem sinal de gps",
            "searching for gps"
        )
        return ambientPhrases.any { combined == it || combined.contains(it) } &&
            parseDistanceMeters("$title $text $bigText") == null &&
            detectSolidManeuver(combined) == null
    }

    private fun mergeSubTextFallback(
        primary: ParsedSubTextInfo,
        bigText: String,
        text: String,
        textLines: List<String>
    ): ParsedSubTextInfo {
        var mergedTotal = primary.totalDistanceMeters
        var mergedEtaSec = primary.etaSeconds
        var mergedEtaH = primary.etaHours
        var mergedEtaM = primary.etaMinutes
        var mergedIsAm = primary.isAm

        val extraCandidates = mutableListOf<String>()
        // Include all text lines (including line 0)
        extraCandidates.addAll(textLines)
        for (raw in listOf(bigText, text)) {
            if (raw.isNotBlank()) {
                extraCandidates.add(raw)
                val parts = raw.split('\n', '•', '|').map { it.trim() }.filter { it.isNotEmpty() }
                // Include all parts so remaining distance is preserved when supplied in first chunk
                extraCandidates.addAll(parts)
            }
        }

        for (candidate in extraCandidates) {
            val parsedCand = parseSubText(candidate)
            if (mergedTotal == null && parsedCand.totalDistanceMeters != null) {
                mergedTotal = parsedCand.totalDistanceMeters
            }
            if (mergedEtaSec < 0 && parsedCand.etaSeconds >= 0) {
                mergedEtaSec = parsedCand.etaSeconds
            }
            if (mergedEtaH < 0 && parsedCand.etaHours >= 0) {
                mergedEtaH = parsedCand.etaHours
                mergedEtaM = parsedCand.etaMinutes
                mergedIsAm = parsedCand.isAm
            }
        }

        return ParsedSubTextInfo(
            totalDistanceMeters = mergedTotal,
            etaSeconds = mergedEtaSec,
            etaHours = mergedEtaH,
            etaMinutes = mergedEtaM,
            isAm = mergedIsAm
        )
    }

    fun parse(
        title: String,
        text: String,
        subText: String = "",
        bigText: String = "",
        textLines: List<String> = emptyList(),
        infoText: String = "",
        fallbackTurnDistanceMeters: Int? = null,
        fallbackTotalDistanceMeters: Int? = null,
        fallbackSolidManeuver: Byte? = null,
        fallbackRoundaboutExit: Int = 0,
        provider: NavProvider = NavProvider.GOOGLE_MAPS,
        roundaboutCounterClockwise: Boolean = true,
        iconManeuverCode: Byte? = null,
        iconRoundaboutExit: Int = 0
    ): ParsedMapsNavigation? {
        val cleanTitle = title.trim()
        val cleanText = text.trim()
        val cleanBigText = bigText.trim()
        val cleanSubText = subText.trim().ifEmpty { infoText.trim() }
        val cleanLines = textLines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

        val subInfo = mergeSubTextFallback(
            primary = parseSubText(cleanSubText),
            bigText = cleanBigText,
            text = cleanText,
            textLines = textLines.map { it.trim() }.filter { it.isNotEmpty() }
        )

        // Next-maneuver distance priority:
        // 1) pure distance field (e.g., title "300 m") or non-ETA title
        // 2) distance outside parenthetical hints (avoids "(right in 950 m)")
        // 3) full text fallback in title -> bigText -> text -> textLines order
        val titleForTurnDist = if (isEtaSummaryField(cleanTitle)) "" else cleanTitle
        val bigTextForTurnDist = if (isEtaSummaryField(cleanBigText)) "" else cleanBigText
        val textForTurnDist = if (isEtaSummaryField(cleanText)) "" else cleanText

        val parsedTurnDist = parseDistanceMeters(titleForTurnDist)
            ?: parseDistanceMeters(stripParentheticalHints(bigTextForTurnDist))
            ?: parseDistanceMeters(stripParentheticalHints(textForTurnDist))
            ?: parseDistanceMeters(bigTextForTurnDist)
            ?: parseDistanceMeters(textForTurnDist)
            ?: parseDistanceMeters(cleanLines)

        val instructionParts = listOf(cleanBigText, cleanText, cleanTitle, cleanLines)
            .filter { it.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(it) && !isEtaSummaryField(it) }
        val maneuverSourceRaw = if (instructionParts.isNotEmpty()) {
            instructionParts.joinToString("\n")
        } else {
            listOf(cleanTitle, cleanBigText, cleanText, cleanLines).filter { it.isNotEmpty() }.joinToString(" ")
        }
        val maneuverSourceNorm = normalizeForMatch(maneuverSourceRaw)

        val textManeuver = detectSolidManeuver(maneuverSourceNorm, roundaboutCounterClockwise)
        val iconIsRoundabout = iconManeuverCode == BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID ||
            iconManeuverCode == BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
        val textIsRoundabout = textManeuver == BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID ||
            textManeuver == BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
        val prelimManeuver = when {
            textIsRoundabout && !iconIsRoundabout -> textManeuver
            iconIsRoundabout && !textIsRoundabout && textManeuver != null -> textManeuver
            textManeuver == BajajProtocol.ARROW_DESTINATION_SOLID -> BajajProtocol.ARROW_DESTINATION_SOLID
            textManeuver == BajajProtocol.ARROW_UTURN_LEFT_SOLID ||
                textManeuver == BajajProtocol.ARROW_UTURN_RIGHT_SOLID -> textManeuver
            textManeuver == BajajProtocol.ARROW_SHARP_LEFT_SOLID ||
                textManeuver == BajajProtocol.ARROW_SHARP_RIGHT_SOLID -> textManeuver
            else -> iconManeuverCode ?: textManeuver
        }
        val isExplicitArrival = isExplicitArrivalPhrase(
            maneuverSourceNorm,
            parsedTurnDist,
            subInfo.totalDistanceMeters,
            prelimManeuver
        )
        val isExplicitReroute = textManeuver == BajajProtocol.ARROW_WRONG_WAY
        val detectedManeuver = when {
            isExplicitArrival -> BajajProtocol.ARROW_DESTINATION_SOLID
            isExplicitReroute -> BajajProtocol.ARROW_WRONG_WAY
            else -> prelimManeuver
        }

        val isRerouting = detectedManeuver == BajajProtocol.ARROW_WRONG_WAY && (
            maneuverSourceNorm.contains("recalculando") ||
                maneuverSourceNorm.contains("rerouting") ||
                maneuverSourceNorm.contains("procurando gps") ||
                maneuverSourceNorm.contains("searching for gps")
            )

        val isAmbient = !isExplicitArrival && detectedManeuver == null && parsedTurnDist == null && subInfo.totalDistanceMeters == null

        if (isAmbient && fallbackSolidManeuver == null) {
            return null
        }

        val resolvedTurnDist = if (isExplicitArrival) {
            0
        } else {
            parsedTurnDist
                ?: fallbackTurnDistanceMeters
                ?: subInfo.totalDistanceMeters
                ?: 1000
        }

        val resolvedSolidManeuver = when {
            isExplicitArrival -> BajajProtocol.ARROW_DESTINATION_SOLID
            detectedManeuver != null -> {
                // Keep straight arrow above 3 km on long stretches; show upcoming turn within 3 km
                if (detectedManeuver != BajajProtocol.ARROW_STRAIGHT_SOLID && resolvedTurnDist > 3000) {
                    BajajProtocol.ARROW_STRAIGHT_SOLID
                } else {
                    detectedManeuver
                }
            }
            else -> {
                // If Maps sent no new instruction (e.g. only street name on long straight):
                // Keep previous curve maneuver if distance is still decreasing within 3 km
                if (fallbackSolidManeuver != null && parsedTurnDist != null &&
                    fallbackTurnDistanceMeters != null && parsedTurnDist <= fallbackTurnDistanceMeters &&
                    parsedTurnDist <= 3000
                ) {
                    fallbackSolidManeuver
                } else {
                    BajajProtocol.ARROW_STRAIGHT_SOLID
                }
            }
        }

        val resolvedTotalDist = if (isExplicitArrival) {
            0
        } else {
            subInfo.totalDistanceMeters
                ?: fallbackTotalDistanceMeters
                ?: resolvedTurnDist
        }

        val safeTotalDist = if (isExplicitArrival) 0 else max(resolvedTurnDist, resolvedTotalDist)

        val isRoundabout = resolvedSolidManeuver == BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID ||
            resolvedSolidManeuver == BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID

        val resolvedRoundaboutExit = if (isRoundabout) {
            // Prioritize the geometric exit spoke (1..7) extracted from the Maps notification icon,
            // because the Bajaj cluster LCD uses 1..7 as 45° angular positions around the ring (4 = straight),
            // and the icon works identically across all languages.
            val primaryExit = parseRoundaboutExit(maneuverSourceNorm, inferIfMissing = false)
            val explicitOrdinal = if (primaryExit in 1..7) {
                primaryExit
            } else {
                parseRoundaboutExit(normalizeForMatch(cleanSubText), inferIfMissing = false)
            }
            when {
                iconRoundaboutExit in 1..7 -> iconRoundaboutExit
                explicitOrdinal in 1..7 -> mapOrdinalExitToClusterSpoke(explicitOrdinal)
                fallbackSolidManeuver == resolvedSolidManeuver && fallbackRoundaboutExit in 1..7 -> fallbackRoundaboutExit
                else -> {
                    val inferred = parseRoundaboutExit(maneuverSourceNorm, inferIfMissing = true)
                    if (inferred in 1..7) inferred else 4
                }
            }
        } else {
            0
        }

        val finalManeuverCode = if (isExplicitArrival) {
            BajajProtocol.ARROW_DESTINATION_BLINK
        } else {
            BajajProtocol.maneuverForDistance(resolvedSolidManeuver, resolvedTurnDist)
        }

        val streetCandidate = when {
            isExplicitArrival -> {
                val cand = when {
                    cleanBigText.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(cleanBigText) -> cleanStreetDisplayText(cleanBigText)
                    cleanText.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(cleanText) -> cleanStreetDisplayText(cleanText)
                    cleanTitle.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(cleanTitle) -> cleanStreetDisplayText(cleanTitle)
                    else -> "Você chegou ao destino"
                }
                cand
            }
            cleanBigText.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(cleanBigText) && !isEtaSummaryField(cleanBigText) -> cleanStreetDisplayText(cleanBigText)
            cleanText.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(cleanText) && !isEtaSummaryField(cleanText) -> cleanStreetDisplayText(cleanText)
            cleanTitle.isNotEmpty() && !PURE_DISTANCE_ONLY_REGEX.matches(cleanTitle) && !isEtaSummaryField(cleanTitle) -> cleanStreetDisplayText(cleanTitle)
            cleanLines.isNotEmpty() -> cleanStreetDisplayText(cleanLines)
            cleanTitle.isNotEmpty() -> cleanStreetDisplayText(cleanTitle)
            else -> provider.displayName
        }

        return ParsedMapsNavigation(
            distanceMeters = resolvedTurnDist,
            totalDistanceMeters = safeTotalDist,
            maneuverCode = finalManeuverCode,
            solidManeuverCode = resolvedSolidManeuver,
            roundaboutExit = resolvedRoundaboutExit,
            etaSeconds = if (isExplicitArrival) 0 else subInfo.etaSeconds,
            etaHours = subInfo.etaHours,
            etaMinutes = subInfo.etaMinutes,
            isAm = subInfo.isAm,
            displayStreetText = streetCandidate,
            isTurnDistanceExact = parsedTurnDist != null || isExplicitArrival,
            isAmbientStatusOnly = !isExplicitArrival && detectedManeuver == null && parsedTurnDist == null,
            isArrival = isExplicitArrival,
            isRerouting = isRerouting
        )
    }

    private fun isExplicitArrivalPhrase(
        norm: String,
        parsedTurnDist: Int?,
        totalDist: Int?,
        detectedManeuver: Byte?
    ): Boolean {
        if (norm.isBlank()) return false

        val strongArrivalPhrases = listOf(
            "voce chegou",
            "chegou ao destino",
            "chegou ao seu destino",
            "chegou a seu destino",
            "chegou no destino",
            "chegou no seu destino",
            "chegada ao destino",
            "destino alcancado",
            "destino atingido",
            "you have arrived",
            "you have reached",
            "arrived at",
            "reached your destination",
            "reached destination"
        )
        if ((parsedTurnDist == null || parsedTurnDist <= 60) && strongArrivalPhrases.any { norm.contains(it) }) {
            return true
        }

        val positionalArrivalPhrases = listOf(
            "o destino esta",
            "o destino estara",
            "destino estara",
            "o seu destino esta",
            "o seu destino estara",
            "seu destino esta",
            "seu destino estara",
            "o destino fica",
            "destino fica",
            "o seu destino fica",
            "seu destino fica",
            "destino a direita",
            "destino a esquerda",
            "destino a frente",
            "destination is on",
            "destination is ahead",
            "your destination is"
        )
        if ((parsedTurnDist == null || parsedTurnDist <= 60) &&
            (positionalArrivalPhrases.any { norm.contains(it) } || "\\bchegou\\b".toRegex().containsMatchIn(norm))
        ) {
            return true
        }

        return detectedManeuver == BajajProtocol.ARROW_DESTINATION_SOLID &&
            (parsedTurnDist == null || parsedTurnDist <= 50) &&
            (totalDist == null || totalDist <= 80)
    }

    fun parseDistanceMeters(raw: String): Int? {
        if (raw.isBlank()) return null
        val norm = normalizeForMatch(raw)
        val match = DISTANCE_REGEX.find(norm) ?: return null
        val rawNum = match.groupValues[1]
        val unit = match.groupValues[2].lowercase(Locale.US)

        val numericValue = if (unit == "m" && rawNum.matches("\\d{1,3}\\.\\d{3}".toRegex())) {
            rawNum.replace(".", "").toDoubleOrNull()
        } else {
            rawNum.replace(',', '.').toDoubleOrNull()
        } ?: return null

        val meters = when (unit) {
            "km" -> (numericValue * 1000.0).roundToInt()
            "mi" -> (numericValue * 1609.34).roundToInt()
            "yd" -> (numericValue * 0.9144).roundToInt()
            "ft" -> (numericValue * 0.3048).roundToInt()
            else -> numericValue.roundToInt()
        }
        return meters.coerceAtLeast(0)
    }

    fun parseSubText(subText: String): ParsedSubTextInfo {
        if (subText.isBlank()) {
            return ParsedSubTextInfo(
                totalDistanceMeters = null,
                etaSeconds = -1,
                etaHours = -1,
                etaMinutes = -1,
                isAm = null
            )
        }

        val norm = normalizeForMatch(subText)
        val totalDist = parseDistanceMeters(subText)

        var etaSeconds = -1
        val hmMatch = DURATION_HM_REGEX.find(norm)
        if (hmMatch != null) {
            val h = hmMatch.groupValues[1].toIntOrNull() ?: 0
            val m = hmMatch.groupValues[2].toIntOrNull() ?: 0
            etaSeconds = (h * 3600) + (m * 60)
        } else {
            val minMatch = DURATION_MIN_REGEX.find(norm)
            if (minMatch != null) {
                val m = minMatch.groupValues[1].toIntOrNull() ?: 0
                etaSeconds = m * 60
            } else {
                val secMatch = DURATION_SEC_REGEX.find(norm)
                if (secMatch != null) {
                    etaSeconds = secMatch.groupValues[1].toIntOrNull() ?: -1
                }
            }
        }

        var etaHours = -1
        var etaMinutes = -1
        var isAm: Boolean? = null

        val clockMatch = CLOCK_ETA_REGEX.find(norm)
        if (clockMatch != null) {
            val rawHour = clockMatch.groupValues[1].toIntOrNull()
            val rawMin = clockMatch.groupValues[2].toIntOrNull()
            val amPm = clockMatch.groupValues[3].lowercase(Locale.US)
            if (rawHour != null && rawMin != null && rawMin in 0..59) {
                if (amPm == "am" || amPm == "pm") {
                    val h12 = rawHour.coerceIn(1, 12) % 12
                    isAm = (amPm == "am")
                    etaHours = if (isAm == true) h12 else h12 + 12
                    etaMinutes = rawMin
                } else if (rawHour in 0..23) {
                    isAm = rawHour < 12
                    etaHours = rawHour
                    etaMinutes = rawMin
                }
            }
        }

        return ParsedSubTextInfo(
            totalDistanceMeters = totalDist,
            etaSeconds = etaSeconds,
            etaHours = etaHours,
            etaMinutes = etaMinutes,
            isAm = isAm
        )
    }

    private fun findExplicitRoundaboutExit(text: String): Int {
        if (text.isBlank()) return 0

        ROUNDABOUT_NUMERIC_PT_REGEX.find(text)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceIn(1, 7) ?: 0
        }
        ROUNDABOUT_SAIDA_NUM_REGEX.find(text)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceIn(1, 7) ?: 0
        }
        ROUNDABOUT_ORDINAL_SHORT_PT_REGEX.find(text)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceIn(1, 7) ?: 0
        }
        ROUNDABOUT_NUMERIC_EN_REGEX.find(text)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceIn(1, 7) ?: 0
        }
        ROUNDABOUT_EXIT_NUM_REGEX.find(text)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceIn(1, 7) ?: 0
        }

        val ordinalPairs = listOf(
            "primeira saida" to 1,
            "segunda saida" to 2,
            "terceira saida" to 3,
            "quarta saida" to 4,
            "quinta saida" to 5,
            "sexta saida" to 6,
            "setima saida" to 7,
            "first exit" to 1,
            "second exit" to 2,
            "third exit" to 3,
            "fourth exit" to 4,
            "fifth exit" to 5,
            "sixth exit" to 6,
            "seventh exit" to 7
        )
        for ((phrase, exitNum) in ordinalPairs) {
            if (text.contains(phrase)) return exitNum
        }
        return 0
    }

    fun mapOrdinalExitToClusterSpoke(ordinalExit: Int): Int {
        // Maps standard 4-way roundabout ordinal exit (1st, 2nd, 3rd, 4th+)
        // to Bajaj's 7-spoke angular LCD wheel (2=90° right, 4=0° straight, 6=270° left, 7=225° U-turn)
        return when (ordinalExit) {
            1 -> 2
            2 -> 4
            3 -> 6
            in 4..7 -> 7
            else -> 4
        }
    }

    fun parseRoundaboutExit(normText: String, inferIfMissing: Boolean = false): Int {
        if (normText.isBlank()) return if (inferIfMissing) 4 else 0

        val primaryText = stripParentheticalHints(normText)
        val explicitPrimary = findExplicitRoundaboutExit(primaryText)
        val explicit = if (explicitPrimary in 1..7) explicitPrimary else findExplicitRoundaboutExit(normText)

        if (explicit in 1..7) {
            return explicit
        }

        if (!inferIfMissing) return 0

        val clause = extractPrimaryActionClause(normText)
        return when {
            clause.contains("em frente") ||
                clause.contains("continue straight") ||
                clause.contains("go straight") ||
                clause.contains("straight") ||
                clause.contains("reto") ||
                clause.contains("recto") ||
                clause.contains("tout droit") ||
                clause.contains("geradeaus") -> 4
            clause.contains("retorno") ||
                clause.contains("meia-volta") ||
                clause.contains("meia volta") ||
                clause.contains("u-turn") ||
                clause.contains("u turn") -> 7
            (clause.contains("leve") || clause.contains("suave") || clause.contains("slight")) &&
                (clause.contains("direita") || clause.contains("right")) -> 3
            (clause.contains("leve") || clause.contains("suave") || clause.contains("slight")) &&
                (clause.contains("esquerda") || clause.contains("left")) -> 5
            (clause.contains("acentuada") || clause.contains("fechada") || clause.contains("sharp")) &&
                (clause.contains("direita") || clause.contains("right")) -> 1
            (clause.contains("acentuada") || clause.contains("fechada") || clause.contains("sharp")) &&
                (clause.contains("esquerda") || clause.contains("left")) -> 7
            clause.contains("esquerda") || clause.contains("left") || clause.contains("izquierda") || clause.contains("gauche") || clause.contains("links") -> 6
            clause.contains("direita") || clause.contains("right") || clause.contains("derecha") || clause.contains("droite") || clause.contains("rechts") -> 2
            else -> 4
        }
    }

    fun detectSolidManeuver(norm: String, defaultCounterClockwise: Boolean = true): Byte? {
        if (norm.isBlank()) return null

        val primary = extractPrimaryActionClause(norm)
        detectInClause(primary, defaultCounterClockwise)?.let { return it }

        val withoutParens = stripParentheticalHints(norm)
        if (withoutParens != primary) {
            detectInClause(withoutParens, defaultCounterClockwise)?.let { return it }
        }

        return detectInClause(norm, defaultCounterClockwise)
    }

    private fun detectInClause(text: String, defaultCounterClockwise: Boolean = true): Byte? {
        if (text.isBlank()) return null

        val hasLeft = "\\besquerda\\b|\\bleft\\b".toRegex().containsMatchIn(text)
        val hasRight = "\\bdireita\\b|\\bright\\b".toRegex().containsMatchIn(text)

        return when {
            text.contains("recalculando") ||
                text.contains("rerouting") ||
                text.contains("contramao") ||
                text.contains("wrong way") -> BajajProtocol.ARROW_WRONG_WAY

            text.contains("chegou") ||
                text.contains("destino") ||
                text.contains("destination") ||
                text.contains("arrived") ||
                text.contains("arriving") -> BajajProtocol.ARROW_DESTINATION_SOLID

            (text.contains("trem") || text.contains("comboio") || text.contains("train")) &&
                (text.contains("balsa") || text.contains("ferry") || text.contains("embarque") || text.contains("shuttle")) ->
                BajajProtocol.ARROW_FERRY_TRAIN_SOLID

            text.contains("ferry train") || text.contains("shuttle train") ->
                BajajProtocol.ARROW_FERRY_TRAIN_SOLID

            text.contains("balsa") || text.contains("ferry") || text.contains("barca") ->
                BajajProtocol.ARROW_FERRY_SOLID

            text.contains("rotatoria") ||
                text.contains("rotula") ||
                text.contains("rotunda") ||
                text.contains("rotonda") ||
                text.contains("rotonde") ||
                text.contains("rond-point") ||
                text.contains("kreisverkehr") ||
                text.contains("roundabout") ||
                text.contains("traffic circle") -> {
                val explicitClockwise = text.contains("horario") ||
                    text.contains("mao inglesa")
                if (explicitClockwise || !defaultCounterClockwise) {
                    BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID
                } else {
                    BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
                }
            }

            text.contains("retorno") ||
                "\\bretorne\\b".toRegex().containsMatchIn(text) ||
                text.contains("meia-volta") ||
                text.contains("meia volta") ||
                text.contains("u-turn") ||
                text.contains("u turn") -> {
                if (hasRight && !hasLeft) BajajProtocol.ARROW_UTURN_RIGHT_SOLID
                else BajajProtocol.ARROW_UTURN_LEFT_SOLID
            }

            text.contains("alca") ||
                text.contains("rampa") ||
                text.contains("ramp") ||
                text.contains("pegue a saida") ||
                text.contains("use a saida") ||
                text.contains("entre na saida") ||
                text.contains("saia na") ||
                text.contains("saia a") ||
                text.contains("take exit") ||
                text.contains("take the exit") ||
                text.contains("exit onto") ||
                text.contains("exit toward") -> {
                when {
                    hasLeft && !hasRight -> BajajProtocol.ARROW_RAMP_LEFT_SOLID
                    hasRight -> BajajProtocol.ARROW_RAMP_RIGHT_SOLID
                    else -> BajajProtocol.ARROW_RAMP_RIGHT_SOLID
                }
            }

            text.contains("converg") ||
                text.contains("incorpore") ||
                text.contains("merge") -> BajajProtocol.ARROW_MERGE

            text.contains("bifurc") || text.contains("fork") -> {
                when {
                    hasLeft && !hasRight -> BajajProtocol.ARROW_FORK_LEFT_SOLID
                    hasRight -> BajajProtocol.ARROW_FORK_RIGHT_SOLID
                    else -> BajajProtocol.ARROW_FORK_RIGHT_SOLID
                }
            }

            (text.contains("acentuada") || text.contains("fechada") || text.contains("sharp")) &&
                (hasLeft || hasRight) -> {
                if (hasRight && !hasLeft) BajajProtocol.ARROW_SHARP_RIGHT_SOLID
                else BajajProtocol.ARROW_SHARP_LEFT_SOLID
            }

            (text.contains("leve") || text.contains("suave") || text.contains("slight")) &&
                (hasLeft || hasRight) -> {
                if (hasRight && !hasLeft) BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID
                else BajajProtocol.ARROW_SLIGHT_LEFT_SOLID
            }

            (text.contains("mantenha") || text.contains("fique") || text.contains("faixa") ||
                text.contains("keep") || text.contains("stay") || text.contains("lane")) &&
                (hasLeft || hasRight) -> {
                if (hasRight && !hasLeft) BajajProtocol.ARROW_KEEP_RIGHT_SOLID
                else BajajProtocol.ARROW_KEEP_LEFT_SOLID
            }

            text.contains("saida") && (hasLeft || hasRight) -> {
                if (hasRight && !hasLeft) BajajProtocol.ARROW_RAMP_RIGHT_SOLID
                else BajajProtocol.ARROW_RAMP_LEFT_SOLID
            }

            // Se a instrução for explicitamente seguir em frente / reto (sem instrução de virar),
            // prioriza seguir reto mesmo que o nome da via ou dica mencione "direita" ou "esquerda".
            (text.contains("siga em frente") || text.contains("continue em frente") ||
                text.contains("va em frente") || text.contains("continue reto") ||
                text.contains("siga reto") || text.contains("keep straight") ||
                text.contains("go straight") || text.contains("proceed straight")) &&
                !"\\bvire\\b|\\bturn\\b|\\bcurva\\b|\\bdobre\\b".toRegex().containsMatchIn(text) ->
                BajajProtocol.ARROW_STRAIGHT_SOLID

            hasRight && !hasLeft -> BajajProtocol.ARROW_RIGHT_SOLID
            hasLeft && !hasRight -> BajajProtocol.ARROW_LEFT_SOLID

            text.contains("siga") ||
                text.contains("continue") ||
                text.contains("em frente") ||
                text.contains("va em frente") ||
                text.contains("continue reto") ||
                text.contains("siga reto") ||
                text.contains("keep straight") ||
                text.contains("straight") ||
                text.contains("head ") ||
                text.contains("follow ") ||
                text.contains("proceed") -> BajajProtocol.ARROW_STRAIGHT_SOLID

            else -> null
        }
    }
}
