// TinaIDE launcher template (C, no prototype) to tolerate both main() and main(int,char**)
// The user's main is renamed to tina_user_main at compile time via -Dmain=tina_user_main

#ifdef __cplusplus
extern "C" {
#endif

extern int tina_user_main(); // no prototype on purpose

int run_main(void) {
    // Call with (0, NULL). For signatures that take (int,char**), extra args match exactly.
    // For zero-arg main, extra args are tolerated by most ABIs when no prototype is visible.
    return tina_user_main(0, (char**)0);
}

#ifdef __cplusplus
}
#endif
