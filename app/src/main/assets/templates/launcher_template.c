// TinaIDE C++ launcher template with weak-overload detection.
// - User main is renamed via -Dmain=tina_user_main in user compilation.
// - Define TINA_ENTRY to set the exported, unmangled entry symbol (e.g., myproj_main).
// - Compile this file as C++ (-x c++ -std=c++17).
//
// Needs stdio for fprintf/stderr used in fallback diagnostics.
#include <stdio.h>
#include <exception>
#include <typeinfo>

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
    int (*p0)() = (int(*)())tina_user_main;

    try {
        if (p2) return p2(0, (char**)0);
        if (p0) return p0();
    } catch (const std::bad_cast& e) {
        fprintf(stderr, "unhandled std::bad_cast (in launcher): %s\n", e.what());
        return 101;
    } catch (const std::exception& e) {
        fprintf(stderr, "unhandled std::exception (in launcher): %s\n", e.what());
        return 102;
    } catch (...) {
        fprintf(stderr, "unhandled non-std exception (in launcher)\n");
        return 103;
    }

    // Neither form linked in; treat as error
    // Also print a hint for the user-facing UI (captured by isolated runner)
    fprintf(stderr, "[tina] no user entry found (expected tina_user_main)\n");
    return -200;
}
