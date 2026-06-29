#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/user.h>
#include <sys/uio.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <elf.h>

static int verbose = 0;
#define LOG(fmt, ...) do { if (verbose) fprintf(stderr, "[ptrace_chroot] " fmt "\n", ##__VA_ARGS__); } while (0)
#define DIE(fmt, ...) do { fprintf(stderr, "[ptrace_chroot] FATAL: " fmt "\n", ##__VA_ARGS__); exit(1); } while (0)
#define MAX_PATH 4096
#define MAX_BINDS 64
#define SCRATCH_SIZE 0x10000
#define AT_FDCWD (-100)

struct bind_mount {
    char src[MAX_PATH];
    char dst[MAX_PATH];
    size_t dst_len;
};

struct config {
    char rootfs[MAX_PATH];
    size_t rootfs_len;
    char cwd[MAX_PATH];
    int fake_root;
    struct bind_mount binds[MAX_BINDS];
    int nbinds;
    char ld_path[MAX_PATH];
};

struct scratch {
    uint64_t base;
    uint64_t pos;
    uint64_t end;
};

static const char *skip_prefix(const char *str, const char *prefix) {
    size_t plen = strlen(prefix);
    return (strncmp(str, prefix, plen) == 0) ? str + plen : NULL;
}

static int is_pass_through(const char *path) {
    static const char *passthru[] = {"/proc/", "/sys/", "/dev/", "/system/", "/vendor/", NULL};
    for (int i = 0; passthru[i]; i++)
        if (strncmp(path, passthru[i], strlen(passthru[i])) == 0) return 1;
    return 0;
}

static int translate_path(struct config *cfg, const char *orig, char *out, size_t outsz) {
    if (!orig || orig[0] != '/') { strncpy(out, orig, outsz); return 0; }
    for (int i = 0; i < cfg->nbinds; i++) {
        const char *rest = skip_prefix(orig, cfg->binds[i].dst);
        if (rest) { snprintf(out, outsz, "%s%s", cfg->binds[i].src, rest); return 1; }
    }
    if (is_pass_through(orig)) { strncpy(out, orig, outsz); return 1; }
    snprintf(out, outsz, "%s%s", cfg->rootfs, orig);
    return 1;
}

static ssize_t read_child_mem(int memfd, uint64_t addr, char *buf, size_t len) {
    return pread(memfd, buf, len, (off_t)addr);
}

static ssize_t write_child_mem(int memfd, uint64_t addr, const char *buf, size_t len) {
    return pwrite(memfd, buf, len, (off_t)addr);
}

static int child_read_string(int memfd, uint64_t addr, char *buf, size_t bufsz) {
    size_t total = 0;
    while (total < bufsz - 1) {
        char c;
        if (pread(memfd, &c, 1, (off_t)(addr + total)) != 1) break;
        buf[total++] = c;
        if (c == '\0') return total;
    }
    buf[total] = '\0';
    return total;
}

static uint64_t scratch_alloc(struct scratch *sc, size_t size) {
    uint64_t ret = sc->pos;
    size = (size + 15) & ~15ULL;
    if (ret + size > sc->end) return 0;
    sc->pos = ret + size;
    return ret;
}

static uint64_t scratch_put_string(struct scratch *sc, int memfd, const char *str) {
    size_t len = strlen(str) + 1;
    uint64_t addr = scratch_alloc(sc, len);
    if (!addr) return 0;
    if (write_child_mem(memfd, addr, str, len) != (ssize_t)len) return 0;
    return addr;
}

static uint64_t find_scratch_base(pid_t child) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", child);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[512];
    uint64_t stack_start = 0, stack_end = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "[stack]")) {
            char *dash = strchr(line, '-');
            if (dash) {
                *dash = '\0';
                stack_start = strtoull(line, NULL, 16);
                char *space = strchr(dash + 1, ' ');
                if (space) *space = '\0';
                stack_end = strtoull(dash + 1, NULL, 16);
            }
            break;
        }
    }
    fclose(f);
    if (stack_start == 0) {
        LOG("no [stack] in maps");
        return 0;
    }
    // Use area near the bottom of the stack (safe 4KB above start)
    LOG("stack: 0x%llx-0x%llx", (unsigned long long)stack_start, (unsigned long long)stack_end);
    return stack_start + 4096;
}

