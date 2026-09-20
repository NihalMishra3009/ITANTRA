/* Thin, JNI-free wrapper over eSpeak NG (GPL-3.0-or-later). */
#include "itantra_espeak_core.h"

#include <stdlib.h>
#include <string.h>

#include <espeak-ng/speak_lib.h>

static int g_sample_rate = 0;
static short *g_buf = NULL;
static size_t g_len = 0;
static size_t g_cap = 0;

static int synth_cb(short *wav, int numsamples, espeak_EVENT *events) {
    (void)events;
    if (wav == NULL || numsamples <= 0) return 0;
    if (g_len + (size_t)numsamples > g_cap) {
        size_t ncap = g_cap ? g_cap : 16384;
        while (ncap < g_len + (size_t)numsamples) ncap *= 2;
        short *nb = (short *)realloc(g_buf, ncap * sizeof(short));
        if (!nb) return 1; /* non-zero aborts synthesis */
        g_buf = nb;
        g_cap = ncap;
    }
    memcpy(g_buf + g_len, wav, (size_t)numsamples * sizeof(short));
    g_len += (size_t)numsamples;
    return 0;
}

int itx_init(const char *data_parent) {
    if (g_sample_rate > 0) return g_sample_rate;
    int sr = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data_parent,
                               espeakINITIALIZE_DONT_EXIT);
    if (sr <= 0) return -1;
    espeak_SetSynthCallback(synth_cb);
    g_sample_rate = sr;
    return sr;
}

int itx_set_voice(const char *voice) {
    if (g_sample_rate <= 0 || !voice) return -1;
    return espeak_SetVoiceByName(voice) == EE_OK ? 0 : -2;
}

int itx_synth(const char *utf8, int rate_wpm, short **out_pcm, int *out_samples) {
    if (g_sample_rate <= 0 || !utf8 || !out_pcm || !out_samples) return -1;
    if (rate_wpm > 0) espeak_SetParameter(espeakRATE, rate_wpm, 0);
    g_len = 0;
    espeak_ERROR err = espeak_Synth(utf8, strlen(utf8) + 1, 0, POS_CHARACTER, 0,
                                    espeakCHARS_UTF8, NULL, NULL);
    if (err != EE_OK) return -2;
    espeak_Synchronize();
    short *res = (short *)malloc((g_len ? g_len : 1) * sizeof(short));
    if (!res) return -3;
    if (g_len) memcpy(res, g_buf, g_len * sizeof(short));
    *out_pcm = res;
    *out_samples = (int)g_len;
    return 0;
}

void itx_free(short *pcm) { free(pcm); }

void itx_terminate(void) {
    if (g_sample_rate > 0) espeak_Terminate();
    g_sample_rate = 0;
    free(g_buf);
    g_buf = NULL;
    g_len = g_cap = 0;
}
