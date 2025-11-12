// TinaIDE C++ launcher template
// - User main is renamed via -Dmain=tina_user_main in user compilation.
// - Define TINA_ENTRY to set the exported entry symbol (e.g., myproj_main).
// - Compile this file as C (-x c), NOT C++, to avoid mangling issues.

#include <stdio.h>

#ifndef TINA_ENTRY
#define TINA_ENTRY run_main
#endif

// Declare user's main as weak C symbol (no mangling, no overloading)
// User code is compiled with -Dmain=tina_user_main, so their main() becomes tina_user_main()
extern int tina_user_main(void) __attribute__((weak));

int TINA_ENTRY(void) {
    if (tina_user_main) {
        return tina_user_main();
    }
    
    // No user entry found
    fprintf(stderr, "[tina] no user entry found (expected tina_user_main)\n");
    return -200;
}
