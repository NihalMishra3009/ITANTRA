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
 *   config.json  ("decoder_start_token_id", "pad_token_id", "eos_token_id",
 *                 "vocab_size")
 *   tokenizer/sp.vocab  (SentencePiece vocab: "<id>\t<piece>", id = index)
 *
 * TOKENIZATION   : longest-substring match over the REAL SentencePiece vocab
 *                  (deterministic; NOT whitespace splitting). Full SentencePiece
 *                  parity for every input is the documented enhancement — the
 *                  required test sentences are asserted against HF via the
 *                  sentencepiece parity harness (model-conversion, python).
 * SEQ2SEQ       : encoder "input_ids" -> "last_hidden_state"[1,S,D];
 *                 decoder "input_ids"+"encoder_hidden_states" -> "logits"[1,T,V].
 * Greedy decode bounded (maxSteps).
 *
 * THREAD SAFETY  : a mutex serializes first-load + every translate call.
 * SESSION CACHE  : one OrtEnv + encoder/decoder pair cached per model dir;
 *                  released when the pair changes or on engine release.
 * TIMING         : monotonic (std::chrono::steady_clock) per stage, returned
 *                  to Java for benchmarking.
 * ERRORS         : a structured error string ({code}:{message}) instead of "".
 */

#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <fstream>
#include <sstream>
#include <cstdint>
#include <cstring>
#include <chrono>
#include <mutex>
#include <dlfcn.h>
#include <android/log.h>
#include "core/session/onnxruntime_c_api.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "itantra_mt", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "itantra_mt", __VA_ARGS__)

namespace {

const OrtApi* g_ort = nullptr;
std::mutex g_mtx;  // serializes load + translate

bool loadOrtApi() {
    std::lock_guard<std::mutex> lk(g_mtx);
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

const char* lastError(OrtStatus* st) { return (st && g_ort->GetErrorMessage) ? g_ort->GetErrorMessage(st) : "unknown"; }
bool check(OrtStatus* st) { if (!st) return true; LOGE("ORT error: %s", lastError(st)); g_ort->ReleaseStatus(st); return false; }

void releaseSessionEnv(OrtEnv* e, OrtSession* s1, OrtSession* s2) {
    if (g_ort) {
        if (s1) g_ort->ReleaseSession(s1);
        if (s2) g_ort->ReleaseSession(s2);
        if (e) g_ort->ReleaseEnv(e);
    }
}

struct SpVocab {
    std::vector<std::string> idToPiece;
    std::map<std::string, int64_t> pieceToId;
    int64_t pad_id = 0, bos_id = 0, eos_id = 0, unk_id = 0, decoder_start_id = 0;
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
        auto tab = line.find('\t');
        if (tab == std::string::npos) continue;
        int64_t id = atoll(line.substr(0, tab).c_str());
        std::string piece = line.substr(tab + 1);
        if (id < 0) continue;
        if ((int64_t)v.idToPiece.size() <= id) v.idToPiece.resize((size_t)id + 1);
        v.idToPiece[(size_t)id] = piece;
        if (v.pieceToId.find(piece) == v.pieceToId.end()) v.pieceToId[piece] = id;
        if ((int)piece.size() > v.maxLen) v.maxLen = (int)piece.size();
    }
    return !v.idToPiece.empty();
}

// Longest-match tokenization over the real SP vocab (ids incl. BOS/EOS).
std::vector<int64_t> tokenize(const std::string& text, const SpVocab& v) {
    std::vector<int64_t> ids;
    ids.push_back(v.bos_id);
    size_t i = 0;
    const size_t n = text.size();
    while (i < n) {
        int len = std::min(v.maxLen > 0 ? v.maxLen : 1, (int)(n - i));
        bool matched = false;
        for (; len >= 1; --len) {
            auto it = v.pieceToId.find(text.substr(i, (size_t)len));
            if (it != v.pieceToId.end()) { ids.push_back(it->second); i += (size_t)len; matched = true; break; }
        }
        if (!matched) { ids.push_back(v.unk_id); i += 1; }
    }
    ids.push_back(v.eos_id);
    return ids;
}

// Cached per-pair session: created once, reused until pair changes/release.
struct CachedPair {
    std::string dir;
    OrtEnv* env = nullptr;
    OrtSession* enc = nullptr;
    OrtSession* dec = nullptr;
    SpVocab vocab;
};

CachedPair g_pair;

inline int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void clearPair() {
    if (g_ort) {
        releaseSessionEnv(g_pair.env, g_pair.enc, g_pair.dec);
    }
    g_pair = CachedPair();
}

} // namespace

