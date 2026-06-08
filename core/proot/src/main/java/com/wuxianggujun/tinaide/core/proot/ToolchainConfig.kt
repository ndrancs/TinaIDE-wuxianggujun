package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import android.os.Parcelable
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import kotlinx.parcelize.Parcelize

/**
 * 工具链配置
 *
 * 用于存储用户选择的工具链组件
 */
@Parcelize
data class ToolchainConfig(
    // 编译器选择
    val installClang: Boolean = true,
    val installGcc: Boolean = false,

    // 链接器选择
    val installLld: Boolean = true,
    val installGnuLd: Boolean = false,

    // 调试器选择
    val installLldb: Boolean = true,
    val installGdb: Boolean = false,

    // 必装组件（不可选）
    val installClangd: Boolean = true,
    val installLlvm: Boolean = true,
    val installLibcxx: Boolean = true,
    val installCmake: Boolean = true,
    val installNinja: Boolean = true,
    val installMake: Boolean = true,
    val installGit: Boolean = true,
    val installClangFormat: Boolean = true
) : Parcelable {
    companion object {
        /**
         * 推荐配置（适合大多数用户）
         * Clang + LLD + LLDB + 所有必装组件
         */
        fun recommended() = ToolchainConfig(
            installClang = true,
            installGcc = false,
            installLld = true,
            installGnuLd = false,
            installLldb = true,
            installGdb = false
        )
    }

    /**
     * 获取需要安装的包列表
     */
    fun getPackageList(): List<String> {
        val packages = mutableListOf<String>()

        // 必装组件
        if (installClangd) packages.add("clangd")
        if (installLlvm) packages.add("llvm")
        if (installLibcxx) {
            packages.add("libc++-dev")
            packages.add("libc++abi-dev")
        }
        if (installCmake) packages.add("cmake")
        if (installNinja) packages.add("ninja-build")
        if (installMake) packages.add("make")
        if (installGit) packages.add("git")
        if (installClangFormat) packages.add("clang-format")

        // 编译器
        if (installClang) packages.add("clang")
        if (installGcc) {
            packages.add("gcc")
            packages.add("g++")
        }

        // 链接器
        if (installLld) packages.add("lld")
        if (installGnuLd) packages.add("binutils")

        // 调试器
        if (installLldb) {
            packages.add("lldb")
            packages.add("python3-lldb")
        }
        if (installGdb) packages.add("gdb")

        return packages
    }

    /**
     * 获取包信息列表（用于 UI 显示）
     */
    fun getPackageInfoList(context: Context): List<PRootBootstrap.PackageInfo> {
        val infoList = mutableListOf<PRootBootstrap.PackageInfo>()

        // 必装组件
        if (installClangd) infoList.add(PRootBootstrap.PackageInfo("clangd", Strings.toolchain_pkg_clangd.strOr(context)))
        if (installLlvm) infoList.add(PRootBootstrap.PackageInfo("llvm", Strings.toolchain_pkg_llvm.strOr(context)))
        if (installLibcxx) {
            infoList.add(PRootBootstrap.PackageInfo("libc++-dev", Strings.toolchain_pkg_libcxx_dev.strOr(context)))
            infoList.add(PRootBootstrap.PackageInfo("libc++abi-dev", Strings.toolchain_pkg_libcxxabi_dev.strOr(context)))
        }
        if (installCmake) infoList.add(PRootBootstrap.PackageInfo("cmake", Strings.toolchain_pkg_cmake.strOr(context)))
        if (installNinja) infoList.add(PRootBootstrap.PackageInfo("ninja-build", Strings.toolchain_pkg_ninja.strOr(context)))
        if (installMake) infoList.add(PRootBootstrap.PackageInfo("make", Strings.toolchain_pkg_make.strOr(context)))
        if (installGit) infoList.add(PRootBootstrap.PackageInfo("git", Strings.toolchain_pkg_git.strOr(context)))
        if (installClangFormat) infoList.add(PRootBootstrap.PackageInfo("clang-format", Strings.toolchain_pkg_clang_format.strOr(context)))

        // 编译器
        if (installClang) infoList.add(PRootBootstrap.PackageInfo("clang", Strings.toolchain_pkg_clang.strOr(context)))
        if (installGcc) {
            infoList.add(PRootBootstrap.PackageInfo("gcc", Strings.toolchain_pkg_gcc.strOr(context)))
            infoList.add(PRootBootstrap.PackageInfo("g++", Strings.toolchain_pkg_gxx.strOr(context)))
        }

        // 链接器
        if (installLld) infoList.add(PRootBootstrap.PackageInfo("lld", Strings.toolchain_pkg_lld.strOr(context)))
        if (installGnuLd) infoList.add(PRootBootstrap.PackageInfo("binutils", Strings.toolchain_pkg_binutils.strOr(context)))

        // 调试器
        if (installLldb) {
            infoList.add(PRootBootstrap.PackageInfo("lldb", Strings.toolchain_pkg_lldb.strOr(context)))
            infoList.add(PRootBootstrap.PackageInfo("python3-lldb", Strings.toolchain_pkg_python3_lldb.strOr(context)))
        }
        if (installGdb) infoList.add(PRootBootstrap.PackageInfo("gdb", Strings.toolchain_pkg_gdb.strOr(context)))

        return infoList
    }

    /**
     * 估算安装大小（MB）
     */
    fun estimateSize(): Int {
        var sizeInMB = 0

        // 必装组件（约 160MB）
        if (installClangd) sizeInMB += 30
        if (installLlvm) sizeInMB += 50
        if (installLibcxx) sizeInMB += 20
        if (installCmake) sizeInMB += 15
        if (installNinja) sizeInMB += 5
        if (installMake) sizeInMB += 5
        if (installGit) sizeInMB += 25
        if (installClangFormat) sizeInMB += 10

        // 编译器
        if (installClang) sizeInMB += 300 // Clang 完整工具链
        if (installGcc) sizeInMB += 250 // GCC 完整工具链

        // 链接器
        if (installLld) sizeInMB += 20
        if (installGnuLd) sizeInMB += 30

        // 调试器
        if (installLldb) sizeInMB += 50
        if (installGdb) sizeInMB += 30

        return sizeInMB
    }
}
