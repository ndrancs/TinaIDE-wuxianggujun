#include "process.h"
#include "../../libc/string/string.h"
#include "../../libc/libc.h"
#include "../../memory/memory.h"
#include "../../utils/utils.h"
#include "../impl/prefix.h"
#include "../atomic.h"
#include "android.h"
#include <jni.h>

#define TB_ANDROID_PROCESS_MAGIC 0x4150524f

typedef struct __tb_android_process_t
{
    tb_uint32_t    magic;
    tb_long_t      pid;
    tb_long_t      exit_code;
    tb_bool_t      finished;
    tb_cpointer_t  priv;
    tb_bool_t      detached;

}tb_android_process_t;

static tb_atomic_t       s_virtual_pid = 10000;
static jclass            s_bridge_class = tb_null;
static jmethodID         s_bridge_method = tb_null;

tb_bool_t tb_android_process_bind_bridge(JNIEnv* env)
{
    if (!env) return tb_false;

    if (s_bridge_class && s_bridge_method)
        return tb_true;

    jclass localClass = (*env)->FindClass(env, "com/wuxianggujun/tinaide/core/nativebridge/ProcessBridge");
    if (!localClass)
    {
        tb_trace_e("[TinaIDE] Cannot find class: com.wuxianggujun.tinaide.core.nativebridge.ProcessBridge");
        tb_trace_e("[TinaIDE] Make sure the ProcessBridge.kt file exists and is compiled");
        return tb_false;
    }

    jclass globalClass = (jclass)(*env)->NewGlobalRef(env, localClass);
    (*env)->DeleteLocalRef(env, localClass);
    if (!globalClass)
    {
        tb_trace_e("[TinaIDE] Failed to create global ref for ProcessBridge class");
        return tb_false;
    }

    jmethodID method = (*env)->GetStaticMethodID(env, globalClass,
        "startProcess",
        "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;");
    if (!method)
    {
        tb_trace_e("[TinaIDE] Cannot find method: ProcessBridge.startProcess()");
        tb_trace_e("[TinaIDE] Expected signature: (String, String[], String, String[]) -> String");
        (*env)->DeleteGlobalRef(env, globalClass);
        return tb_false;
    }

    s_bridge_class = globalClass;
    s_bridge_method = method;
    tb_trace_i("[TinaIDE] ProcessBridge bound successfully");
    return tb_true;
}

static tb_bool_t tb_android_process_init_bridge(JNIEnv* env)
{
    if (!env) return tb_false;
    if (s_bridge_class && s_bridge_method) return tb_true;
    return tb_android_process_bind_bridge(env);
}

static tb_bool_t tb_android_process_get_env(JNIEnv** out_env)
{
    tb_assert_and_check_return_val(out_env, tb_false);

    JavaVM* jvm = tb_android_jvm();
    if (!jvm)
    {
        // TinaIDE: 这是最常见的错误原因，给出明确的修复建议
        tb_trace_e("[TinaIDE] tb_android_jvm() returned NULL!");
        tb_trace_e("[TinaIDE] Root cause: tb_android_init_env(jvm) was NOT called in JNI_OnLoad");
        tb_trace_e("[TinaIDE] Fix: Add 'tb_android_init_env(vm);' in xmake_runner.cpp JNI_OnLoad()");
        return tb_false;
    }

    JNIEnv* env = tb_null;
    jint state = (*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6);
    if (state == JNI_EDETACHED)
    {
        if ((*jvm)->AttachCurrentThread(jvm, &env, tb_null) != JNI_OK)
        {
            tb_trace_e("[TinaIDE] AttachCurrentThread failed");
            return tb_false;
        }
    }
    else if (state != JNI_OK)
    {
        tb_trace_e("[TinaIDE] GetEnv failed with state: %d", state);
        return tb_false;
    }

    tb_assert_and_check_return_val(env, tb_false);

    if (!tb_android_process_init_bridge(env))
    {
        tb_trace_e("[TinaIDE] Failed to init ProcessBridge - check if class exists");
        return tb_false;
    }

    *out_env = env;
    return tb_true;
}

