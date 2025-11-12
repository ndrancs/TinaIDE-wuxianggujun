// TinaIDE launcher template using a project-specific entry symbol.
// User's main is renamed via -Dmain=tina_user_main during compilation.
// Define TINA_ENTRY to the desired entry identifier (e.g., myproj_main).

#ifdef __cplusplus
extern "C" {
#endif

extern int tina_user_main(); // intentionally no prototype to tolerate both forms

#ifndef TINA_ENTRY
#define TINA_ENTRY run_main
#endif

int TINA_ENTRY(void) {
    return tina_user_main(0, (char**)0);
}

#ifdef __cplusplus
}
#endif