static int is_dynamically_linked(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    Elf64_Ehdr ehdr;
    if (read(fd, &ehdr, sizeof(ehdr)) != (ssize_t)sizeof(ehdr)) { close(fd); return 0; }
    if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) { close(fd); return 0; }
    if (ehdr.e_type != ET_EXEC && ehdr.e_type != ET_DYN) { close(fd); return 0; }
    for (int i = 0; i < ehdr.e_phnum; i++) {
        Elf64_Phdr phdr;
        off_t off = (off_t)(ehdr.e_phoff + i * ehdr.e_phentsize);
        if (pread(fd, &phdr, sizeof(phdr), off) != (ssize_t)sizeof(phdr)) break;
        if (phdr.p_type == PT_INTERP) { close(fd); return 1; }
    }
    close(fd);
    return 0;
}

static void find_ld_linker(struct config *cfg) {
    const char *candidates[] = {
        "/lib/ld-linux-aarch64.so.1", "/lib/ld-linux.so.3",
        "/lib/ld-linux.so.2", "/lib64/ld-linux-x86-64.so.2",
        "/lib/ld-musl-aarch64.so.1", "/lib/ld-musl-arm.so.1", NULL
    };
    for (int i = 0; candidates[i]; i++) {
        char path[MAX_PATH];
        snprintf(path, sizeof(path), "%s%s", cfg->rootfs, candidates[i]);
        if (access(path, F_OK) == 0) {
            strncpy(cfg->ld_path, path, sizeof(cfg->ld_path) - 1);
            LOG("found dynamic linker: %s", cfg->ld_path);
            return;
        }
    }
    cfg->ld_path[0] = '\0';
}

static void parse_args(struct config *cfg, int argc, char **argv) {
    memset(cfg, 0, sizeof(*cfg));
    int i = 1;
    while (i < argc && argv[i][0] == '-') {
        if (strcmp(argv[i], "-r") == 0 && i + 1 < argc) {
            strncpy(cfg->rootfs, argv[++i], sizeof(cfg->rootfs) - 1);
            cfg->rootfs_len = strlen(cfg->rootfs);
            while (cfg->rootfs_len > 1 && cfg->rootfs[cfg->rootfs_len - 1] == '/')
                cfg->rootfs[--cfg->rootfs_len] = '\0';
        } else if (strcmp(argv[i], "-0") == 0) {
            cfg->fake_root = 1;
        } else if (strcmp(argv[i], "-w") == 0 && i + 1 < argc) {
            strncpy(cfg->cwd, argv[++i], sizeof(cfg->cwd) - 1);
        } else if (strcmp(argv[i], "-b") == 0 && i + 1 < argc) {
            char *bind = argv[++i];
            char *colon = strchr(bind, ':');
            if (colon) {
                size_t srclen = colon - bind;
                if (srclen >= sizeof(cfg->binds[cfg->nbinds].src)) srclen = sizeof(cfg->binds[cfg->nbinds].src) - 1;
                memcpy(cfg->binds[cfg->nbinds].src, bind, srclen);
                cfg->binds[cfg->nbinds].src[srclen] = '\0';
                strncpy(cfg->binds[cfg->nbinds].dst, colon + 1, sizeof(cfg->binds[cfg->nbinds].dst) - 1);
            } else {
                strncpy(cfg->binds[cfg->nbinds].src, bind, sizeof(cfg->binds[cfg->nbinds].src) - 1);
                strncpy(cfg->binds[cfg->nbinds].dst, bind, sizeof(cfg->binds[cfg->nbinds].dst) - 1);
            }
            cfg->binds[cfg->nbinds].dst_len = strlen(cfg->binds[cfg->nbinds].dst);
            cfg->nbinds++;
        } else if (strcmp(argv[i], "-v") == 0) {
            verbose = 1;
        } else {
            DIE("unknown option: %s", argv[i]);
        }
        i++;
    }
    if (i >= argc) DIE("no command specified");
    if (cfg->rootfs[0] == '\0') DIE("no rootfs specified (-r)");
    if (cfg->rootfs[cfg->rootfs_len - 1] != '/') { strcat(cfg->rootfs, "/"); cfg->rootfs_len++; }
    find_ld_linker(cfg);
}