static int64_t argmaxOverLogits(const std::vector<float>& logits, int64_t rowStart, int64_t vocabSize) {
    int64_t best = 0; float bestv = -1e30f;
    for (int64_t t = 0; t < vocabSize; ++t) {
        float x = logits[(size_t)(rowStart + t)];
        if (x > bestv) { bestv = x; best = t; }
    }
    return best;
}

extern "C" JNIEXPORT void JNICALL
Java_com_itantra_translation_OpusMtTranslationEngine_nnRelease(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_mtx);
    clearPair();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_itantra_translation_OpusMtTranslationEngine_nnTranslate(
        JNIEnv* env, jobject /*thiz*/, jstring jModelDir, jstring jText) {

    const char* dirC = env->GetStringUTFChars(jModelDir, nullptr);
    const char* textC = env->GetStringUTFChars(jText, nullptr);
    std::string dir = dirC, text = textC;
    env->ReleaseStringUTFChars(jModelDir, dirC);
    env->ReleaseStringUTFChars(jText, textC);
    if (dir.empty() || text.empty()) return env->NewStringUTF("101:empty-input");

    std::lock_guard<std::mutex> lk(g_mtx);  // serialize load + translate (P1)

    if (!loadOrtApi()) return env->NewStringUTF("102:no-ort");

    // ---- Session cache: reuse encoder/decoder for this pair (P1) ----
    if (g_pair.dir != dir || g_pair.enc == nullptr || g_pair.dec == nullptr) {
        clearPair();
        if (!loadConfig(dir, g_pair.vocab) || !loadVocab(dir, g_pair.vocab)) {
            g_pair.dir = dir; // keep dir so we retry vocab if files later appear? no — report
            clearPair();
            return env->NewStringUTF("103:bad-pack");
        }
        OrtEnv* env1 = nullptr;
        if (!check(g_ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "itantra_mt", &env1))) {
            return env->NewStringUTF("104:no-env");
        }
        OrtSessionOptions* opt = nullptr;
        if (!check(g_ort->CreateSessionOptions(&opt))) { releaseSessionEnv(env1, nullptr, nullptr); return env->NewStringUTF("104:no-opt"); }
        if (!check(g_ort->SetSessionGraphOptimizationLevel(opt, ORT_ENABLE_BASIC))) { g_ort->ReleaseSessionOptions(opt); releaseSessionEnv(env1, nullptr, nullptr); return env->NewStringUTF("105:no-optlev"); }
        OrtSession* enc = nullptr, *dec = nullptr;
        std::string encPath = dir + "/encoder_model.onnx";
        std::string decPath = dir + "/decoder_model.onnx";
        bool okE = check(g_ort->CreateSession(env1, encPath.c_str(), opt, &enc));
        if (okE) okE = check(g_ort->CreateSession(env1, decPath.c_str(), opt, &dec));
        g_ort->ReleaseSessionOptions(opt);
        if (!okE) { releaseSessionEnv(env1, enc, dec); return env->NewStringUTF("106:load-session"); }
        g_pair.dir = dir; g_pair.env = env1; g_pair.enc = enc; g_pair.dec = dec;
        LOGI("MT pair cached: %s", dir.c_str());
    }

    const SpVocab& vocab = g_pair.vocab;
    OrtSession* encoder = g_pair.enc;
    OrtSession* decoder = g_pair.dec;
    OrtEnv* ortEnv = g_pair.env;

    int64_t t0 = nowNs();
    auto ids = tokenize(text, vocab);
    int64_t tTok = nowNs() - t0;

    OrtMemoryInfo* mem = nullptr;
    if (!check(g_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem))) return env->NewStringUTF("107:no-mem");

    // ---- Encoder ----
    int64_t t1 = nowNs();
    std::vector<int64_t> encShape = {1, (int64_t)ids.size()};
    std::vector<int64_t> idsLong(ids.begin(), ids.end());
    OrtValue* encIn = nullptr;
    OrtValue* encOuts[1] = {nullptr};
    const char* encInNames[1] = {"input_ids"};
    const char* encOutNames[1] = {"last_hidden_state"};
    bool ok = check(g_ort->CreateTensorWithDataAsOrtValue(mem, idsLong.data(), idsLong.size() * sizeof(int64_t),
            encShape.data(), 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &encIn));
    if (ok) ok = check(g_ort->Run(encoder, nullptr, encInNames, (const OrtValue* const*)&encIn, 1, encOutNames, 1, encOuts));
    if (encIn) g_ort->ReleaseValue(encIn);
    if (!ok) { g_ort->ReleaseMemoryInfo(mem); return env->NewStringUTF("108:enc-run"); }
    int64_t tEnc = nowNs() - t1;

    // Extract last_hidden_state [1,S,D].
    OrtTensorTypeAndShapeInfo* info = nullptr;
    if (!check(g_ort->GetTensorTypeAndShape(encOuts[0], &info))) { g_ort->ReleaseValue(encOuts[0]); g_ort->ReleaseMemoryInfo(mem); return env->NewStringUTF("109:shape"); }
    size_t ndim = 0; g_ort->GetDimensionsCount(info, &ndim);
    std::vector<int64_t> dims(ndim, 0); g_ort->GetDimensions(info, dims.data(), ndim);
    void* encData = nullptr; g_ort->GetTensorMutableData(encOuts[0], &encData);
    int64_t S = dims.size() > 1 ? dims[1] : 1;
    int64_t D = dims.size() > 2 ? dims[2] : 1;
    std::vector<float> encHidden((size_t)(S * D));
    std::memcpy(encHidden.data(), encData, encHidden.size() * sizeof(float));
    g_ort->ReleaseValue(encOuts[0]);

    // ---- Decoder greedy (bounded) ----
    int64_t t2 = nowNs();
    const int64_t maxSteps = 64;
    const int64_t vocabSize = (int64_t)vocab.idToPiece.size();
    std::vector<int64_t> decIds = {vocab.decoder_start_id};
    std::string out;
    bool done = false;
    int steps = 0;
    while (steps < maxSteps && !done) {
        std::vector<int64_t> decShape = {1, (int64_t)decIds.size()};
        OrtValue* decIdsIn = nullptr;
        OrtValue* encHiddenIn = nullptr;
        OrtValue* decOuts[1] = {nullptr};
        const char* decInNames[2] = {"input_ids", "encoder_hidden_states"};
        const char* decOutNames[1] = {"logits"};
        bool okd = check(g_ort->CreateTensorWithDataAsOrtValue(mem, decIds.data(), decIds.size() * sizeof(int64_t),
                decShape.data(), 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &decIdsIn));
        std::vector<int64_t> encShape2 = {1, S, D};
        if (okd) okd = check(g_ort->CreateTensorWithDataAsOrtValue(mem, encHidden.data(), encHidden.size() * sizeof(float),
                encShape2.data(), 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &encHiddenIn));
        const OrtValue* decIns[2] = {decIdsIn, encHiddenIn};
        if (okd) okd = check(g_ort->Run(decoder, nullptr, decInNames, decIns, 2, decOutNames, 1, decOuts));
        g_ort->ReleaseValue(decIdsIn);
        g_ort->ReleaseValue(encHiddenIn);
        if (!okd) { g_ort->ReleaseMemoryInfo(mem); return env->NewStringUTF("110:dec-run"); }
        void* logitsData = nullptr; g_ort->GetTensorMutableData(decOuts[0], &logitsData);
        int64_t T = (int64_t)decIds.size();
        std::vector<float> logits((float*)logitsData, (float*)logitsData + T * vocabSize);
        g_ort->ReleaseValue(decOuts[0]);
        int64_t next = argmaxOverLogits(logits, (T - 1) * vocabSize, vocabSize);
        if (next == vocab.eos_id || next == vocab.pad_id) { done = true; break; }
        if (next >= 0 && next < (int64_t)vocab.idToPiece.size()) {
            std::string piece = vocab.idToPiece[(size_t)next];
            if (piece.rfind("▁", 0) == 0) {
                if (!out.empty()) out += ' ';
                out += piece.substr(3);
            } else {
                out += piece;
            }
        }
        decIds.push_back(next);
        steps++;
    }
    int64_t tDec = nowNs() - t2;
    (void)ortEnv; // env lives with the cached pair; released on nnRelease / pair change

    g_ort->ReleaseMemoryInfo(mem);

    char meta[160];
    snprintf(meta, sizeof(meta), "\n__mttok=%lld\n__mtenc=%lld\n__mtdec=%lld\n__mtall=%lld",
             (long long)(tTok / 1000), (long long)(tEnc / 1000),
             (long long)(tDec / 1000), (long long)(nowNs() - t0) / 1000);
    std::string res = out + meta;
    return env->NewStringUTF(res.c_str());
}