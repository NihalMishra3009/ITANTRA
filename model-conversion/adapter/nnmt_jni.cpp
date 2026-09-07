/*
 * libitantra_mt — JNI adapter that runs Helsinki-NLP Opus-MT as a seq2seq ONNX
 * deployment through the ONNX Runtime C API ALREADY LINKED by sherpa-onnx.
 *
 * Runtime: dlopen("libonnxruntime.so") -> OrtGetApiBase().GetApi(ORT_API_VERSION).
 * The vendored onnxruntime_c_api.h (1.27) provides the EXACT OrtApi struct layout,
 * so this compiles against real ORT and reuses sherpa's native ORT without a
 * second libonnxruntime.so.
 *
 * Model pack layout ({modelDir}/):
 *   encoder_model.onnx
 *   decoder_model.onnx
 *   config.json                  ("decoder_start_token_id", "pad_token_id",
 *                                 "eos_token_id", "vocab_size")
 *   tokenizer/sp.vocab           (SentencePiece vocab: "<id>\t<piece>", id = index)
 *
 * Tokenization: longest-substring match over the REAL SentencePiece vocabulary
 * (not whitespace splitting). Crossing matches produce the exact vocab pieces.
 * This yields faithful token IDs for common tokens; a full SentencePiece encode
 * is the documented enhancement path.
 *
 * Seq2seq contract (HuggingFace MarianMT ONNX export):
 *   encoder: in "input_ids" int64[1,S] -> out "last_hidden_state"[1,S,D]
 *   decoder: in "input_ids"[1,T], "encoder_hidden_states"[1,S,D] -> "logits"[1,T,V]
 * Greedy decoding runs in this file.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <fstream>
#include <sstream>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <android/log.h>
#include "onnxruntime_c_api.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "itantra_mt", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "itantra_mt", __VA_ARGS__)

namespace {

const OrtApi* g_ort = nullptr;

bool loadOrtApi() {
    if (g_ort) return true;
    void* h = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) h = dlopen("libonnxruntime.so", RTLD_NOW);
    if (!h) { LOGE("dlopen libonnxruntime.so failed: %s", dlerror()); return false; }
    auto base_fn = (const OrtApiBase*(*)())dlsym(h, "OrtGetApiBase");
    if (!base_fn) { LOGE("OrtGetApiBase not found"); return false; }
    g_ort = base_fn()->GetApi(ORT_API_VERSION);
    if (!g_ort) { LOGE("GetApi(%d) returned null", ORT_API_VERSION); return false; }
    LOGI("ONNX Runtime C API loaded (ver %s)", base_fn()->GetVersionString());
    return true;
}

// ---- tiny helpers over the OrtApi ----
const char* lastError(OrtStatus* st) { return (st && g_ort->GetErrorMessage) ? g_ort->GetErrorMessage(st) : "unknown"; }
bool check(OrtStatus* st) { if (!st) return true; LOGE("ORT error: %s", lastError(st)); g_ort->ReleaseStatus(st); return false; }

struct SpVocab {
    std::vector<std::string> idToPiece;   // index -> piece
    std::map<std::string, int64_t> pieceToId; // longest piece -> id (longer keys preferred)
    int64_t pad_id = 0, bos_id = 0, eos_id = 0, unk_id = 0;
    int64_t decoder_start_id = 0;
    int maxLen = 0;
};

bool loadConfig(const std::string& dir, SpVocab& v) {
    std::ifstream cfg(dir + "/config.json");
    if (!cfg.is_open()) { LOGE("config.json missing in %s", dir.c_str()); return false; }
    std::string s((std::istreambuf_iterator<char>(cfg)), std::istreambuf_iterator<char>());
    auto findNum = [&](const std::string& key) -> int64_t {
        auto pos = s.find(key);
        if (pos == std::string::npos) return -1;
        pos = s.find(':', pos);
        if (pos == std::string::npos) return -1;
        return atoll(s.c_str() + pos + 1);
    };
    v.pad_id = findNum("\"pad_token_id\"");
    v.eos_id = findNum("\"eos_token_id\"");
    v.bos_id = findNum("\"bos_token_id\"");
    v.decoder_start_id = findNum("\"decoder_start_token_id\"");
    if (v.decoder_start_id < 0) v.decoder_start_id = v.bos_id;
    return true;
}

bool loadVocab(const std::string& dir, SpVocab& v) {
    std::ifstream f(dir + "/tokenizer/sp.vocab");
    if (!f.is_open()) { LOGE("tokenizer/sp.vocab missing in %s", dir.c_str()); return false; }
    std::string line;
    while (std::getline(f, line)) {
        // each line: "<id>\t<piece>"
        auto tab = line.find('\t');
        if (tab == std::string::npos) continue;
        int64_t id = atoll(line.substr(0, tab).c_str());
        std::string piece = line.substr(tab + 1);
        if (id < 0) continue;
        if ((int64_t)v.idToPiece.size() <= id) v.idToPiece.resize((size_t)id + 1);
        v.idToPiece[(size_t)id] = piece;
        // keep the LONGEST piece for each id (SentencePiece pieces are unique)
        auto it = v.pieceToId.find(piece);
        if (it == v.pieceToId.end()) v.pieceToId[piece] = id;
        if ((int)piece.size() > v.maxLen) v.maxLen = (int)piece.size();
    }
    return !v.idToPiece.empty();
}

// Longest-match tokenization over the real SP vocab (returns ids incl. BOS/EOS).
// Opus-MT directed pairs have a single source/target (no language marker token).
std::vector<int64_t> tokenize(const std::string& text, const SpVocab& v) {
    std::vector<int64_t> ids;
    ids.push_back(v.bos_id);
    size_t i = 0;
    const size_t n = text.size();
    while (i < n) {
        int bestLen = v.maxLen > 0 ? (int)v.maxLen : 1;
        int len = std::min(bestLen, (int)(n - i));
        bool matched = false;
        for (; len >= 1; --len) {
            auto it = v.pieceToId.find(text.substr(i, (size_t)len));
            if (it != v.pieceToId.end()) {
                ids.push_back(it->second);
                i += (size_t)len;
                matched = true;
                break;
            }
        }
        if (!matched) { ids.push_back(v.unk_id); i += 1; }
    }
    ids.push_back(v.eos_id);
    return ids;
}

struct OrtSessionHolder {
    OrtSession* enc = nullptr;
    OrtSession* dec = nullptr;
    OrtSessionOptions* opts = nullptr;
    OrtEnv* env = nullptr;
};

} // namespace

static int64_t argmaxOverLogits(const std::vector<float>& logits, int64_t rowStart, int64_t vocabSize) {
    int64_t best = 0; float bestv = -1e30f;
    for (int64_t t = 0; t < vocabSize; ++t) {
        float x = logits[(size_t)(rowStart + t)];
        if (x > bestv) { bestv = x; best = t; }
    }
    return best;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_itantra_translation_OpusMtTranslationEngine_nnTranslate(
        JNIEnv* env, jobject /*thiz*/, jstring jModelDir, jstring jText) {

    const char* dirC = env->GetStringUTFChars(jModelDir, nullptr);
    const char* textC = env->GetStringUTFChars(jText, nullptr);
    std::string dir = dirC, text = textC;
    env->ReleaseStringUTFChars(jModelDir, dirC);
    env->ReleaseStringUTFChars(jText, textC);
    if (dir.empty() || text.empty()) return env->NewStringUTF("");

    if (!loadOrtApi()) return env->NewStringUTF("");

    SpVocab vocab;
    if (!loadConfig(dir, vocab) || !loadVocab(dir, vocab)) {
        return env->NewStringUTF("");
    }

    // Load models.
    OrtSession* encoder = nullptr;
    OrtSession* decoder = nullptr;
    {
        OrtSessionOptions* opt = nullptr;
        if (!check(g_ort->CreateSessionOptions(&opt))) return env->NewStringUTF("");
        if (!check(g_ort->SetSessionGraphOptimizationLevel(opt, ORT_ENABLE_BASIC))) { g_ort->ReleaseSessionOptions(opt); return env->NewStringUTF(""); }
        std::string encPath = dir + "/encoder_model.onnx";
        if (!check(g_ort->CreateSession(nullptr, encPath.c_str(), opt, &encoder))) { g_ort->ReleaseSessionOptions(opt); return env->NewStringUTF(""); }
        std::string decPath = dir + "/decoder_model.onnx";
        if (!check(g_ort->CreateSession(nullptr, decPath.c_str(), opt, &decoder))) { g_ort->ReleaseSessionOptions(opt); return env->NewStringUTF(""); }
        g_ort->ReleaseSessionOptions(opt);
    }

    // Tokenize (source language requested by the caller's language pair).
    auto ids = tokenize(text, vocab);

    // Encoder.
    OrtMemoryInfo* mem = nullptr;
    g_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem);
    std::vector<int64_t> encShape = {1, (int64_t)ids.size()};
    std::vector<int64_t> idsLong(ids.begin(), ids.end());
    OrtValue* encIn = nullptr;
    if (!check(g_ort->CreateTensorWithDataAsOrtValue(mem, idsLong.data(), idsLong.size() * sizeof(int64_t),
            encShape.data(), 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &encIn))) {
        g_ort->ReleaseMemoryInfo(mem); return env->NewStringUTF("");
    }
    const char* encInNames[1] = {"input_ids"};
    OrtValue* encOuts[1] = {nullptr};
    bool ok = check(g_ort->Run(encoder, nullptr, encInNames, (const OrtValue* const*)&encIn, 1, encOuts, 1));
    g_ort->ReleaseValue(encIn);
    if (!ok) { g_ort->ReleaseMemoryInfo(mem); return env->NewStringUTF(""); }

    // Extract last_hidden_state [1,S,D].
    OrtTensorTypeAndShapeInfo* info = nullptr;
    if (!check(g_ort->GetTensorTypeAndShape(encOuts[0], &info))) { g_ort->ReleaseValue(encOuts[0]); g_ort->ReleaseMemoryInfo(mem); return env->NewStringUTF(""); }
    size_t ndim = 0;
    g_ort->GetDimensionsCount(info, &ndim);
    std::vector<int64_t> dims(ndim, 0);
    g_ort->GetDimensions(info, dims.data(), ndim);
    void* encData = nullptr;
    g_ort->GetTensorMutableData(encOuts[0], &encData);
    int64_t S = dims.size() > 1 ? dims[1] : 1;
    int64_t D = dims.size() > 2 ? dims[2] : 1;
    std::vector<float> encHidden((size_t)(S * D));
    std::memcpy(encHidden.data(), encData, encHidden.size() * sizeof(float));

    // Decoder greedy loop (bounded).
    const int64_t maxSteps = 64;
    const int64_t vocabSize = (int64_t)vocab.idToPiece.size();
    std::vector<int64_t> decIds = {vocab.decoder_start_id};
    std::string out;
    bool done = false;
    for (int step = 0; step < maxSteps && !done; ++step) {
        std::vector<int64_t> decShape = {1, (int64_t)decIds.size()};
        OrtValue* decIdsIn = nullptr;
        g_ort->CreateTensorWithDataAsOrtValue(mem, decIds.data(), decIds.size() * sizeof(int64_t),
                decShape.data(), 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &decIdsIn);
        std::vector<int64_t> encShape2 = {1, S, D};
        OrtValue* encHiddenIn = nullptr;
        g_ort->CreateTensorWithDataAsOrtValue(mem, encHidden.data(), encHidden.size() * sizeof(float),
                encShape2.data(), 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &encHiddenIn);
        const char* decInNames[2] = {"input_ids", "encoder_hidden_states"};
        const OrtValue* decIns[2] = {decIdsIn, encHiddenIn};
        OrtValue* decOuts[1] = {nullptr};
        char* decOutName = "logits";
        bool okd = check(g_ort->Run(decoder, nullptr, decInNames, decIns, 2, decOuts, 1));
        g_ort->ReleaseValue(decIdsIn);
        g_ort->ReleaseValue(encHiddenIn);
        if (!okd) { done = true; break; }
        // logits [1,T,V]; take last step row
        void* logitsData = nullptr;
        g_ort->GetTensorMutableData(decOuts[0], &logitsData);
        int64_t T = (int64_t)decIds.size();
        std::vector<float> logits((float*)logitsData, (float*)logitsData + T * vocabSize);
        g_ort->ReleaseValue(decOuts[0]);
        int64_t next = argmaxOverLogits(logits, (T - 1) * vocabSize, vocabSize);
        if (next == vocab.eos_id || next == vocab.pad_id) { done = true; break; }
        if (next >= 0 && next < (int64_t)vocab.idToPiece.size()) {
            std::string piece = vocab.idToPiece[(size_t)next];
            // SP detokenize: "▁" marks a word start; replace with space (first only).
            if (piece.rfind("▁", 0) == 0) {
                if (!out.empty()) out += ' ';
                out += piece.substr(3);
            } else {
                out += piece;
            }
        }
        decIds.push_back(next);
    }

    g_ort->ReleaseValue(encOuts[0]);
    g_ort->ReleaseMemoryInfo(mem);
    g_ort->ReleaseSession(encoder);
    g_ort->ReleaseSession(decoder);

    return env->NewStringUTF(out.c_str());
}