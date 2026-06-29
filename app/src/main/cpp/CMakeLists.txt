cmake_minimum_required(VERSION 3.22)
project("ptrace_chroot" C)

add_executable(ptrace_chroot ptrace_chroot.c)

target_compile_options(ptrace_chroot PRIVATE -Wall -Wextra -O2 -s)

# Copy to library output dir with .so extension so AGP packages it
add_custom_command(TARGET ptrace_chroot POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy
        "$<TARGET_FILE:ptrace_chroot>"
        "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libptrace_chroot.so"
    COMMENT "Packaging ptrace_chroot as libptrace_chroot.so"
)
