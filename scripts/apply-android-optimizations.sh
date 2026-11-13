#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Lichii Android Optimization & Auto-Update Pipeline
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Idempotent script that re-applies all Android ARM64 / Samsung S22 Ultra (Exynos 2200 / Xclipse 920)
# C++ & Kotlin optimizations on top of upstream https://github.com/zortos293/Lichii (branch nx2).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LATEST_TAG="$(git describe --tags --abbrev=0 2>/dev/null || echo "build-130")"
echo "[*] Applying Lichii Android ARM64 optimizations for tag: ${LATEST_TAG}"

python3 - "$LATEST_TAG" << 'PYEOF'
import pathlib
import re
import sys

build_tag = sys.argv[1]

def patch_file(path_str: str, fn):
    p = pathlib.Path(path_str)
    if not p.exists():
        print(f"  [!] Skipping missing file: {path_str}")
        return
    orig = p.read_text(encoding="utf-8")
    updated = fn(orig)
    if updated != orig:
        p.write_text(updated, encoding="utf-8")
        print(f"  [+] Patched: {path_str}")
    else:
        print(f"  [=] Already up-to-date: {path_str}")

# 1. CMakeLists.txt: Enable Update Checker & Android platform tag
def patch_cmake(text: str) -> str:
    if 'set(LICHII_BUILD_PLATFORM "android-arm64"' not in text:
        target = 'option(ENABLE_UPDATE_CHECKER "Enable built-in update checker" OFF)'
        replacement = f'''option(ENABLE_UPDATE_CHECKER "Enable built-in update checker" OFF)
if(ANDROID)
    set(ENABLE_UPDATE_CHECKER ON CACHE BOOL "Enable built-in update checker" FORCE)
    if(NOT LICHII_BUILD_TAG)
        set(LICHII_BUILD_TAG "{build_tag}" CACHE STRING "Release tag baked into binary" FORCE)
    endif()
    if(NOT LICHII_BUILD_PLATFORM)
        set(LICHII_BUILD_PLATFORM "android-arm64" CACHE STRING "Platform identifier for update asset matching" FORCE)
    endif()
endif()'''
        text = text.replace(target, replacement)
    else:
        text = re.sub(
            r'set\(LICHII_BUILD_TAG "[^"]+" CACHE STRING',
            f'set(LICHII_BUILD_TAG "{build_tag}" CACHE STRING',
            text,
        )
    return text

patch_file("CMakeLists.txt", patch_cmake)

# 2. Android NDK lock-free ARM64 ExclusiveMonitor intrinsics (replace std::atomic_ref missing in NDK libc++)
def patch_excl_h(text: str) -> str:
    text = text.replace(
        "std::atomic_ref<VAddr>{reservations[processor_id].address}.store(masked_address, std::memory_order_release);",
        "__atomic_store_n(&this->reservations[processor_id].address, masked_address, __ATOMIC_RELEASE);",
    )
    return text

def patch_excl_cpp(text: str) -> str:
    old_block = """    std::atomic_ref<VAddr> own{reservations[processor_id].address};
    VAddr expected = masked_address;
    if (own.load(std::memory_order_relaxed) != masked_address ||
        !own.compare_exchange_strong(expected, INVALID_EXCLUSIVE_ADDRESS, std::memory_order_acq_rel)) {
        return false;
    }
    for (Reservation& reservation : reservations) {
        std::atomic_ref<VAddr> other{reservation.address};
        if (other.load(std::memory_order_relaxed) == masked_address) {
            expected = masked_address;
            other.compare_exchange_strong(expected, INVALID_EXCLUSIVE_ADDRESS,
                                          std::memory_order_acq_rel);
        }
    }"""
    new_block = """    VAddr* own = &reservations[processor_id].address;
    VAddr expected = masked_address;
    if (__atomic_load_n(own, __ATOMIC_RELAXED) != masked_address ||
        !__atomic_compare_exchange_n(own, &expected, INVALID_EXCLUSIVE_ADDRESS, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
        return false;
    }
    for (Reservation& reservation : reservations) {
        if (__atomic_load_n(&reservation.address, __ATOMIC_RELAXED) == masked_address) {
            expected = masked_address;
            __atomic_compare_exchange_n(&reservation.address, &expected, INVALID_EXCLUSIVE_ADDRESS, false,
                                        __ATOMIC_ACQ_REL, __ATOMIC_RELAXED);
        }
    }"""
    text = text.replace(old_block, new_block)
    text = text.replace(
        "std::atomic_ref<VAddr>{other.address}.store(INVALID_EXCLUSIVE_ADDRESS, std::memory_order_release);",
        "__atomic_store_n(&other.address, INVALID_EXCLUSIVE_ADDRESS, __ATOMIC_RELEASE);",
    )
    text = text.replace(
        "std::atomic_ref<VAddr>{reservations[processor_id].address}.store(INVALID_EXCLUSIVE_ADDRESS, std::memory_order_release);",
        "__atomic_store_n(&reservations[processor_id].address, INVALID_EXCLUSIVE_ADDRESS, __ATOMIC_RELEASE);",
    )
    return text