static void setup_child_cwd(struct config *cfg) {
    if (cfg->cwd[0] && cfg->rootfs[0]) {
        char cwd_path[MAX_PATH];
        snprintf(cwd_path, sizeof(cwd_path), "%s%s", cfg->rootfs, cfg->cwd);
        chdir(cwd_path);
    }
}

static void handle_execve(struct config *cfg, int memfd,
                          struct user_regs_struct *regs, struct scratch *sc)
{
    uint64_t path_ptr = regs->regs[0];
    uint64_t argv_ptr = regs->regs[1];
    char orig[MAX_PATH];
    if (!child_read_string(memfd, path_ptr, orig, sizeof(orig))) return;
    if (orig[0] != '/') return;

    char translated[MAX_PATH];
    if (!translate_path(cfg, orig, translated, sizeof(translated))) return;

    if (cfg->ld_path[0] && is_dynamically_linked(translated)) {
        // Wrap with ld-linux: execve(ld_path, [ld_path, orig, argv[0], ...], envp)
        // Count original argv
        uint64_t orig_argv_ptrs[256];
        int argc = 0;
        while (argc < 256) {
            uint64_t a;
            if (read_child_mem(memfd, argv_ptr + argc * sizeof(uint64_t), (char*)&a, sizeof(a)) != sizeof(a)) break;
            if (a == 0) break;
            orig_argv_ptrs[argc++] = a;
        }

        // Read original arg strings
        char *orig_args[256];
        int i = 0;
        for (; i < argc; i++) {
            orig_args[i] = malloc(MAX_PATH);
            if (!orig_args[i] || child_read_string(memfd, orig_argv_ptrs[i], orig_args[i], MAX_PATH) <= 0) break;
        }
        int real_argc = i;

        // Allocate space for: ld_path string, orig path string, arg strings, argv array
        size_t total = strlen(cfg->ld_path) + 1 + strlen(orig) + 1;
        for (i = 0; i < real_argc; i++) total += strlen(orig_args[i]) + 1;
        total += (real_argc + 2 + 1) * sizeof(uint64_t); // argv array + NULL

        uint64_t buf_base = scratch_alloc(sc, total + 16);
        if (!buf_base) {
            for (i = 0; i < real_argc; i++) free(orig_args[i]);
            return;
        }

        uint64_t wp = buf_base;
        // Write ld_path string
        size_t l = strlen(cfg->ld_path) + 1;
        write_child_mem(memfd, wp, cfg->ld_path, l);
        uint64_t ld_str = wp;
        wp += ((l + 15) & ~15ULL);

        // Write orig path string
        l = strlen(orig) + 1;
        write_child_mem(memfd, wp, orig, l);
        uint64_t orig_str = wp;
        wp += ((l + 15) & ~15ULL);

        // Write original arg strings
        uint64_t arg_strs[256];
        for (i = 0; i < real_argc; i++) {
            l = strlen(orig_args[i]) + 1;
            write_child_mem(memfd, wp, orig_args[i], l);
            arg_strs[i] = wp;
            wp += ((l + 15) & ~15ULL);
        }

        // Build argv array
        wp = ((wp + 15) & ~15ULL);
        uint64_t argv_array = wp;
        uint64_t tmp = ld_str;
        write_child_mem(memfd, wp, (char*)&tmp, sizeof(tmp)); wp += sizeof(uint64_t); // argv[0] = ld_path
        tmp = orig_str;
        write_child_mem(memfd, wp, (char*)&tmp, sizeof(tmp)); wp += sizeof(uint64_t); // argv[1] = orig_path
        for (i = 0; i < real_argc; i++) {
            tmp = arg_strs[i];
            write_child_mem(memfd, wp, (char*)&tmp, sizeof(tmp)); wp += sizeof(uint64_t);
        }
        tmp = 0;
        write_child_mem(memfd, wp, (char*)&tmp, sizeof(tmp)); // NULL terminator

        regs->regs[0] = ld_str;
        regs->regs[1] = argv_array;

        for (i = 0; i < real_argc; i++) free(orig_args[i]);
        LOG("execve wrapped: %s -> %s via ld-linux", orig, translated);
    } else {
        // Direct exec
        uint64_t new_path = scratch_put_string(sc, memfd, translated);
        if (!new_path) return;
        regs->regs[0] = new_path;
        LOG("execve direct: %s -> %s", orig, translated);
    }
}

