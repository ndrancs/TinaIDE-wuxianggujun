// TinaIDE C++ launcher template with SFINAE dispatch to user main signature.
// - User main is renamed via -Dmain=tina_user_main during compilation of user sources.
// - Define TINA_ENTRY to the desired unmangled entry symbol (e.g., myproj_main).
// - This file must be compiled as C++ (e.g., -x c++ -std=c++17).

#ifndef TINA_ENTRY
#define TINA_ENTRY run_main
#endif

// Declare both candidate signatures; only the actually-defined one will be referenced.
extern int tina_user_main();
extern int tina_user_main(int, char**);

// Prefer calling (int,char**) when available; otherwise fall back to ()
// SFINAE selects the viable expression.

template <typename = void>
static auto tina_user_main_dispatch(int) -> decltype(tina_user_main(0, (char**)0), int()) {
    return tina_user_main(0, (char**)0);
}

template <typename = void>
static auto tina_user_main_dispatch(long) -> decltype(tina_user_main(), int()) {
    return tina_user_main();
}

// Export an unmangled entry symbol so dlsym("<project>_main") succeeds.
extern "C" int TINA_ENTRY(void) {
    return tina_user_main_dispatch<>(0);
}
