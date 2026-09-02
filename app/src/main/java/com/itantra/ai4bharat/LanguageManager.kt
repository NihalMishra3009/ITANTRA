package com.itantra.ai4bharat

import com.itantra.stt.SupportedLanguage

/**
 * Language profile mapping the 10 iTantra languages to their on-device model
 * artifacts. STT uses ONE Whisper multilingual model for all languages; TTS
 * uses a per-language sherpa-onnx VITS model bundled as
 * `assets/models/tts/vits_<lang>/model.onnx` (+ tokens.txt).
 */
data class IndicLanguageProfile(
    val language: SupportedLanguage,
    val scriptName: String,
    val isoCode: String,
    val sttModelType: String,      // "whisper" (shared, all langs)
    val ttsModelDir: String,       // "models/tts/vits_<lang>"
    val whisperLanguageCode: String,
    val ttsAvailable: Boolean,
    val sampleEmergencyPhrases: List<String>
)

object LanguageManager {

    private val profiles = mapOf(
        "hi" to IndicLanguageProfile(SupportedLanguage.HINDI, "Devanagari", "hin", "whisper", "models/tts/vits_hi", "hi", false,
            listOf("मुझे मदद चाहिए", "तुरंत सहायता भेजें", "स्थान सुरक्षित है", "दवाइयों की आवश्यकता है")),
        "mr" to IndicLanguageProfile(SupportedLanguage.MARATHI, "Devanagari", "mar", "whisper", "models/tts/vits_mr", "mr", false,
            listOf("मला मदतीची गरज आहे", "तातडीने मदत पाठवा", "जागा सुरक्षित आहे", "औषधांची गरज आहे")),
        "bn" to IndicLanguageProfile(SupportedLanguage.BENGALI, "Bengali", "ben", "whisper", "models/tts/vits_bn", "bn", true,
            listOf("আমার সাহায্য প্রয়োজন", "অবিলম্বে সাহায্য পাঠান", "স্থান নিরাপদ আছে", "ওষুধের প্রয়োজন")),
        "gu" to IndicLanguageProfile(SupportedLanguage.GUJARATI, "Gujarati", "guj", "whisper", "models/tts/vits_gu", "gu", false,
            listOf("મને મદદ જોઈએ છે", "તાત્કાલિક સહાય મોકલો", "સ્થાન સુરક્ષિત છે", "દવાઓની જરૂર છે")),
        "or" to IndicLanguageProfile(SupportedLanguage.ODIA, "Odia", "ori", "whisper", "models/tts/vits_or", "or", false,
            listOf("ମୋତେ ସାହାଯ୍ୟ ଦରକାର", "ତୁରନ୍ତ ସାହାଯ୍ୟ ପଠାନ୍ତୁ", "ସ୍ଥାନ ସୁରକ୍ଷିତ ଅଛି", "ଔଷଧ ଆବଶ୍ୟକ")),
        "ta" to IndicLanguageProfile(SupportedLanguage.TAMIL, "Tamil", "tam", "whisper", "models/tts/vits_ta", "ta", false,
            listOf("எனக்கு உதவி தேவை", "உடனடி உதவி அனுப்பவும்", "இடம் பாதுகாப்பாக உள்ளது", "மருந்துகள் தேவை")),
        "te" to IndicLanguageProfile(SupportedLanguage.TELUGU, "Telugu", "tel", "whisper", "models/tts/vits_te", "te", false,
            listOf("నాకు సహాయం కావాలి", "వెంటనే సహాయం పంపండి", "ప్రదేశం సురక్షితంగా ఉంది", "మందులు అవసరం")),
        "kn" to IndicLanguageProfile(SupportedLanguage.KANNADA, "Kannada", "kan", "whisper", "models/tts/vits_kn", "kn", false,
            listOf("ನನಗೆ ಸಹಾಯ ಬೇಕು", "ತಕ್ಷಣ ಸಹಾಯ ಕಳುಹಿಸಿ", "ಸ್ಥಳ ಸುರಕ್ಷಿತವಾಗಿದೆ", "ಔಷಧಿಗಳ ಅವಶ್ಯಕತೆಯಿದೆ")),
        "ml" to IndicLanguageProfile(SupportedLanguage.MALAYALAM, "Malayalam", "mal", "whisper", "models/tts/vits_ml", "ml", false,
            listOf("എനിക്ക് സഹായം വേണം", "ഉടൻ സഹായം അയക്കുക", "സ്ഥലം സുരക്ഷിതമാണ്", "മരുന്നുകൾ ആവശ്യമാണ്")),
        "en" to IndicLanguageProfile(SupportedLanguage.ENGLISH, "Latin", "eng", "whisper", "models/tts/vits_en", "en", false,
            listOf("I need assistance", "Send immediate help", "Location is secure", "Medical supplies required"))
    )

    fun getProfile(langCode: String): IndicLanguageProfile {
        return profiles[langCode.lowercase()] ?: profiles["hi"]!!
    }

    fun getAllSupportedLanguages(): List<SupportedLanguage> {
        return SupportedLanguage.values().toList()
    }
}
