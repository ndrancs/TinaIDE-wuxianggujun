#ifndef TB_PLATFORM_ANDROID_PROCESS_H
#define TB_PLATFORM_ANDROID_PROCESS_H

#include "prefix.h"
#include "../process.h"
#include <jni.h>

tb_bool_t           tb_android_process_is_enabled(tb_noarg_t);
tb_process_ref_t    tb_process_init_android(tb_char_t const* pathname,
                                            tb_char_t const* argv[],
                                            tb_process_attr_ref_t attr);
tb_long_t           tb_process_wait_android(tb_process_ref_t self,
                                            tb_long_t* pstatus,
                                            tb_long_t timeout);
tb_long_t           tb_process_waitlist_android(tb_process_ref_t const* processes,
                                                tb_process_waitinfo_ref_t infolist,
                                                tb_size_t infomaxn,
                                                tb_long_t timeout);
tb_void_t           tb_process_exit_android(tb_process_ref_t self);
tb_void_t           tb_process_kill_android(tb_process_ref_t self);
tb_cpointer_t       tb_process_priv_android(tb_process_ref_t self);
tb_void_t           tb_process_priv_set_android(tb_process_ref_t self, tb_cpointer_t priv);
tb_void_t           tb_process_resume_android(tb_process_ref_t self);
tb_void_t           tb_process_suspend_android(tb_process_ref_t self);
tb_bool_t           tb_process_is_android(tb_process_ref_t self);
tb_bool_t           tb_android_process_bind_bridge(JNIEnv* env);

#endif
