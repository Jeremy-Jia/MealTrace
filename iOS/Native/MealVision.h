#ifndef MEALVISION_H
#define MEALVISION_H
#ifdef __cplusplus
extern "C" {
#endif
// Each call owns and releases its model/context. Calls are serialized by the caller.
char * mealvision_recognize(const char * model, const char * projector, const char * image,
                           int use_gpu, char ** error);
void mealvision_cancel(void);
void mealvision_free(char * value);
#ifdef __cplusplus
}
#endif
#endif