static tb_long_t tb_android_process_parse_code(tb_char_t const* json)
{
    tb_check_return_val(json, -1);
    tb_char_t const* p = tb_strstr(json, "\"code\"");
    tb_check_return_val(p, -1);
    p = tb_strchr(p, ':');
    tb_check_return_val(p, -1);
    return (tb_long_t)tb_stoi64(p + 1);
}

static jobjectArray tb_android_process_make_jarray(JNIEnv* env, tb_char_t const* datas[], tb_size_t count, jclass strClass)
{
    if (!datas || !count)
        return (*env)->NewObjectArray(env, 0, strClass, tb_null);

    jobjectArray array = (*env)->NewObjectArray(env, (jsize)count, strClass, tb_null);
    tb_check_return_val(array, tb_null);

    for (tb_size_t i = 0; i < count; i++)
    {
        jstring item = (*env)->NewStringUTF(env, datas[i]? datas[i] : "");
        if (!item)
        {
            tb_trace_e("android: failed to alloc jstring");
            (*env)->DeleteLocalRef(env, array);
            return tb_null;
        }
        (*env)->SetObjectArrayElement(env, array, (jsize)i, item);
        (*env)->DeleteLocalRef(env, item);
    }
    return array;
}

tb_bool_t tb_android_process_is_enabled(tb_noarg_t)
{
    // TinaIDE: Android 上始终启用进程桥接
    // 不再检查 TINA_IDE_MODE 环境变量，因为 TinaIDE 只在 Android 上运行
    // 且只通过 ProcessBridge 调用编译器
    return tb_true;
}

