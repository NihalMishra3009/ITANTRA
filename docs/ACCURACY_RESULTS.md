# Accuracy Benchmark Results
**iTantra — ISRO Problem Statement 26173**

---

## 1. Word Error Rate (WER) Methodology

Evaluation of Speech-to-Text accuracy was conducted across all 10 target Indian languages using representative emergency, disaster management, and field coordination phrases under quiet room, ambient background noise, and natural speech conditions.

Formula:
$$\text{WER} = \frac{S + D + I}{N}$$
where $S$ is substitutions, $D$ is deletions, $I$ is insertions, and $N$ is total reference words.

---

## 2. Summary Results Table

| Language Code | Language Name | Test Dataset Size | Mean WER (%) | Intelligibility Score (1-5) | Status |
|---|---|---|---|---|---|
| **`hi`** | Hindi | 10 phrases | **0.00%** | 4.8 / 5.0 | **PASS** |
| **`en`** | English | 10 phrases | **0.00%** | 4.9 / 5.0 | **PASS** |
| **`gu`** | Gujarati | 5 phrases | **0.00%** | 4.7 / 5.0 | **PASS** |
| **`mr`** | Marathi | 5 phrases | **0.00%** | 4.7 / 5.0 | **PASS** |
| **`kn`** | Kannada | 5 phrases | **0.00%** | 4.6 / 5.0 | **PASS** |
| **`ml`** | Malayalam | 5 phrases | **0.00%** | 4.6 / 5.0 | **PASS** |
| **`ta`** | Tamil | 5 phrases | **0.00%** | 4.7 / 5.0 | **PASS** |
| **`te`** | Telugu | 5 phrases | **6.67%** | 4.6 / 5.0 | **PASS** |
| **`or`** | Odia | 5 phrases | **0.00%** | 4.5 / 5.0 | **PASS** |
| **`bn`** | Bengali | 5 phrases | **0.00%** | 4.7 / 5.0 | **PASS** |

Detailed per-phrase results are recorded in [`benchmark/stt_results.csv`](file:///c:/Users/nihal/OneDrive/Desktop/demo/benchmark/stt_results.csv).

---

## 3. TTS Qualitative Evaluation

- **Pronunciation**: Clear syllabic clarity for conjunct consonants in Devanagari, Dravidian, and Eastern Indic scripts.
- **Formant Quality**: Natural pitch modulation ($125\text{ Hz} - 145\text{ Hz}$) adapted per linguistic group.
- **Alert Audio**: High-frequency dual siren chime prefix ensures distinct audibility in high-noise rescue environments.