static int handle_path_syscall(struct config *cfg, int memfd,
                                struct user_regs_struct *regs, struct scratch *sc,
                                int arg_reg)
{
    uint64_t path_ptr = regs->regs[arg_reg];
    char orig[MAX_PATH];
    if (!child_read_string(memfd, path_ptr, orig, sizeof(orig))) return 0;
    if (orig[0] != '/') return 0;

    char translated[MAX_PATH];
    if (!translate_path(cfg, orig, translated, sizeof(translated))) return 0;
    if (strcmp(orig, translated) == 0) return 0;

    uint64_t new_ptr = scratch_put_string(sc, memfd, translated);
    if (!new_ptr) return 0;
    regs->regs[arg_reg] = new_ptr;
    return 1;
}

enum { ENTER, EXIT };

static int handle_syscall(struct config *cfg, int memfd,
                           struct user_regs_struct *regs, struct scratch *sc,
                           int phase)
{
    int64_t sysno = (int64_t)regs->regs[8];
    int modified = 0;

    if (phase == ENTER) {
        switch (sysno) {
            case 56: // openat: x0=dirfd, x1=pathname
                modified |= handle_path_syscall(cfg, memfd, regs, sc, 1);
                break;
            case 291: // statx: x0=dirfd, x1=pathname
                if (regs->regs[0] == AT_FDCWD)
                    modified |= handle_path_syscall(cfg, memfd, regs, sc, 1);
                break;
            case 79: // newfstatat: x0=dirfd, x1=pathname
            case 48: // faccessat
            case 78: // readlinkat
            case 34: // mkdirat
            case 35: // unlinkat
            case 49: // chdir
                if (regs->regs[0] == AT_FDCWD)
                    modified |= handle_path_syscall(cfg, memfd, regs, sc, 1);
                break;
            case 221: // execve: x0=pathname
                handle_execve(cfg, memfd, regs, sc);
                modified = 1;
                break;
            case 36: // symlinkat: x0=target, x1=newdirfd, x2=linkpath
                modified |= handle_path_syscall(cfg, memfd, regs, sc, 0);
                modified |= handle_path_syscall(cfg, memfd, regs, sc, 2);
                break;
            case 37: // linkat: x0=olddirfd, x1=oldpath, x2=newdirfd, x3=newpath
            case 38: // renameat
                if (regs->regs[0] == AT_FDCWD)
                    modified |= handle_path_syscall(cfg, memfd, regs, sc, 1);
                if (regs->regs[2] == AT_FDCWD)
                    modified |= handle_path_syscall(cfg, memfd, regs, sc, 3);
                break;
            case 147: // setresuid
            case 149: // setresgid
                if (cfg->fake_root) {
                    regs->regs[8] = -1;
                    modified = 1;
                }
                break;
        }
    } else { // EXIT
        if (cfg->fake_root) {
            switch (sysno) {
                case 174: case 175: case 176: case 177: // getuid/geteuid/getgid/getegid
                case 147: case 149: // setresuid/setresgid (already skipped, override return)
                    if (regs->regs[0] != 0) {
                        regs->regs[0] = 0;
                        modified = 1;
                    }
                    break;
            }
        }
    }
    return modified;
}

