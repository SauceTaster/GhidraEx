#include <stdint.h>

#if defined(_MSC_VER)
#define NOINLINE __declspec(noinline)
#else
#define NOINLINE __attribute__((noinline))
#endif

static NOINLINE uint32_t rotate_mix(uint32_t value, uint32_t salt) {
    value ^= salt + UINT32_C(0x9e3779b9);
    return (value << 7) | (value >> 25);
}

int main(int argc, char **argv) {
    uint32_t value = rotate_mix((uint32_t) argc, UINT32_C(0x13579bdf));
    return (int) ((value ^ (argv[0] != 0 ? (unsigned char) argv[0][0] : 0)) & 0x7f);
}
