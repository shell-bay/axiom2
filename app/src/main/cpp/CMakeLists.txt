cmake_minimum_required(VERSION 3.22)
project("ptrace_chroot" C)

add_executable(ptrace_chroot ptrace_chroot.c)

target_compile_options(ptrace_chroot PRIVATE -Wall -Wextra -O2 -s)

# Copy into source jniLibs so Android packages it as a native lib
add_custom_command(TARGET ptrace_chroot POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
        "${CMAKE_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI}/"
    COMMAND ${CMAKE_COMMAND} -E copy
        "$<TARGET_FILE:ptrace_chroot>"
        "${CMAKE_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI}/libptrace_chroot.so"
    COMMENT "Packaging ptrace_chroot -> jniLibs/${ANDROID_ABI}/libptrace_chroot.so"
)
