#!/usr/bin/env python3
"""
Synthetic Multilingual Dataset Generator for iTantra Offline Transceiver.
Generates 16kHz 16-bit Mono PCM audio samples with ground truth transcriptions,
formants, envelopes, and synthetic background noise (SNR 20dB, 10dB) across 10 Indian languages.
"""

import os
import sys
import json
import math
import random
import struct
import wave

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

SAMPLE_RATE = 16000

PHRASES = {
    "hi": [
        "मुझे तुरंत सहायता चाहिए",
        "रास्ता साफ है तुरंत आगे बढ़ें",
        "दवाइयों और भोजन की आवश्यकता है",
        "हम सुरक्षित स्थान पर पहुंच गए हैं"
    ],
    "mr": [
        "मला मदतीची गरज आहे",
        "तातडीने मदत पाठवा",
        "जागा सुरक्षित आहे",
        "औषधांची गरज आहे"
    ],
    "bn": [
        "আমার সাহায্য প্রয়োজন",
        "অবিলম্বে সাহায্য পাঠান",
        "স্থান নিরাপদ আছে",
        "ওষুধের প্রয়োজন"
    ],
    "gu": [
        "મને મદદ જોઈએ છે",
        "તાત્કાલિક સહાય મોકલો",
        "સ્થાન સુરક્ષિત છે",
        "દવાઓની જરૂર છે"
    ],
    "or": [
        "ମୋତେ ସାହାଯ୍ୟ ଦରକାର",
        "ତୁରନ୍ତ ସାହାଯ୍ୟ ପଠାନ୍ତୁ",
        "ସ୍ଥାନ ସୁରକ୍ଷିତ ଅଛି",
        "ଔଷଧ ଆବଶ୍ୟକ"
    ],
    "ta": [
        "எனக்கு உதவி தேவை",
        "உடனடி உதவி அனுப்பவும்",
        "இடம் பாதுகாப்பாக உள்ளது",
        "மருந்துகள் தேவை"
    ],
    "te": [
        "నాకు సహాయం కావాలి",
        "వెంటనే సహాయం పంపండి",
        "ప్రదేశం సురక్షితంగా ఉంది",
        "మందులు అవసరం"
    ],
    "kn": [
        "ನನಗೆ ಸಹಾಯ ಬೇಕು",
        "ತಕ್ಷಣ ಸಹಾಯ ಕಳುಹಿಸಿ",
        "ಸ್ಥಳ ಸುರಕ್ಷಿತವಾಗಿದೆ",
        "ಔಷಧಿಗಳ ಅವಶ್ಯಕತೆಯಿದೆ"
    ],
    "ml": [
        "എനിക്ക് സഹായം വേണം",
        "ഉടൻ സഹായം അയക്കുക",
        "സ്ഥലം സുരക്ഷിതമാണ്",
        "മരുന്നുകൾ ആവശ്യമാണ്"
    ],
    "en": [
        "I need assistance immediately",
        "Route is clear proceed now",
        "Medical supplies and food required",
        "We have reached the safe shelter"
    ]
}

def generate_synthetic_audio(text: str, lang: str, noise_type: str = "none") -> bytes:
    """Generates synthetic 16kHz speech PCM waveform with formants and noise."""
    words = text.split()
    total_samples = []
    
    # Pre-speech silence (150ms)
    total_samples.extend([0] * int(SAMPLE_RATE * 0.15))
    
    base_freq = 135.0 if lang in ["hi", "mr", "gu"] else (145.0 if lang in ["ta", "ml"] else 125.0)
    
    for word_idx, word in enumerate(words):
        word_dur = int(SAMPLE_RATE * (0.20 + len(word) * 0.03))
        f1 = 500.0 + (word_idx * 70) % 250
        f2 = 1500.0 + (word_idx * 120) % 400
        
        for i in range(word_dur):
            t = i / SAMPLE_RATE
            progress = i / word_dur
            
            # Envelope (ADSR)
            if progress < 0.15:
                env = progress / 0.15
            elif progress > 0.80:
                env = (1.0 - progress) / 0.20
            else:
                env = 1.0
                
            pitch = base_freq + 8.0 * math.sin(math.pi * progress)
            voice = (math.sin(2 * math.pi * pitch * t) +
                     0.4 * math.sin(4 * math.pi * pitch * t) +
                     0.3 * math.sin(2 * math.pi * f1 * t) +
                     0.2 * math.sin(2 * math.pi * f2 * t)) * env
            
            sample = int(voice * 18000)
            
            # Add synthetic noise if specified
            if noise_type == "ambient":
                sample += int((random.random() - 0.5) * 1200) # ~20dB SNR
            elif noise_type == "disaster_wind":
                wind = math.sin(2 * math.pi * 45 * t) * 2500 + (random.random() - 0.5) * 2000 # ~12dB SNR
                sample += int(wind)
                
            sample = max(-32768, min(32767, sample))
            total_samples.append(sample)
            
        # Inter-word silence (60ms)
        total_samples.extend([0] * int(SAMPLE_RATE * 0.06))
        
    # Post-speech silence (200ms)
    total_samples.extend([0] * int(SAMPLE_RATE * 0.20))
    
    raw_bytes = struct.pack(f"<{len(total_samples)}h", *total_samples)
    return raw_bytes

def main():
    output_dir = os.path.join("benchmark", "synthetic_dataset")
    os.makedirs(output_dir, exist_ok=True)
    
    dataset_index = []
    print("=" * 80)
    print("GENERATING SYNTHETIC MULTILINGUAL DATASET (10 INDIAN LANGUAGES)")
    print("=" * 80)
    
    sample_id = 1
    for lang, phrases in PHRASES.items():
        for phrase_idx, phrase in enumerate(phrases):
            for noise in ["clean", "ambient", "disaster_wind"]:
                filename = f"synth_{lang}_{phrase_idx+1}_{noise}.wav"
                filepath = os.path.join(output_dir, filename)
                
                audio_bytes = generate_synthetic_audio(phrase, lang, noise)
                
                # Write standard WAV file
                with wave.open(filepath, "wb") as wav_file:
                    wav_file.setnchannels(1)
                    wav_file.setsampwidth(2)
                    wav_file.setframerate(SAMPLE_RATE)
                    wav_file.writeframes(audio_bytes)
                    
                duration_sec = len(audio_bytes) / (SAMPLE_RATE * 2)
                
                entry = {
                    "id": f"SMP_{sample_id:04d}",
                    "language": lang,
                    "filename": filename,
                    "filepath": filepath,
                    "ground_truth": phrase,
                    "noise_condition": noise,
                    "duration_sec": round(duration_sec, 2),
                    "sample_rate": SAMPLE_RATE
                }
                dataset_index.append(entry)
                sample_id += 1
                
    index_path = os.path.join(output_dir, "dataset.json")
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(dataset_index, f, ensure_ascii=False, indent=2)
        
    print(f"[+] Successfully generated {len(dataset_index)} synthetic audio samples.")
    print(f"[+] Dataset manifest written to: {index_path}")
    print("=" * 80)

if __name__ == "__main__":
    main()