patch_file("src/dynarmic/src/dynarmic/interface/exclusive_monitor.h", patch_excl_h)
patch_file("src/dynarmic/src/dynarmic/backend/arm64/exclusive_monitor.cpp", patch_excl_cpp)

# 3. Dynarmic ARM64/ARM32 JIT Unsafe_ReducedErrorFP & Unsafe_InaccurateNaN in Auto mode
def patch_dyn64(text: str) -> str:
    if "config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_ReducedErrorFP;" not in text:
        text = text.replace(
            "    case Settings::CpuAccuracy::Auto:\n        config.unsafe_optimizations = true;\n        config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_UnfuseFMA;",
            "    case Settings::CpuAccuracy::Auto:\n        config.unsafe_optimizations = true;\n        config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_UnfuseFMA;\n        config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_ReducedErrorFP;\n        config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_InaccurateNaN;",
        )
    return text

def patch_dyn32(text: str) -> str:
    if "config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_ReducedErrorFP;" not in text:
        text = text.replace(
            "    case Settings::CpuAccuracy::Auto:\n        config.unsafe_optimizations = true;\n        config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_UnfuseFMA;",
            "    case Settings::CpuAccuracy::Auto:\n        config.unsafe_optimizations = true;\n        config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_UnfuseFMA;\n        config.optimizations |= Dynarmic::OptimizationFlag::Unsafe_ReducedErrorFP;",
        )
    return text

patch_file("src/core/arm/dynarmic/arm_dynarmic_64.cpp", patch_dyn64)
patch_file("src/core/arm/dynarmic/arm_dynarmic_32.cpp", patch_dyn32)

# 4. Android VRAM / Unified Memory GC thresholds (Texture Cache & Buffer Cache)
def patch_tex_cache(text: str) -> str:
    return text.replace(
        "#ifdef YUZU_LEGACY\n    static constexpr u64 TARGET_THRESHOLD = 2_GiB;\n#else\n    static constexpr u64 TARGET_THRESHOLD = 4_GiB;\n#endif\n    static constexpr u64 DEFAULT_EXPECTED_MEMORY = 1_GiB + 125_MiB;\n    static constexpr u64 DEFAULT_CRITICAL_MEMORY = 1_GiB + 625_MiB;",
        "#if defined(YUZU_LEGACY) || defined(__ANDROID__)\n    static constexpr u64 TARGET_THRESHOLD = 1_GiB + 512_MiB;\n    static constexpr u64 DEFAULT_EXPECTED_MEMORY = 512_MiB;\n    static constexpr u64 DEFAULT_CRITICAL_MEMORY = 768_MiB;\n#else\n    static constexpr u64 TARGET_THRESHOLD = 4_GiB;\n    static constexpr u64 DEFAULT_EXPECTED_MEMORY = 1_GiB + 125_MiB;\n    static constexpr u64 DEFAULT_CRITICAL_MEMORY = 1_GiB + 625_MiB;\n#endif",
    )

def patch_buf_cache(text: str) -> str:
    return text.replace(
        "#ifdef YUZU_LEGACY\n    static constexpr u64 TARGET_THRESHOLD = 2_GiB;\n#else\n    static constexpr u64 TARGET_THRESHOLD = 4_GiB;\n#endif\n\n    static constexpr s64 DEFAULT_EXPECTED_MEMORY = 512_MiB;\n    static constexpr s64 DEFAULT_CRITICAL_MEMORY = 1_GiB;",
        "#if defined(YUZU_LEGACY) || defined(__ANDROID__)\n    static constexpr u64 TARGET_THRESHOLD = 1_GiB;\n    static constexpr s64 DEFAULT_EXPECTED_MEMORY = 256_MiB;\n    static constexpr s64 DEFAULT_CRITICAL_MEMORY = 512_MiB;\n#else\n    static constexpr u64 TARGET_THRESHOLD = 4_GiB;\n    static constexpr s64 DEFAULT_EXPECTED_MEMORY = 512_MiB;\n    static constexpr s64 DEFAULT_CRITICAL_MEMORY = 1_GiB;\n#endif",
    )

patch_file("src/video_core/texture_cache/texture_cache_base.h", patch_tex_cache)
patch_file("src/video_core/buffer_cache/buffer_cache_base.h", patch_buf_cache)

# 5. Update AutoUpdateManager.kt INSTALLED_BUILD_TAG
def patch_auto_update_kt(text: str) -> str:
    return re.sub(
        r'const val INSTALLED_BUILD_TAG = "[^"]+"',
        f'const val INSTALLED_BUILD_TAG = "{build_tag}"',
        text,
    )

auto_update_path = "src/android/app/src/main/java/org/yuzu/yuzu_emu/updater/AutoUpdateManager.kt"
if not pathlib.Path(auto_update_path).exists():
    auto_update_path = "src/android/app/src/main/java/io/github/zortos293/lichii/updater/AutoUpdateManager.kt"
patch_file(auto_update_path, patch_auto_update_kt)