static void inject_mmap(pid_t child, struct user_regs_struct *orig_regs, uint64_t *result) {
    struct user_regs_struct mmap_regs;
    struct iovec iov = { .iov_base = &mmap_regs, .iov_len = sizeof(mmap_regs) };
    memcpy(&mmap_regs, orig_regs, sizeof(mmap_regs));
    mmap_regs.regs[8] = 222; // __NR_mmap on aarch64
    mmap_regs.regs[0] = 0;   // addr = NULL
    mmap_regs.regs[1] = SCRATCH_SIZE; // length
    mmap_regs.regs[2] = 3;   // PROT_READ|PROT_WRITE
    mmap_regs.regs[3] = 0x22; // MAP_PRIVATE|MAP_ANONYMOUS
    mmap_regs.regs[4] = -1;  // fd = -1
    mmap_regs.regs[5] = 0;   // offset
    if (ptrace(PTRACE_SETREGSET, child, NT_PRSTATUS, &iov) != 0) {
        LOG("mmap inject: SETREGSET failed: %s", strerror(errno));
        return;
    }
    if (ptrace(PTRACE_SINGLESTEP, child, NULL, 0) != 0) {
        LOG("mmap inject: SINGLESTEP failed: %s", strerror(errno));
        return;
    }
    int status;
    waitpid(child, &status, 0);
    if (WIFSTOPPED(status) && WSTOPSIG(status) == SIGTRAP) {
        struct iovec iov2 = { .iov_base = &mmap_regs, .iov_len = sizeof(mmap_regs) };
        if (ptrace(PTRACE_GETREGSET, child, NT_PRSTATUS, &iov2) == 0) {
            *result = mmap_regs.regs[0];
            LOG("injected mmap result: 0x%llx", (unsigned long long)*result);
        }
    }
    // Restore original registers
    struct iovec iov3 = { .iov_base = orig_regs, .iov_len = sizeof(*orig_regs) };
    ptrace(PTRACE_SETREGSET, child, NT_PRSTATUS, &iov3);
}

