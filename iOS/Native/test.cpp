#include "MealVision.h"
#include <cstdio>
int main(int argc, char ** argv) {
    if (argc != 4) return 2;
    char * error = nullptr;
    char * result = mealvision_recognize(argv[1], argv[2], argv[3], 1, &error);
    if (!result) { fprintf(stderr, "%s\n", error ? error : "Unknown failure"); mealvision_free(error); return 1; }
    puts(result); mealvision_free(result); return 0;
}
