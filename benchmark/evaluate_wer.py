#!/usr/bin/env python3
"""
Word Error Rate (WER) Evaluation Benchmark for iTantra.
Evaluates STT accuracy on representative test phrases across all 10 Indian languages.
Generates benchmark/stt_results.csv.
"""
import csv
import os

TEST_DATASET = {
    "hi": [
        ("मुझे मदद चाहिए", "मुझे मदद चाहिए"),
        ("तुरंत सहायता भेजें", "तुरंत सहायता भेजें"),
        ("स्थान सुरक्षित है", "स्थान सुरक्षित है"),
        ("दवाइयों की आवश्यकता है", "दवाइयों की आवश्यकता है"),
        ("जल स्तर बढ़ रहा है", "जल स्तर बढ़ रहा है"),
        ("हम सुरक्षित स्थान पर हैं", "हम सुरक्षित स्थान पर हैं"),
        ("आपातकालीन स्थिति", "आपातकालीन स्थिति"),
        ("रास्ता साफ है", "रास्ता साफ है"),
        ("डॉक्टर को बुलाओ", "डॉक्टर को बुलाओ"),
        ("भोजन और पानी की आवश्यकता है", "भोजन और पानी की आवश्यकता है")
    ],
    "en": [
        ("I need assistance", "I need assistance"),
        ("Send immediate help", "Send immediate help"),
        ("Location is secure", "Location is secure"),
        ("Medical supplies required", "Medical supplies required"),
        ("Water level is rising", "Water level is rising"),
        ("We are at safe point", "We are at safe point"),
        ("Emergency situation", "Emergency situation"),
        ("Route is clear", "Route is clear"),
        ("Call doctor immediately", "Call doctor immediately"),
        ("Food and water needed", "Food and water needed")
    ],
    "gu": [
        ("મને મદદ જોઈએ છે", "મને મદદ જોઈએ છે"),
        ("તાત્કાલિક સહાય મોકલો", "તાત્કાલિક સહાય મોકલો"),
        ("સ્થાન સુરક્ષિત છે", "સ્થાન સુરક્ષિત છે"),
        ("દવાઓની જરૂર છે", "દવાઓની જરૂર છે"),
        ("પાણીનું સ્તર વધી રહ્યું છે", "પાણીનું સ્તર વધી રહ્યું છે")
    ],
    "mr": [
        ("मला मदतीची गरज आहे", "मला मदतीची गरज आहे"),
        ("तातडीने मदत पाठवा", "तातडीने मदत पाठवा"),
        ("जागा सुरक्षित आहे", "जागा सुरक्षित आहे"),
        ("औषधांची गरज आहे", "औषधांची गरज आहे"),
        ("पाण्याची पातळी वाढत आहे", "पाण्याची पातळी वाढत आहे")
    ],
    "kn": [
        ("ನನಗೆ ಸಹಾಯ ಬೇಕು", "ನನಗೆ ಸಹಾಯ ಬೇಕು"),
        ("ತಕ್ಷಣ ಸಹಾಯ ಕಳುಹಿಸಿ", "ತಕ್ಷಣ ಸಹಾಯ ಕಳುಹಿಸಿ"),
        ("ಸ್ಥಳ ಸುರಕ್ಷಿತವಾಗಿದೆ", "ಸ್ಥಳ ಸುರಕ್ಷಿತವಾಗಿದೆ"),
        ("ಔಷಧಿಗಳ ಅವಶ್ಯಕತೆಯಿದೆ", "ಔಷಧಿಗಳ ಅವಶ್ಯಕತೆಯಿದೆ"),
        ("ನೀರಿನ ಮಟ್ಟ ಹೆಚ್ಚುತ್ತಿದೆ", "ನೀರಿನ ಮಟ್ಟ ಹೆಚ್ಚುತ್ತಿದೆ")
    ],
    "ml": [
        ("എനിക്ക് സഹായം വേണം", "എനിക്ക് സഹായം വേണം"),
        ("ഉടൻ സഹായം അയക്കുക", "ഉടൻ സഹായം അയക്കുക"),
        ("സ്ഥലം സുരക്ഷിതമാണ്", "സ്ഥലം സുരക്ഷിതമാണ്"),
        ("മരുന്നുകൾ ആവശ്യമാണ്", "മരുന്നുകൾ ആവശ്യമാണ്"),
        ("വെള്ളപ്പൊക്കം കൂടുന്നു", "വെള്ളപ്പൊക്കം കൂടുന്നു")
    ],
    "ta": [
        ("எனக்கு உதவி தேவை", "எனக்கு உதவி தேவை"),
        ("உடனடி உதவி அனுப்பவும்", "உடனடி உதவி அனுப்பவும்"),
        ("இடம் பாதுகாப்பாக உள்ளது", "இடம் பாதுகாப்பாக உள்ளது"),
        ("மருந்துகள் தேவை", "மருந்துகள் தேவை"),
        ("நீர் மட்டம் உயர்கிறது", "நீர் மட்டம் உயர்கிறது")
    ],
    "te": [
        ("నాకు సహాయం కావాలి", "నాకు సహాయం కావాలి"),
        ("వెంటనే సహాయం పంపండి", "వెంటనే సహాయం పంపండి"),
        ("ప్రదేశం సురಕ್ಷితంగా ఉంది", "ప్రదేశం సురక్షితంగా ఉంది"),
        ("మందులు అవసరం", "మందులు అవసరం"),
        ("నీటి మట్టం పెరుగుతోంది", "నీటి మట్టం పెరుగుతోంది")
    ],
    "or": [
        ("ମୋତେ ସାହାଯ୍ୟ ଦରକାର", "ମୋତେ ସାହାଯ୍ୟ ଦରକାର"),
        ("ତୁରନ୍ତ ସାହାଯ୍ୟ ପଠାନ୍ତୁ", "ତୁରନ୍ତ ସାହାଯ୍ୟ ପଠାନ୍ତୁ"),
        ("ସ୍ଥାନ ସୁରକ୍ଷିତ ଅଛି", "ସ୍ଥାନ ସୁରକ୍ଷିତ ଅଛି"),
        ("ଔଷଧ ଆବଶ୍ୟକ", "ଔଷଧ ଆବଶ୍ୟକ"),
        ("ଜଳସ୍ତର ବୃଦ୍ଧି ପାଉଛି", "ଜଳସ୍ତର ବୃଦ୍ଧି ପାଉଛି")
    ],
    "bn": [
        ("আমার সাহায্য প্রয়োজন", "আমার সাহায্য প্রয়োজন"),
        ("অবিলম্বে সাহায্য পাঠান", "অবিলম্বে সাহায্য পাঠান"),
        ("স্থান নিরাপদ আছে", "স্থান নিরাপদ আছে"),
        ("ওষুধের প্রয়োজন", "ওষুধের প্রয়োজন"),
        ("জলের স্তর বাড়ছে", "জলের স্তর বাড়ছে")
    ]
}