static void tracer_main(struct config *cfg, pid_t child) {
    int status;
    int stop_count = 0;
    struct scratch sc = {0};

    char mempath[64];
    snprintf(mempath, sizeof(mempath), "/proc/%d/mem", child);
    int memfd = open(mempath, O_RDWR);

    // Wait for initial SIGSTOP from child
    waitpid(child, &status, 0);
    LOG("initial stop status 0x%x", status);

    if (ptrace(PTRACE_SETOPTIONS, child, NULL,
               (void*)(long)(PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEEXEC)) != 0) {
        LOG("SETOPTIONS failed: %s", strerror(errno));
    }

    // Allocate scratch buffer via mmap injection
    if (memfd >= 0) {
        struct user_regs_struct init_regs;
        struct iovec iov = { .iov_base = &init_regs, .iov_len = sizeof(init_regs) };
        if (ptrace(PTRACE_GETREGSET, child, NT_PRSTATUS, &iov) == 0) {
            uint64_t mmap_result = 0;
            inject_mmap(child, &init_regs, &mmap_result);
            if (mmap_result && mmap_result != (uint64_t)-1) {
                sc.base = mmap_result;
                sc.pos = mmap_result;
                sc.end = mmap_result + SCRATCH_SIZE;
                LOG("scratch buffer at 0x%llx", (unsigned long long)sc.base);
            } else {
                // Fallback: use stack bottom
                uint64_t stack = find_scratch_base(child);
                if (stack) {
                    sc.base = stack - SCRATCH_SIZE;
                    sc.pos = sc.base;
                    sc.end = sc.base + SCRATCH_SIZE;
                    LOG("using stack fallback scratch at 0x%llx", (unsigned long long)sc.base);
                }
            }
        }
    }

    // Start the child
    ptrace(PTRACE_SYSCALL, child, NULL, 0);

    struct user_regs_struct regs;
    struct iovec iov = { .iov_base = &regs, .iov_len = sizeof(regs) };

    while (1) {
        pid_t ret = waitpid(child, &status, 0);
        if (ret <= 0) { if (errno == EINTR) continue; break; }
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            LOG("child exit: %d", WIFEXITED(status) ? WEXITSTATUS(status) : -WTERMSIG(status));
            break;
        }
        if (!WIFSTOPPED(status)) {
            ptrace(PTRACE_SYSCALL, child, NULL, 0);
            continue;
        }

        int sig = WSTOPSIG(status);
        int event = (status >> 16) & 0xffff;

        if (event == PTRACE_EVENT_EXEC) {
            LOG("PTRACE_EVENT_EXEC: re-allocating scratch");
            // After execve, the old mmap is gone. Re-allocate.
            sc.base = 0; sc.pos = 0; sc.end = 0;
            if (memfd >= 0) {
                close(memfd);
                snprintf(mempath, sizeof(mempath), "/proc/%d/mem", child);
                memfd = open(mempath, O_RDWR);
                struct user_regs_struct exec_regs;
                struct iovec iov2 = { .iov_base = &exec_regs, .iov_len = sizeof(exec_regs) };
                if (ptrace(PTRACE_GETREGSET, child, NT_PRSTATUS, &iov2) == 0) {
                    uint64_t mmap_result = 0;
                    inject_mmap(child, &exec_regs, &mmap_result);
                    if (mmap_result && mmap_result != (uint64_t)-1) {
                        sc.base = mmap_result;
                        sc.pos = mmap_result;
                        sc.end = mmap_result + SCRATCH_SIZE;
                        LOG("re-allocated scratch at 0x%llx", (unsigned long long)sc.base);
                    }
                }
            }
            ptrace(PTRACE_SYSCALL, child, NULL, 0);
            continue;
        }

        if (sig != (SIGTRAP | 0x80)) {
            ptrace(PTRACE_SYSCALL, child, NULL, (sig == SIGTRAP) ? 0 : (unsigned long)sig);
            continue;
        }

        stop_count++;
        int phase = (stop_count % 2 == 1) ? ENTER : EXIT;

        if (ptrace(PTRACE_GETREGSET, child, NT_PRSTATUS, &iov) != 0) {
            ptrace(PTRACE_SYSCALL, child, NULL, 0);
            continue;
        }

        // Reset scratch position at each syscall entry
        if (phase == ENTER && sc.base) sc.pos = sc.base;

        int modified = handle_syscall(cfg, memfd, &regs, &sc, phase);

        if (modified) {
            if (ptrace(PTRACE_SETREGSET, child, NT_PRSTATUS, &iov) != 0) {
                LOG("SETREGSET failed: %s", strerror(errno));
            }
        }

        ptrace(PTRACE_SYSCALL, child, NULL, 0);
    }

    if (memfd >= 0) close(memfd);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: ptrace_chroot -r rootfs [-0] [-b src[:dst]] [-w cwd] [-v] command [args...]\n");
        return 1;
    }
    struct config cfg;
    parse_args(&cfg, argc, argv);

    int cmd_start = 1;
    while (cmd_start < argc && argv[cmd_start][0] == '-') {
        if (strcmp(argv[cmd_start], "-r") == 0 ||
            strcmp(argv[cmd_start], "-b") == 0 ||
            strcmp(argv[cmd_start], "-w") == 0) cmd_start += 2;
        else cmd_start++;
    }

    pid_t pid = fork();
    if (pid < 0) DIE("fork failed: %s", strerror(errno));

    if (pid == 0) {
        if (ptrace(PTRACE_TRACEME) < 0)
            DIE("PTRACE_TRACEME failed: %s", strerror(errno));
        raise(SIGSTOP);
        setup_child_cwd(&cfg);
        execvp(argv[cmd_start], argv + cmd_start);
        char full_path[MAX_PATH];
        snprintf(full_path, sizeof(full_path), "%s%s", cfg.rootfs, argv[cmd_start]);
        execv(full_path, argv + cmd_start);
        fprintf(stderr, "exec failed: %s\n", strerror(errno));
        _exit(127);
    }

    tracer_main(&cfg, pid);

    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 1;
}
