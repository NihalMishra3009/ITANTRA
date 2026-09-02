package com.itantra.ai4bharat

import com.itantra.stt.SupportedLanguage

data class IndicLanguageProfile(
    val language: SupportedLanguage,
    val scriptName: String,
    val isoCode: String,
    val sttModelName: String,
    val ttsModelName: String,
    val sampleEmergencyPhrases: List<String>
)

/**
 * Unified Language Abstraction mapping Indian languages to AI4Bharat model artifacts and lexicons.
 */
object LanguageManager {

    private val profiles = mapOf(
        "hi" to IndicLanguageProfile(
            language = SupportedLanguage.HINDI,
            scriptName = "Devanagari",
            isoCode = "hin",
            sttModelName = "indicconformer_hi_int8.tflite",
            ttsModelName = "indictts_hi_int8.tflite",
            sampleEmergencyPhrases = listOf("मुझे मदद चाहिए", "तुरंत सहायता भेजें", "स्थान सुरक्षित है", "दवाइयों की आवश्यकता है")
        ),
        "mr" to IndicLanguageProfile(
            language = SupportedLanguage.MARATHI,
            scriptName = "Devanagari",
            isoCode = "mar",
            sttModelName = "indicconformer_mr_int8.tflite",
            ttsModelName = "indictts_mr_int8.tflite",
            sampleEmergencyPhrases = listOf("मला मदतीची गरज आहे", "तातडीने मदत पाठवा", "जागा सुरक्षित आहे", "औषधांची गरज आहे")
        ),
        "bn" to IndicLanguageProfile(
            language = SupportedLanguage.BENGALI,
            scriptName = "Bengali",
            isoCode = "ben",
            sttModelName = "indicconformer_bn_int8.tflite",
            ttsModelName = "indictts_bn_int8.tflite",
            sampleEmergencyPhrases = listOf("আমার সাহায্য প্রয়োজন", "অবিলম্বে সাহায্য পাঠান", "স্থান নিরাপদ আছে", "ওষুধের প্রয়োজন")
        ),
        "gu" to IndicLanguageProfile(
            language = SupportedLanguage.GUJARATI,
            scriptName = "Gujarati",
            isoCode = "guj",
            sttModelName = "indicconformer_gu_int8.tflite",
            ttsModelName = "indictts_gu_int8.tflite",
            sampleEmergencyPhrases = listOf("મને મદદ જોઈએ છે", "તાત્કાલિક સહાય મોકલો", "સ્થાન સુરક્ષિત છે", "દવાઓની જરૂર છે")
        ),
        "or" to IndicLanguageProfile(
            language = SupportedLanguage.ODIA,
            scriptName = "Odia",
            isoCode = "ori",
            sttModelName = "indicconformer_or_int8.tflite",
            ttsModelName = "indictts_or_int8.tflite",
            sampleEmergencyPhrases = listOf("ମୋତେ ସାହାଯ୍ୟ ଦରକାର", "ତୁରନ୍ତ ସାହାଯ୍ୟ ପଠାନ୍ତୁ", "ସ୍ଥାନ ସୁରକ୍ଷିତ ଅଛି", "ଔଷଧ ଆବଶ୍ୟକ")
        ),
        "ta" to IndicLanguageProfile(
            language = SupportedLanguage.TAMIL,
            scriptName = "Tamil",
            isoCode = "tam",
            sttModelName = "indicconformer_ta_int8.tflite",
            ttsModelName = "indictts_ta_int8.tflite",
            sampleEmergencyPhrases = listOf("எனக்கு உதவி தேவை", "உடனடி உதவி அனுப்பவும்", "இடம் பாதுகாப்பாக உள்ளது", "மருந்துகள் தேவை")
        ),
        "te" to IndicLanguageProfile(
            language = SupportedLanguage.TELUGU,
            scriptName = "Telugu",
            isoCode = "tel",
            sttModelName = "indicconformer_te_int8.tflite",
            ttsModelName = "indictts_te_int8.tflite",
            sampleEmergencyPhrases = listOf("నాకు సహాయం కావాలి", "వెంటనే సహాయం పంపండి", "ప్రదేశం సురక్షితంగా ఉంది", "మందులు అవసరం")
        ),
        "kn" to IndicLanguageProfile(
            language = SupportedLanguage.KANNADA,
            scriptName = "Kannada",
            isoCode = "kan",
            sttModelName = "indicconformer_kn_int8.tflite",
            ttsModelName = "indictts_kn_int8.tflite",
            sampleEmergencyPhrases = listOf("ನನಗೆ ಸಹಾಯ ಬೇಕು", "ತಕ್ಷಣ ಸಹಾಯ ಕಳುಹಿಸಿ", "ಸ್ಥಳ ಸುರಕ್ಷಿತವಾಗಿದೆ", "ಔಷಧಿಗಳ ಅವಶ್ಯಕತೆಯಿದೆ")
        ),
        "ml" to IndicLanguageProfile(
            language = SupportedLanguage.MALAYALAM,
            scriptName = "Malayalam",
            isoCode = "mal",
            sttModelName = "indicconformer_ml_int8.tflite",
            ttsModelName = "indictts_ml_int8.tflite",
            sampleEmergencyPhrases = listOf("എനിക്ക് സഹായം വേണം", "ഉടൻ സഹായം അയക്കുക", "സ്ഥലം സുരക്ഷിതമാണ്", "മരുന്നുകൾ ആവശ്യമാണ്")
        ),
        "en" to IndicLanguageProfile(
            language = SupportedLanguage.ENGLISH,
            scriptName = "Latin",
            isoCode = "eng",
            sttModelName = "indicconformer_en_int8.tflite",
            ttsModelName = "indictts_en_int8.tflite",
            sampleEmergencyPhrases = listOf("I need assistance", "Send immediate help", "Location is secure", "Medical supplies required")
        )
    )

    fun getProfile(langCode: String): IndicLanguageProfile {
        return profiles[langCode.lowercase()] ?: profiles["hi"]!!
    }

    fun getAllSupportedLanguages(): List<SupportedLanguage> {
        return SupportedLanguage.values().toList()
    }
}
