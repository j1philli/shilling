package finance.shilling.shared.data.sync

private val relayCandidateType = Regex("(?:^|\\s)typ\\s+relay(?:\\s|$)", RegexOption.IGNORE_CASE)
private val relaySdpLine = Regex("(?im)^a=candidate:[^\\r\\n]*\\btyp\\s+relay\\b\\r?\\n?")

internal fun isRelayCandidate(candidate: String): Boolean = relayCandidateType.containsMatchIn(candidate)

internal fun withoutRelayCandidates(sdp: String): String = relaySdpLine.replace(sdp, "")