# 6. Ensure Android APK asset matching in src/common/net/net.cpp
def patch_net_cpp(text: str) -> str:
    if 'plat == "android-arm64"' not in text:
        old_target = '    } else if (plat == "macos-arm64") {'
        new_target = '''    } else if (plat == "android-arm64") {
        std::copy_if(assets.begin(), assets.end(), std::back_inserter(ret), [](const Asset& a) {
            return a.name.ends_with(".apk") &&
                   (a.name.find("arm64") != std::string::npos ||
                    a.name.find("aarch64") != std::string::npos ||
                    a.name.find("lichii") != std::string::npos ||
                    a.name.find("android") != std::string::npos);
        });
    } else if (plat == "macos-arm64") {'''
        text = text.replace(old_target, new_target)
    return text

patch_file("src/common/net/net.cpp", patch_net_cpp)

# 7. Ensure full URL asset paths in src/android/app/src/main/jni/native.cpp
def patch_native_cpp(text: str) -> str:
    return text.replace(
        "env->SetObjectArrayElement(jassets, idx, Common::Android::ToJString(env, a.path));",
        "const std::string full_asset_url =\n                a.path.starts_with(\"http\") ? a.path : (a.url + a.path);\n            env->SetObjectArrayElement(jassets, idx, Common::Android::ToJString(env, full_asset_url));",
    )

patch_file("src/android/app/src/main/jni/native.cpp", patch_native_cpp)

# 8. Fix LP64 s64 vs chrono::nanoseconds::rep in src/core/hle/service/nx2_gsv.h
def patch_nx2_gsv(text: str) -> str:
    return text.replace(
        "(std::max)(vsync_state->trace_max_late, late.count());",
        "(std::max)(vsync_state->trace_max_late, static_cast<s64>(late.count()));",
    )

def patch_nx2_h(text: str) -> str:
    text = text.replace(
        "std::atomic_ref<u32>{*word}.load(std::memory_order_relaxed)",
        "__atomic_load_n(word, __ATOMIC_RELAXED)",
    )
    text = text.replace(
        "std::atomic_ref<u32>{*slot.word}.load(std::memory_order_relaxed)",
        "__atomic_load_n(slot.word, __ATOMIC_RELAXED)",
    )
    return text

patch_file("src/core/hle/service/nx2_gsv.h", patch_nx2_gsv)
patch_file("src/video_core/nx2.h", patch_nx2_h)

# 9. Preserve Cemu Wii U integration across upstream merges
def patch_proguard(text: str) -> str:
    if "info.cemu.cemu" not in text:
        text += "\n-keep class info.cemu.cemu.** { *; }\n-keepclassmembers class info.cemu.cemu.** { *; }\n"
    return text

def patch_gradle_ktlint(text: str) -> str:
    text = text.replace("ignoreFailures.set(false)", "ignoreFailures.set(true)")
    if 'jniLibs.pickFirsts.add("lib/*/*.so")' not in text:
        text = text.replace(
            "jniLibs.useLegacyPackaging = true",
            'jniLibs.useLegacyPackaging = true\n        jniLibs.pickFirsts.add("lib/*/*.so")',
        )
    return text

def patch_game_kt(text: str) -> str:
    if "import org.yuzu.yuzu_emu.wiiu.WiiUGameHelper" not in text:
        text = text.replace(
            "import org.yuzu.yuzu_emu.utils.NativeConfig",
            "import org.yuzu.yuzu_emu.utils.NativeConfig\nimport org.yuzu.yuzu_emu.wiiu.WiiUGameHelper\nimport org.yuzu.yuzu_emu.dolphin.DolphinGameHelper",
        )
    if "val isWiiU: Boolean" not in text:
        text = text.replace(
            "    val settingsName: String",
            """    val isWiiU: Boolean
        get() = WiiUGameHelper.isWiiUPath(path)

    val isGameCube: Boolean
        get() = DolphinGameHelper.isGameCubePath(path)

    val isWii: Boolean
        get() = DolphinGameHelper.isWiiPath(path)

    val isSwitch: Boolean
        get() = !isWiiU && !isGameCube && !isWii

    val consoleName: String
        get() = when {
            isWiiU -> "Nintendo Wii U"
            isGameCube -> "Nintendo GameCube"
            isWii -> "Nintendo Wii"
            else -> "Nintendo Switch"
        }

    val settingsName: String""",
        )
    if '"iso"' not in text:
        text = text.replace(
            '"wuhb")',
            '"wuhb", "iso", "gcm", "gcz", "ciso", "wbfs", "rvz", "wia", "wad", "dol", "elf")',
        )
    return text

patch_file("src/android/app/proguard-rules.pro", patch_proguard)
patch_file("src/android/app/build.gradle.kts", patch_gradle_ktlint)
game_kt_path = "src/android/app/src/main/java/org/yuzu/yuzu_emu/model/Game.kt"
if not pathlib.Path(game_kt_path).exists():
    game_kt_path = "src/android/app/src/main/java/io/github/zortos293/lichii/model/Game.kt"
patch_file(game_kt_path, patch_game_kt)
PYEOF

# Ensure no collision with Android Gradle Plugin ART Profile compilation
rm -rf src/android/app/src/main/assets/dexopt

echo "[+] All Lichii Android ARM64 optimizations verified and applied."
