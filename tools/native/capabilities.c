#include <asm/hwcap.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/auxv.h>

// Public Qualcomm FastRPC remote_handle_control ABI (DSP capability request = 2).
struct dsp_capability { uint32_t domain, attribute, capability; };
typedef int (*control_fn)(uint32_t, void *, uint32_t);

int main(void) {
    unsigned long h = getauxval(AT_HWCAP), h2 = getauxval(AT_HWCAP2);
    int fast_cpu = (h & HWCAP_FPHP) && (h & HWCAP_ASIMDHP) &&
                   (h & HWCAP_ASIMDDP) && (h2 & HWCAP2_I8MM);
    uint32_t arch = 0, unsigned_pd = 0;
    void * lib = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (lib) {
        control_fn control = (control_fn)dlsym(lib, "remote_handle_control");
        if (control) {
            struct dsp_capability a = {3, 6, 0}, u = {3, 1, 0};
            if (control(2, &a, sizeof a) == 0) arch = a.capability & 255;
            if (control(2, &u, sizeof u) == 0) unsigned_pd = u.capability;
        }
        dlclose(lib);
    }
    printf("{\"fast_cpu\":%s,\"hexagon_arch\":%u,\"unsigned_pd\":%s}\n",
           fast_cpu ? "true" : "false", arch, unsigned_pd ? "true" : "false");
    return 0;
}
