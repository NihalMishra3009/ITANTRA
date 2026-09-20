# Vendored eSpeak NG (GPL-3.0-or-later) — k2-fsa fork at ed530aa113046142eb5115cf2fc9157854d0ffe1,
# the exact revision sherpa-onnx pins, so it matches the espeak-ng-data shipped with the
# Piper/sherpa voice packs. Only the synthesis library is built: no CLI, MBROLA,
# libsonic, pcaudio, async or speech-player. See ../../../../../../docs/MODEL_LICENSES.md.
cmake_minimum_required(VERSION 3.22.1)

add_library(ucd STATIC
    src/ucd-tools/src/case.c
    src/ucd-tools/src/categories.c
    src/ucd-tools/src/ctype.c
    src/ucd-tools/src/proplist.c
    src/ucd-tools/src/scripts.c
    src/ucd-tools/src/tostring.c
)
target_include_directories(ucd PUBLIC src/ucd-tools/src/include)
set_target_properties(ucd PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(espeak-ng-static STATIC
    src/libespeak-ng/common.c
    src/libespeak-ng/mnemonics.c
    src/libespeak-ng/error.c
    src/libespeak-ng/ieee80.c
    src/libespeak-ng/compiledata.c
    src/libespeak-ng/compiledict.c
    src/libespeak-ng/dictionary.c
    src/libespeak-ng/encoding.c
    src/libespeak-ng/intonation.c
    src/libespeak-ng/langopts.c
    src/libespeak-ng/numbers.c
    src/libespeak-ng/phoneme.c
    src/libespeak-ng/phonemelist.c
    src/libespeak-ng/readclause.c
    src/libespeak-ng/setlengths.c
    src/libespeak-ng/soundicon.c
    src/libespeak-ng/spect.c
    src/libespeak-ng/ssml.c
    src/libespeak-ng/synthdata.c
    src/libespeak-ng/synthesize.c
    src/libespeak-ng/tr_languages.c
    src/libespeak-ng/translate.c
    src/libespeak-ng/translateword.c
    src/libespeak-ng/voices.c
    src/libespeak-ng/wavegen.c
    src/libespeak-ng/speech.c
    src/libespeak-ng/espeak_api.c
    src/libespeak-ng/klatt.c
)
target_include_directories(espeak-ng-static
    PUBLIC  src/include src/include/compat
    PRIVATE src src/libespeak-ng
)
target_compile_definitions(espeak-ng-static PUBLIC LIBESPEAK_NG_EXPORT=1)
target_compile_options(espeak-ng-static PRIVATE
    -fPIC -fvisibility=hidden -fno-exceptions -fwrapv
    -Wno-unused-result -Wno-format-overflow -Wno-format-truncation
    -Wno-uninitialized -Wno-format -w
)
target_link_libraries(espeak-ng-static PUBLIC ucd m)
