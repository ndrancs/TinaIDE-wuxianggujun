// TinaIDE C++ launcher template with weak-overload detection.
// - User main is renamed via -Dmain=tina_user_main in user compilation.
// - Define TINA_ENTRY to set the exported, unmangled entry symbol (e.g., myproj_main).
// - Compile this file as C++ (-x c++ -std=c++17).
//
// Needs stdio for fprintf/stderr used in fallback diagnostics.
#include <stdio.h>

#ifndef TINA_ENTRY
#define TINA_ENTRY run_main
#endif

// Declare both overloads as weak. If a definition is missing, the
// function pointer will be null at runtime and we can safely skip it.
extern int tina_user_main(int, char**) __attribute__((weak));
extern int tina_user_main() __attribute__((weak));

extern "C" int TINA_ENTRY(void) {
    // Prefer argc/argv when available
    int (*p2)(int, char**) = tina_user_main;
    if (p2) return p2(0, nullptr);

    // Fallback to zero-arg form
    int (*p0)() = (int(*)())tina_user_main;
    if (p0) return p0();

    // Neither form linked in; treat as error
    // Also print a hint for the user-facing UI (captured by isolated runner)
    fprintf(stderr, "[tina] no user entry found (expected tina_user_main)\n");
    return -200;
}
