package com.itantra.translation

/**
 * Decides, on the RECEIVING phone, whether an incoming message is spoken as it arrived or first
 * translated into this phone's own language.
 *
 * Each phone has one language preference: the language its user speaks and wants to hear. A sender
 * transmits text in ITS language; every receiver converts to ITS OWN, so one broadcast can reach
 * phones with different preferences and the sender never needs to know them.
 */
object ReceiverLanguagePolicy {
    enum class Plan { SPEAK_AS_IS, TRANSLATE }

    /**
     * - Emergency/alert messages are NEVER translated (protocol rule: SOS must not depend on any
     *   translation model, and must not be delayed or altered by one).
     * - Same language, or an unknown sender language: nothing to do.
     * - Otherwise translate into [myLanguage].
     */
    fun plan(packetLanguage: String, myLanguage: String, isEmergency: Boolean): Plan = when {
        isEmergency -> Plan.SPEAK_AS_IS
        packetLanguage.isBlank() || myLanguage.isBlank() -> Plan.SPEAK_AS_IS
        packetLanguage.equals(myLanguage, ignoreCase = true) -> Plan.SPEAK_AS_IS
        else -> Plan.TRANSLATE
    }
}