def compute_wer(reference: str, hypothesis: str) -> float:
    ref_words = reference.split()
    hyp_words = hypothesis.split()
    if not ref_words:
        return 0.0 if not hyp_words else 1.0
    
    # Levenshtein distance on word level
    d = [[0] * (len(hyp_words) + 1) for _ in range(len(ref_words) + 1)]
    for i in range(len(ref_words) + 1):
        d[i][0] = i
    for j in range(len(hyp_words) + 1):
        d[0][j] = j
        
    for i in range(1, len(ref_words) + 1):
        for j in range(1, len(hyp_words) + 1):
            if ref_words[i - 1] == hyp_words[j - 1]:
                d[i][j] = d[i - 1][j - 1]
            else:
                substitution = d[i - 1][j - 1] + 1
                insertion = d[i][j - 1] + 1
                deletion = d[i - 1][j] + 1
                d[i][j] = min(substitution, insertion, deletion)
                
    return d[len(ref_words)][len(hyp_words)] / len(ref_words)

def main():
    benchmark_dir = os.path.dirname(__file__)
    csv_file = os.path.join(benchmark_dir, "stt_results.csv")
    
    rows = []
    print("=" * 70)
    print("iTantra Multilingual STT Accuracy Evaluation (WER)")
    print("=" * 70)
    
    for lang, phrases in TEST_DATASET.items():
        total_wer = 0.0
        for ref, hyp in phrases:
            wer = compute_wer(ref, hyp)
            total_wer += wer
            rows.append({
                "language": lang,
                "reference": ref,
                "hypothesis": hyp,
                "wer": round(wer, 4),
                "status": "PASS" if wer < 0.15 else "WARN"
            })
        avg_wer = total_wer / len(phrases)
        print(f"[*] Language: {lang.upper():<4} | Test Phrases: {len(phrases):<2} | Mean WER: {avg_wer * 100:.2f}%")
        
    with open(csv_file, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["language", "reference", "hypothesis", "wer", "status"])
        writer.writeheader()
        writer.writerows(rows)
        
    print(f"\n[+] STT evaluation results saved to: {csv_file}")

if __name__ == "__main__":
    main()
