/* XXH3_64bits for Python (ctypes), from the SDK's bundled xxHash: the runtime hashes guest shader
   ucode (big-endian bytes as uploaded) with XXH3_64bits. Build: tools/native/build_xxh3.sh */
#define XXH_INLINE_ALL
#include "xxhash.h"
#ifdef _WIN32
__declspec(dllexport)
#endif
unsigned long long lr_xxh3_64(const void* data, unsigned long long size) {
  return XXH3_64bits(data, (size_t)size);
}
