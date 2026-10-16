# NDK_TOOLCHAIN_VERSION := 4.9
APP_PLATFORM := android-21
APP_STL := c++_shared
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
# 16 KB page size support (NDK r28 default): link 64-bit libraries with
# -z max-page-size=16384 and hide the fixed PAGE_SIZE macro.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
