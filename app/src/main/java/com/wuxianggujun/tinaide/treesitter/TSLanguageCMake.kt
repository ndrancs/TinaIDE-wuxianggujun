package com.wuxianggujun.tinaide.treesitter

import com.itsaky.androidide.treesitter.TSLanguage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tree-sitter CMake language binding.
 * 
 * This class provides access to the tree-sitter-cmake parser.
 */
object TSLanguageCMake {

    private const val NAME = "cmake"
    
    private val loaded = AtomicBoolean(false)
    
    @Volatile
    private var instance: TSLanguage? = null

    init {
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            // CMake parser is bundled in native_compiler
            try {
                System.loadLibrary("native_compiler")
            } catch (_: UnsatisfiedLinkError) {
                // Already loaded
            }
        }
    }

    /**
     * Get the TSLanguage instance for CMake.
     */
    @JvmStatic
    fun getInstance(): TSLanguage {
        return instance ?: synchronized(this) {
            instance ?: createLanguage().also { instance = it }
        }
    }

    private fun createLanguage(): TSLanguage {
        return TSLanguage.create(NAME, nativeLanguage())
    }

    @JvmStatic
    private external fun nativeLanguage(): Long
}