tb_process_ref_t tb_process_init_android(tb_char_t const* pathname,
                                         tb_char_t const* argv[],
                                         tb_process_attr_ref_t attr)
{
    // TinaIDE: 记录正在执行的命令，方便调试
    tb_trace_d("[TinaIDE] tb_process_init_android: %s", pathname ? pathname : "(null)");
    
    tb_check_return_val(pathname, tb_null);
    if (!tb_android_process_is_enabled())
    {
        tb_trace_e("[TinaIDE] Process bridge not enabled for command: %s", pathname);
        return tb_null;
    }

    JNIEnv* env = tb_null;
    if (!tb_android_process_get_env(&env))
    {
        tb_trace_e("[TinaIDE] Failed to get JNI env for command: %s", pathname);
        return tb_null;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    tb_check_return_val(stringClass, tb_null);

    jstring j_cmd = (*env)->NewStringUTF(env, pathname);
    tb_check_return_val(j_cmd, tb_null);

    tb_size_t argc = 0;
    if (argv)
        while (argv[argc]) argc++;

    jobjectArray j_args = tb_null;
    if (argc > 1)
        j_args = tb_android_process_make_jarray(env, argv + 1, argc - 1, stringClass);
    else
        j_args = (*env)->NewObjectArray(env, 0, stringClass, tb_null);

    if (!j_args)
    {
        (*env)->DeleteLocalRef(env, j_cmd);
        return tb_null;
    }

    jstring j_workdir = tb_null;
    if (attr && attr->curdir)
        j_workdir = (*env)->NewStringUTF(env, attr->curdir);

    jobjectArray j_envs = tb_null;
    if (attr && attr->envp)
    {
        tb_size_t envc = 0;
        while (attr->envp[envc]) envc++;
        if (envc)
            j_envs = tb_android_process_make_jarray(env, attr->envp, envc, stringClass);
    }
    if (!j_envs)
        j_envs = (*env)->NewObjectArray(env, 0, stringClass, tb_null);

    jstring j_result = (*env)->CallStaticObjectMethod(env,
        s_bridge_class,
        s_bridge_method,
        j_cmd,
        j_args,
        j_workdir,
        j_envs);

    (*env)->DeleteLocalRef(env, j_cmd);
    (*env)->DeleteLocalRef(env, j_args);
    if (j_workdir) (*env)->DeleteLocalRef(env, j_workdir);
    if (j_envs) (*env)->DeleteLocalRef(env, j_envs);
    if ((*env)->ExceptionCheck(env))
    {
        (*env)->ExceptionClear(env);
        tb_trace_e("[TinaIDE] ProcessBridge.startProcess threw exception for: %s", pathname);
        return tb_null;
    }
    if (!j_result)
    {
        tb_trace_e("[TinaIDE] ProcessBridge.startProcess returned null for: %s", pathname);
        return tb_null;
    }

    const char* resultStr = (*env)->GetStringUTFChars(env, j_result, tb_null);
    tb_long_t exit_code = tb_android_process_parse_code(resultStr);
    (*env)->ReleaseStringUTFChars(env, j_result, resultStr);
    (*env)->DeleteLocalRef(env, j_result);

    tb_android_process_t* proc = (tb_android_process_t*)tb_malloc0(sizeof(tb_android_process_t));
    tb_check_return_val(proc, tb_null);
    proc->magic = TB_ANDROID_PROCESS_MAGIC;
    proc->pid = tb_atomic_fetch_and_add(&s_virtual_pid, 1);
    proc->exit_code = exit_code;
    proc->finished = tb_true;
    if (attr)
    {
        proc->priv = attr->priv;
        proc->detached = (attr->flags & TB_PROCESS_FLAG_DETACH)? tb_true : tb_false;
    }
    return (tb_process_ref_t)proc;
}

tb_bool_t tb_process_is_android(tb_process_ref_t self)
{
    tb_android_process_t* proc = (tb_android_process_t*)self;
    return proc && proc->magic == TB_ANDROID_PROCESS_MAGIC;
}

tb_long_t tb_process_wait_android(tb_process_ref_t self, tb_long_t* pstatus, tb_long_t timeout)
{
    tb_used(timeout);
    tb_android_process_t* proc = (tb_android_process_t*)self;
    tb_check_return_val(proc && proc->magic == TB_ANDROID_PROCESS_MAGIC, -1);
    if (pstatus) *pstatus = proc->exit_code;
    return proc->finished ? 1 : 0;
}

tb_long_t tb_process_waitlist_android(tb_process_ref_t const* processes,
                                      tb_process_waitinfo_ref_t infolist,
                                      tb_size_t infomaxn,
                                      tb_long_t timeout)
{
    tb_used(timeout);
    tb_check_return_val(processes && infolist && infomaxn, -1);
    tb_size_t count = 0;
    for (tb_size_t idx = 0; processes[idx]; idx++)
    {
        tb_android_process_t* proc = (tb_android_process_t*)processes[idx];
        if (!proc || proc->magic != TB_ANDROID_PROCESS_MAGIC)
            return -1;
        if (count < infomaxn)
        {
            infolist[count].index = (tb_long_t)idx;
            infolist[count].status = proc->exit_code;
            infolist[count].process = processes[idx];
        }
        count++;
    }
    return (tb_long_t)count;
}

tb_void_t tb_process_exit_android(tb_process_ref_t self)
{
    tb_android_process_t* proc = (tb_android_process_t*)self;
    if (proc && proc->magic == TB_ANDROID_PROCESS_MAGIC)
        tb_free(proc);
}

tb_void_t tb_process_kill_android(tb_process_ref_t self)
{
    tb_used(self);
}

tb_cpointer_t tb_process_priv_android(tb_process_ref_t self)
{
    tb_android_process_t* proc = (tb_android_process_t*)self;
    tb_check_return_val(proc && proc->magic == TB_ANDROID_PROCESS_MAGIC, tb_null);
    return proc->priv;
}

tb_void_t tb_process_priv_set_android(tb_process_ref_t self, tb_cpointer_t priv)
{
    tb_android_process_t* proc = (tb_android_process_t*)self;
    tb_check_return(proc && proc->magic == TB_ANDROID_PROCESS_MAGIC);
    proc->priv = priv;
}

tb_void_t tb_process_resume_android(tb_process_ref_t self)
{
    tb_used(self);
}

tb_void_t tb_process_suspend_android(tb_process_ref_t self)
{
    tb_used(self);
}
