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
#include <signal.h>
#include <stdint.h>
#include <fcntl.h>
#include <elf.h>

static int verbose = 0;
#define LOG(fmt, ...) do { if (verbose) fprintf(stderr, "[ptrace_chroot] " fmt "\n", ##__VA_ARGS__); } while (0)
#define DIE(fmt, ...) do { fprintf(stderr, "[ptrace_chroot] FATAL: " fmt "\n", ##__VA_ARGS__); exit(1); } while (0)
#define MAX_PATH 4096
#define MAX_BINDS 64
#define SCRATCH_OFF 0x10000
#define SCRATCH_OFF 0x10000

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

/* Memory access via PTRACE_PEEKTEXT/PTRACE_POKETEXT (more portable than /proc/pid/mem) */
static uint64_t peek_word(pid_t child, uint64_t addr) {
    errno = 0;
    return ptrace(PTRACE_PEEKTEXT, child, (void*)(uintptr_t)addr, NULL);
}

static int poke_word(pid_t child, uint64_t addr, uint64_t word) {
    errno = 0;
    return ptrace(PTRACE_POKETEXT, child, (void*)(uintptr_t)addr, (void*)(uintptr_t)word);
}

static int child_read_string(pid_t child, uint64_t addr, char *buf, size_t bufsz) {
    size_t total = 0;
    uint64_t aligned = addr & ~7ULL;
    size_t skip = addr - aligned;
    while (total < bufsz - 1) {
        uint64_t word = peek_word(child, aligned);
        if (errno) break;
        for (size_t i = skip; i < 8 && total < bufsz - 1; i++) {
            char c = (word >> (i * 8)) & 0xff;
            buf[total++] = c;
            if (c == '\0') return total;
        }
        aligned += 8;
        skip = 0;
    }
    buf[total] = '\0';
    return total;
}

