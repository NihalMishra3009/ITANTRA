#ifndef ITANTRA_ESPEAK_CORE_H
#define ITANTRA_ESPEAK_CORE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Initialise eSpeak NG. `data_parent` is the directory CONTAINING "espeak-ng-data".
 * Returns the output sample rate (Hz) or a negative value on failure. Idempotent. */
int itx_init(const char *data_parent);

/* Select a voice/language by eSpeak name ("hi", "ta", "or", ...). 0 on success. */
int itx_set_voice(const char *voice);

/* Synthesize UTF-8 text to 16-bit mono PCM at the rate returned by itx_init.
 * `rate_wpm` is words-per-minute (<=0 keeps the default). On success returns 0 and
 * hands back a malloc'd buffer that the caller releases with itx_free(). */
int itx_synth(const char *utf8, int rate_wpm, short **out_pcm, int *out_samples);

void itx_free(short *pcm);
void itx_terminate(void);

#ifdef __cplusplus
}
#endif
#endif
