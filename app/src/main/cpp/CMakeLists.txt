cmake_minimum_required(VERSION 3.22)
project("ptrace_chroot" C)

add_executable(ptrace_chroot ptrace_chroot.c)

target_compile_options(ptrace_chroot PRIVATE -Wall -Wextra -O2)

# Copy into assets so it can be extracted at runtime with execute permission
add_custom_command(TARGET ptrace_chroot POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
        "${CMAKE_SOURCE_DIR}/../../assets/"
    COMMAND ${CMAKE_COMMAND} -E copy
        "$<TARGET_FILE:ptrace_chroot>"
        "${CMAKE_SOURCE_DIR}/../../assets/libptrace_chroot"
    COMMENT "Packaging ptrace_chroot -> assets/libptrace_chroot"
)
