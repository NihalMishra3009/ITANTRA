/*
 * specstrings.h — minimal stub for building the vendored ONNX Runtime C API
 * header on Android. The real specstrings.h is a Windows-only SAL annotation
 * header; the ORT header includes it unconditionally but only uses a few SAL
 * macros (e.g. _Must_inspect_result_, _Frees_ptr_ ...) which are no-ops here.
 */
#ifndef _SPECSTRINGS_H_
#define _SPECSTRINGS_H_

#define _Must_inspect_result_
#define _Ret_notnull_
#define _In_
#define _Inout_
#define _Out_
#define _In_range_(a,b)
#define _Out_opt_
#define _In_opt_
#define _Check_return_
#define _Frees_ptr_
#define _Ret_maybenull_
#define _Use_decl_annotations_
#define ORT_API_CALL
#define ORT_MUST_USE_RESULT
#define ORT_ATTRIBUTE_UNUSED

#endif // _SPECSTRINGS_H_