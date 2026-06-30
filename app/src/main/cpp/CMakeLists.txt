cmake_minimum_required(VERSION 3.22)
project("ptrace_chroot" C)

add_executable(ptrace_chroot ptrace_chroot.c)

target_compile_options(ptrace_chroot PRIVATE -Wall -Wextra -O2)