static int write_child_mem(pid_t child, uint64_t addr, const char *buf, size_t len) {
    size_t written = 0;
    while (written < len) {
        uint64_t word = 0;
        /* Handle unaligned start: read-modify-write first partial word */
        uint64_t aligned = (addr + written) & ~7ULL;
        size_t offset = (addr + written) - aligned;
        if (offset != 0) {
            word = peek_word(child, aligned);
            if (errno) return -1;
        }
        for (size_t i = offset; i < 8 && written < len; i++) {
            unsigned char c = (unsigned char)buf[written++];
            word &= ~((uint64_t)0xff << (i * 8));
            word |= ((uint64_t)c << (i * 8));
        }
        if (poke_word(child, aligned, word) != 0) return -1;
    }
    return (int)len;
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

static void handle_execve(struct config *cfg, pid_t child,
                           struct user_regs_struct *regs, uint64_t sp)
{
    uint64_t path_ptr = regs->regs[0];
    uint64_t argv_ptr = regs->regs[1];
    char orig[MAX_PATH];
    if (!child_read_string(child, path_ptr, orig, sizeof(orig))) return;
    if (orig[0] != '/') return;

    char translated[MAX_PATH];
    if (!translate_path(cfg, orig, translated, sizeof(translated))) return;

    uint64_t scratch_base = sp - SCRATCH_OFF;

    if (cfg->ld_path[0] && is_dynamically_linked(translated)) {
        uint64_t orig_argv_ptrs[256];
        int argc = 0;
        while (argc < 256) {
            uint64_t a = peek_word(child, argv_ptr + argc * sizeof(uint64_t));
            if (errno) break;
            if (a == 0) break;
            orig_argv_ptrs[argc++] = a;
        }

        char *orig_args[256];
        int i = 0;
        for (; i < argc; i++) {
            orig_args[i] = malloc(MAX_PATH);
            if (!orig_args[i] || child_read_string(child, orig_argv_ptrs[i], orig_args[i], MAX_PATH) <= 0) break;
        }
        int real_argc = i;

        /* Layout in scratch area: ld_path, orig_path, arg_strings, argv_array */
        uint64_t wp = scratch_base;
        size_t l = strlen(cfg->ld_path) + 1;
        write_child_mem(child, wp, cfg->ld_path, l);
        uint64_t ld_str = wp;
        wp += ((l + 15) & ~15ULL);

        l = strlen(orig) + 1;
        write_child_mem(child, wp, orig, l);
        uint64_t orig_str = wp;
        wp += ((l + 15) & ~15ULL);

        uint64_t arg_strs[256];
        for (i = 0; i < real_argc; i++) {
            l = strlen(orig_args[i]) + 1;
            write_child_mem(child, wp, orig_args[i], l);
            arg_strs[i] = wp;
            wp += ((l + 15) & ~15ULL);
        }

        wp = ((wp + 15) & ~15ULL);
        uint64_t argv_array = wp;
        uint64_t tmp = ld_str;
        write_child_mem(child, wp, (char*)&tmp, sizeof(tmp)); wp += sizeof(uint64_t);
        tmp = orig_str;
        write_child_mem(child, wp, (char*)&tmp, sizeof(tmp)); wp += sizeof(uint64_t);
        for (i = 0; i < real_argc; i++) {
            tmp = arg_strs[i];
            write_child_mem(child, wp, (char*)&tmp, sizeof(tmp)); wp += sizeof(uint64_t);
        }
        tmp = 0;
        write_child_mem(child, wp, (char*)&tmp, sizeof(tmp));

        regs->regs[0] = ld_str;
        regs->regs[1] = argv_array;

        for (i = 0; i < real_argc; i++) free(orig_args[i]);
        LOG("execve wrapped: %s -> %s via ld-linux", orig, translated);
    } else {
        size_t l = strlen(translated) + 1;
        write_child_mem(child, scratch_base, translated, l);
        regs->regs[0] = scratch_base;
        LOG("execve direct: %s -> %s", orig, translated);
    }
}

static int handle_path_syscall(struct config *cfg, pid_t child,
                                struct user_regs_struct *regs, uint64_t sp,
                                int arg_reg, int slot)
{
    uint64_t path_ptr = regs->regs[arg_reg];
    char orig[MAX_PATH];
    if (!child_read_string(child, path_ptr, orig, sizeof(orig))) return 0;
    if (orig[0] != '/') return 0;

    char translated[MAX_PATH];
    if (!translate_path(cfg, orig, translated, sizeof(translated))) return 0;
    if (strcmp(orig, translated) == 0) return 0;

    size_t l = strlen(translated) + 1;
    /* Each slot is 4096 bytes within SCRATCH_OFF area below SP */
    uint64_t target = sp - SCRATCH_OFF + slot * MAX_PATH;
    if (!write_child_mem(child, target, translated, l)) return 0;
    regs->regs[arg_reg] = target;
    return 1;
}

enum { ENTER, EXIT };

static int handle_syscall(struct config *cfg, pid_t child,
                           struct user_regs_struct *regs, int phase)
{
    int64_t sysno = (int64_t)regs->regs[8];
    int modified = 0;
    uint64_t sp = regs->sp;

    if (phase == ENTER) {
        switch (sysno) {
            case 56: /* openat: x0=dirfd, x1=pathname */
                modified |= handle_path_syscall(cfg, child, regs, sp, 1, 0);
                break;
            case 291: /* statx: x0=dirfd, x1=pathname */
                if ((int64_t)regs->regs[0] == AT_FDCWD)
                    modified |= handle_path_syscall(cfg, child, regs, sp, 1, 0);
                break;
            case 79:  /* newfstatat */
                /* fall through */
            case 48:  /* faccessat */
                /* fall through */
            case 78:  /* readlinkat */
                /* fall through */
            case 34:  /* mkdirat */
                /* fall through */
            case 35:  /* unlinkat */
                /* fall through */
            case 49:  /* chdir */
                if ((int64_t)regs->regs[0] == AT_FDCWD)
                    modified |= handle_path_syscall(cfg, child, regs, sp, 1, 0);
                break;
            case 221: /* execve: x0=pathname */
                handle_execve(cfg, child, regs, sp);
                modified = 1;
                break;
            case 36: /* symlinkat: x0=target, x1=newdirfd, x2=linkpath */
                modified |= handle_path_syscall(cfg, child, regs, sp, 0, 0);
                modified |= handle_path_syscall(cfg, child, regs, sp, 2, 1);
                break;
            case 37: /* linkat: x0=olddirfd, x1=oldpath, x2=newdirfd, x3=newpath */
                /* fall through */
            case 38: /* renameat */
                if ((int64_t)regs->regs[0] == AT_FDCWD)
                    modified |= handle_path_syscall(cfg, child, regs, sp, 1, 0);
                if ((int64_t)regs->regs[2] == AT_FDCWD)
                    modified |= handle_path_syscall(cfg, child, regs, sp, 3, 1);
                break;
            case 147: /* setresuid */
                /* fall through */
            case 149: /* setresgid */
                if (cfg->fake_root) {
                    regs->regs[8] = -1;
                    modified = 1;
                }
                break;
        }
    } else { /* EXIT */
        if (cfg->fake_root) {
            switch (sysno) {
                case 174: /* getuid */
                /* fall through */
                case 175: /* geteuid */
                /* fall through */
                case 176: /* getgid */
                /* fall through */
                case 177: /* getegid */
                /* fall through */
                case 147: /* setresuid */
                /* fall through */
                case 149: /* setresgid */
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

static void tracer_main(struct config *cfg, pid_t child) {
    int status;
    int stop_count = 0;

    /* Wait for initial SIGSTOP from child */
    waitpid(child, &status, 0);
    LOG("initial stop status 0x%x", status);

    if (ptrace(PTRACE_SETOPTIONS, child, NULL,
               (void*)(long)(PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEEXEC)) != 0) {
        LOG("SETOPTIONS failed: %s", strerror(errno));
    }

    /* Start the child */
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
            LOG("PTRACE_EVENT_EXEC");
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

        int modified = handle_syscall(cfg, child, &regs, phase);

        if (modified) {
            if (ptrace(PTRACE_SETREGSET, child, NT_PRSTATUS, &iov) != 0) {
                LOG("SETREGSET failed: %s", strerror(errno));
            }
        }

        ptrace(PTRACE_SYSCALL, child, NULL, 0);
    }
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
