/* SPDX-License-Identifier: GPL-2.0-or-later */
/* 
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 */

#include <uapi/asm-generic/errno.h>
#include <pgtable.h>
#include <kpmalloc.h>
#include <linux/err.h>
#include <linux/string.h>
#include <symbol.h>
#include <kallsyms.h>
#include <cache.h>
#include <common.h>
#include <linux/fs.h>
#include <uapi/linux/fs.h>
#include <hotpatch.h>
#include <linux/list.h>
#include <linux/kernel.h>
#include <linux/spinlock.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/rcupdate.h>
#include <linux/rculist.h>

#include "module.h"
#include "relo.h"

#define SZ_128M 0x08000000
#define KPM_MAX_IMAGE_SIZE (4 * 1024 * 1024)
#define KPM_MAX_MEMORY_SIZE (8 * 1024 * 1024)
#define KPM_MAX_SECTIONS 256
#define KPM_MAX_ARGS_LEN 1024

#define ALIGN_MASK(x, mask) (((x) + (mask)) & ~(mask))
#define ALIGN(x, a) ALIGN_MASK(x, (typeof(x))(a)-1)

#define align(X) ALIGN(X, page_size)

#define elf_check_arch(x) ((x)->e_machine == EM_AARCH64)

#define ARCH_SHF_SMALL 0

static inline bool strstarts(const char *str, const char *prefix)
{
    return strncmp(str, prefix, strlen(prefix)) == 0;
}

static char *next_string(char *string, unsigned long *secsize)
{
    while (*secsize && string[0]) {
        string++;
        (*secsize)--;
    }
    while (*secsize && !string[0]) {
        string++;
        (*secsize)--;
    }
    return *secsize ? string : 0;
}

/* Update size with this section: return offset. */
static long get_offset(struct module *mod, unsigned int *size, Elf_Shdr *sechdr, unsigned int section)
{
    long ret = ALIGN(*size, sechdr->sh_addralign ?: 1);
    *size = ret + sechdr->sh_size;
    return ret;
}

static char *get_next_modinfo(const struct load_info *info, const char *tag, char *prev)
{
    char *p;
    unsigned int taglen = strlen(tag);
    Elf_Shdr *infosec = &info->sechdrs[info->index.info];
    unsigned long size = infosec->sh_size;
    char *modinfo = (char *)info->hdr + infosec->sh_offset;
    if (!size || modinfo[size - 1] != '\0') return 0;
    if (prev) {
        size -= prev - modinfo;
        modinfo = next_string(prev, &size);
    }
    for (p = modinfo; p; p = next_string(p, &size)) {
        if (strncmp(p, tag, taglen) == 0 && p[taglen] == '=') return p + taglen + 1;
    }
    return 0;
}

static char *get_modinfo(const struct load_info *info, const char *tag)
{
    return get_next_modinfo(info, tag, 0);
}

static int find_sec(const struct load_info *info, const char *name)
{
    for (int i = 1; i < info->hdr->e_shnum; i++) {
        Elf_Shdr *shdr = &info->sechdrs[i];
        if ((shdr->sh_flags & SHF_ALLOC) && strcmp(info->secstrings + shdr->sh_name, name) == 0) return i;
    }
    return 0;
}

static void *get_sh_base(struct load_info *info, const char *secname)
{
    int idx = find_sec(info, secname);
    if (!idx) return 0;
    Elf_Shdr *infosec = &info->sechdrs[idx];
    void *addr = (void *)info->hdr + infosec->sh_offset;
    return addr;
}

static unsigned long get_sh_size(struct load_info *info, const char *secname)
{
    int idx = find_sec(info, secname);
    if (!idx) return 0;
    Elf_Shdr *infosec = &info->sechdrs[idx];
    return infosec->sh_entsize;
}

static void layout_sections(struct module *mod, struct load_info *info)
{
    static unsigned long const masks[][2] = {
        /* NOTE: all executable code must be the first section in this array; otherwise modify the text_size finder in the two loops below */
        { SHF_EXECINSTR | SHF_ALLOC, ARCH_SHF_SMALL },
        { SHF_ALLOC, SHF_WRITE | ARCH_SHF_SMALL },
        { SHF_WRITE | SHF_ALLOC, ARCH_SHF_SMALL },
        { ARCH_SHF_SMALL | SHF_ALLOC, 0 }
    };

    for (int i = 0; i < info->hdr->e_shnum; i++)
        info->sechdrs[i].sh_entsize = ~0UL;

    // todo: tslf alloc all rwx and not page aligned
    for (int m = 0; m < sizeof(masks) / sizeof(masks[0]); ++m) {
        for (int i = 0; i < info->hdr->e_shnum; ++i) {
            Elf_Shdr *s = &info->sechdrs[i];
            if ((s->sh_flags & masks[m][0]) != masks[m][0] || (s->sh_flags & masks[m][1]) || s->sh_entsize != ~0UL)
                continue;
            s->sh_entsize = get_offset(mod, &mod->size, s, i);
            // const char *sname = info->secstrings + s->sh_name;
        }
        switch (m) {
        case 0: /* executable */
            mod->size = align(mod->size);
            mod->text_size = mod->size;
            break;
        case 1: /* RO: text and ro-data */
            mod->size = align(mod->size);
            mod->ro_size = mod->size;
            break;
        case 2:
            break;
        case 3: /* whole */
            mod->size = align(mod->size);
            break;
        }
    }
}

static bool is_core_symbol(const Elf_Sym *src, const Elf_Shdr *sechdrs, unsigned int shnum)
{
    const Elf_Shdr *sec;
    if (src->st_shndx == SHN_UNDEF || src->st_shndx >= shnum || !src->st_name) return false;
    sec = sechdrs + src->st_shndx;
    if (!(sec->sh_flags & SHF_ALLOC) || !(sec->sh_flags & SHF_EXECINSTR)) return false;
    return true;
}

/* Change all symbols so that st_value encodes the pointer directly. */
static int simplify_symbols(struct module *mod, const struct load_info *info)
{
    Elf_Shdr *symsec = &info->sechdrs[info->index.sym];
    Elf_Sym *sym = (void *)symsec->sh_addr;
    unsigned long secbase;
    unsigned int i;
    int ret = 0;
    unsigned long addr = 0;

    for (i = 1; i < symsec->sh_size / sizeof(Elf_Sym); i++) {
        const char *name = info->strtab + sym[i].st_name;
        switch (sym[i].st_shndx) {
        case SHN_COMMON:
            if (!strncmp(name, "__gnu_lto", 9)) {
                logkd("Please compile with -fno-common\n");
                ret = -ENOEXEC;
            }
            break;
        case SHN_ABS:
            break;
        case SHN_UNDEF:
            addr = symbol_lookup_name(name);
            // kernel symbol cause overflow in relocation
            // if (!addr) addr = kallsyms_lookup_name(name);
            if (!addr) {
                logke("unknown symbol: %s\n", name);
                ret = -ENOENT;
                break;
            }
            sym[i].st_value = addr;
            break;
        default:
            secbase = info->sechdrs[sym[i].st_shndx].sh_addr;
            sym[i].st_value += secbase;
            break;
        }
    }
    return ret;
}

static int apply_relocations(struct module *mod, const struct load_info *info)
{
    int rc = 0;
    unsigned int i;
    for (i = 1; i < info->hdr->e_shnum; i++) {
        unsigned int infosec = info->sechdrs[i].sh_info;
        if (infosec >= info->hdr->e_shnum) continue;
        if (!(info->sechdrs[infosec].sh_flags & SHF_ALLOC)) continue;
        if (info->sechdrs[i].sh_type == SHT_REL) {
            rc = apply_relocate(info->sechdrs, info->strtab, info->index.sym, i, mod);
        } else if (info->sechdrs[i].sh_type == SHT_RELA) {
            rc = apply_relocate_add(info->sechdrs, info->strtab, info->index.sym, i, mod);
        }
        if (rc < 0) break;
    }
    return rc;
}

// todo: free .strtab and .symtab after relocation
static void layout_symtab(struct module *mod, struct load_info *info)
{
    Elf_Shdr *symsect = info->sechdrs + info->index.sym;
    Elf_Shdr *strsect = info->sechdrs + info->index.str;
    const Elf_Sym *src;
    unsigned int i, nsrc, ndst, strtab_size = 0;

    /* Put symbol section at end of module. */
    symsect->sh_flags |= SHF_ALLOC;
    symsect->sh_entsize = get_offset(mod, &mod->size, symsect, info->index.sym);

    src = (void *)info->hdr + symsect->sh_offset;
    nsrc = symsect->sh_size / sizeof(*src);

    /* strtab always starts with a nul, so offset 0 is the empty string. */
    strtab_size = 1;
    /* Compute total space required for the core symbols' strtab. */
    for (ndst = i = 0; i < nsrc; i++) {
        if (i == 0 || is_core_symbol(src + i, info->sechdrs, info->hdr->e_shnum)) {
            strtab_size += strlen(&info->strtab[src[i].st_name]) + 1;
            ndst++;
        }
    }

    /* Append room for core symbols at end. */
    info->symoffs = ALIGN(mod->size, symsect->sh_addralign ?: 1);
    info->stroffs = mod->size = info->symoffs + ndst * sizeof(Elf_Sym);
    mod->size += strtab_size;

    /* Put string table section at end of module. */
    strsect->sh_flags |= SHF_ALLOC;
    strsect->sh_entsize = get_offset(mod, &mod->size, strsect, info->index.str);
}

static int rewrite_section_headers(struct load_info *info)
{
    info->sechdrs[0].sh_addr = 0;
    for (int i = 1; i < info->hdr->e_shnum; i++) {
        Elf_Shdr *shdr = &info->sechdrs[i];
        if (shdr->sh_type != SHT_NOBITS && info->len < shdr->sh_offset + shdr->sh_size) {
            return -ENOEXEC;
        }
        /* Mark all sections sh_addr with their address in the temporary image. */
        shdr->sh_addr = (size_t)info->hdr + shdr->sh_offset;
    }
    return 0;
}

static int move_module(struct module *mod, struct load_info *info)
{
    // todo:
    logki("alloc module size: %llx\n", mod->size);
    mod->start = kp_malloc_exec(mod->size);
    if (!mod->start) {
        return -ENOMEM;
    }
    memset(mod->start, 0, mod->size);

    /* Transfer each section which specifies SHF_ALLOC */
    logkd("final section addresses:\n");

    for (int i = 1; i < info->hdr->e_shnum; i++) {
        void *dest;
        Elf_Shdr *shdr = &info->sechdrs[i];
        if (!(shdr->sh_flags & SHF_ALLOC)) continue;

        dest = mod->start + shdr->sh_entsize;
        const char *sname = info->secstrings + shdr->sh_name;

        logkd("    %s %llx %llx\n", sname, dest, shdr->sh_size);

        if (shdr->sh_type != SHT_NOBITS) memcpy(dest, (void *)shdr->sh_addr, shdr->sh_size);

        shdr->sh_addr = (unsigned long)dest;

        if (!mod->init && !strcmp(".kpm.init", sname)) mod->init = (mod_initcall_t *)dest;

        if (!strcmp(".kpm.ctl0", sname)) mod->ctl0 = (mod_ctl0call_t *)dest;
        if (!strcmp(".kpm.ctl1", sname)) mod->ctl1 = (mod_ctl1call_t *)dest;

        if (!mod->exit && !strcmp(".kpm.exit", sname)) mod->exit = (mod_exitcall_t *)dest;

        if (!mod->info.base && !strcmp(".kpm.info", sname)) mod->info.base = (const char *)dest;
    }
    mod->info.name = info->info.name - info->info.base + mod->info.base;
    mod->info.version = info->info.version - info->info.base + mod->info.base;

    if (info->info.license) mod->info.license = info->info.license - info->info.base + mod->info.base;
    if (info->info.author) mod->info.author = info->info.author - info->info.base + mod->info.base;
    if (info->info.description) mod->info.description = info->info.description - info->info.base + mod->info.base;

    return 0;
}

static int setup_load_info(struct load_info *info)
{
    int rc = 0;
    info->sechdrs = (void *)info->hdr + info->hdr->e_shoff;
    info->secstrings = (void *)info->hdr + info->sechdrs[info->hdr->e_shstrndx].sh_offset;

    if ((rc = rewrite_section_headers(info))) {
        logke("rewrite section error\n");
        return rc;
    }

    if (!find_sec(info, ".kpm.init") || !find_sec(info, ".kpm.exit")) {
        logke("no .kpm.init or .kpm.exit section\n");
        return -ENOEXEC;
    }

    info->index.info = find_sec(info, ".kpm.info");
    if (!info->index.info) {
        logke("no .kpm.info section\n");
        return -ENOEXEC;
    }
    if (info->sechdrs[info->index.info].sh_type != SHT_PROGBITS ||
        !info->sechdrs[info->index.info].sh_size ||
        ((const char *)info->hdr + info->sechdrs[info->index.info].sh_offset)
                [info->sechdrs[info->index.info].sh_size - 1] != '\0') {
        logke("invalid .kpm.info section\n");
        return -ENOEXEC;
    }
    info->info.base = get_sh_base(info, ".kpm.info");
    info->info.size = get_sh_size(info, ".kpm.info");

    const char *name = get_modinfo(info, "name");
    if (!name) {
        logke("module name not found\n");
        return -ENOEXEC;
    }
    info->info.name = name;
    logkd("loading module: \n");
    logkd("    name: %s\n", name);

    const char *version = get_modinfo(info, "version");
    if (!version) {
        logkd("module version not found\n");
        return -ENOEXEC;
    }
    info->info.version = version;
    logkd("    version: %s\n", version);

    const char *license = get_modinfo(info, "license");
    info->info.license = license;
    logkd("    license: %s\n", license ?: "");

    const char *author = get_modinfo(info, "author");
    info->info.author = author;
    logkd("    author: %s\n", author ?: "");
    const char *description = get_modinfo(info, "description");
    info->info.description = description;
    logkd("    description: %s\n", description ?: "");

    for (int i = 1; i < info->hdr->e_shnum; i++) {
        if (info->sechdrs[i].sh_type == SHT_SYMTAB) {
            info->index.sym = i;
            info->index.str = info->sechdrs[i].sh_link;
            if (info->index.str >= info->hdr->e_shnum ||
                info->sechdrs[info->index.str].sh_type != SHT_STRTAB ||
                !info->sechdrs[info->index.str].sh_size ||
                ((const char *)info->hdr + info->sechdrs[info->index.str].sh_offset)
                        [info->sechdrs[info->index.str].sh_size - 1] != '\0' ||
                info->sechdrs[i].sh_size % sizeof(Elf_Sym)) {
                logke("invalid symbol table links\n");
                return -ENOEXEC;
            }
            info->strtab = (char *)info->hdr + info->sechdrs[info->index.str].sh_offset;
            Elf_Sym *symbols = (Elf_Sym *)info->sechdrs[i].sh_addr;
            unsigned int symbol_count = info->sechdrs[i].sh_size / sizeof(Elf_Sym);
            for (unsigned int symbol = 0; symbol < symbol_count; ++symbol) {
                if (symbols[symbol].st_name >= info->sechdrs[info->index.str].sh_size ||
                    (symbols[symbol].st_shndx >= info->hdr->e_shnum &&
                     symbols[symbol].st_shndx != SHN_ABS &&
                     symbols[symbol].st_shndx != SHN_COMMON)) {
                    logke("invalid symbol table entry\n");
                    return -ENOEXEC;
                }
            }
            break;
        }
    }

    if (info->index.sym == 0) {
        logkd("module has no symbols (stripped?)\n");
        return -ENOEXEC;
    }
    return 0;
}

static int elf_header_check(struct load_info *info)
{
    if (!info || !info->hdr || info->len <= sizeof(*(info->hdr)) || info->len > KPM_MAX_IMAGE_SIZE)
        return -ENOEXEC;
    if (memcmp(info->hdr->e_ident, ELFMAG, SELFMAG) ||
        info->hdr->e_ident[EI_CLASS] != ELFCLASS64 ||
        info->hdr->e_ident[EI_DATA] != ELFDATA2LSB ||
        info->hdr->e_ident[EI_VERSION] != EV_CURRENT ||
        info->hdr->e_type != ET_REL || !elf_check_arch(info->hdr) ||
        info->hdr->e_ehsize != sizeof(Elf_Ehdr) ||
        info->hdr->e_shentsize != sizeof(Elf_Shdr))
        return -ENOEXEC;
    if (!info->hdr->e_shnum || info->hdr->e_shnum > KPM_MAX_SECTIONS ||
        info->hdr->e_shstrndx >= info->hdr->e_shnum ||
        info->hdr->e_shoff >= info->len ||
        info->hdr->e_shnum > (info->len - info->hdr->e_shoff) / sizeof(Elf_Shdr))
        return -ENOEXEC;

    unsigned long long memory_size = 0;
    for (unsigned int i = 0; i < info->hdr->e_shnum; ++i) {
        const Elf_Shdr *shdr = &((Elf_Shdr *)((char *)info->hdr + info->hdr->e_shoff))[i];
        if (shdr->sh_type != SHT_NOBITS &&
            (shdr->sh_offset > info->len || shdr->sh_size > info->len - shdr->sh_offset))
            return -ENOEXEC;
        if (shdr->sh_size > KPM_MAX_MEMORY_SIZE ||
            (shdr->sh_addralign &&
             (shdr->sh_addralign > KPM_MAX_MEMORY_SIZE ||
              (shdr->sh_addralign & (shdr->sh_addralign - 1)))))
            return -ENOEXEC;
        unsigned long long alignment = shdr->sh_addralign ?: 1;
        memory_size = (memory_size + alignment - 1) & ~(alignment - 1);
        if (memory_size > KPM_MAX_MEMORY_SIZE - shdr->sh_size)
            return -ENOEXEC;
        memory_size += shdr->sh_size;
    }

    const Elf_Shdr *shstr = &((Elf_Shdr *)((char *)info->hdr + info->hdr->e_shoff))[info->hdr->e_shstrndx];
    if (shstr->sh_type == SHT_NOBITS || shstr->sh_size == 0 ||
        shstr->sh_offset > info->len || shstr->sh_size > info->len - shstr->sh_offset ||
        ((const char *)info->hdr + shstr->sh_offset)[shstr->sh_size - 1] != '\0')
        return -ENOEXEC;

    for (unsigned int i = 0; i < info->hdr->e_shnum; ++i) {
        const Elf_Shdr *shdr = &((Elf_Shdr *)((char *)info->hdr + info->hdr->e_shoff))[i];
        if (shdr->sh_name >= shstr->sh_size)
            return -ENOEXEC;
    }
    return 0;
}

struct module modules = { 0 };
static spinlock_t module_lock;

static struct module *find_module_locked(const char *name)
{
    struct module *pos;

    list_for_each_entry(pos, &modules.list, list) {
        if (!strcmp(name, pos->info.name)) return pos;
    }
    return 0;
}

long load_module(const void *data, int len, const char *args, const char *event, void *__user reserved)
{
    struct load_info load_info = { .len = len, .hdr = data };
    struct load_info *info = &load_info;
    long rc = 0;

    if ((rc = elf_header_check(info))) goto out;
    if ((rc = setup_load_info(info))) goto out;

    spin_lock(&module_lock);
    if (find_module_locked(info->info.name)) {
        logkfd("%s exist\n", info->info.name);
        rc = -EEXIST;
        spin_unlock(&module_lock);
        goto out;
    }
    spin_unlock(&module_lock);

    struct module *mod = (struct module *)vmalloc(sizeof(struct module));
    if (!mod) {
        rc = -ENOMEM;
        goto out;
    }
    memset(mod, 0, sizeof(struct module));

    if (args) {
        mod->args = vmalloc(strlen(args) + 1);
        if (!mod->args) {
            rc = -ENOMEM;
            goto free1;
        }
        strcpy(mod->args, args);
    }

    layout_sections(mod, info);
    layout_symtab(mod, info);

    if ((rc = move_module(mod, info))) goto free;
    if (!mod->init || !*mod->init || !mod->exit || !*mod->exit) {
        rc = -ENOEXEC;
        goto free;
    }
    if ((rc = simplify_symbols(mod, info))) goto free;
    if ((rc = apply_relocations(mod, info))) goto free;

    flush_icache_all();

    rc = (*mod->init)(mod->args, event, reserved);

    if (!rc) {
        logkfi("[%s] succeed with [%s] \n", mod->info.name, args);
        spin_lock(&module_lock);
        if (find_module_locked(mod->info.name)) {
            spin_unlock(&module_lock);
            rc = -EEXIST;
            (*mod->exit)(reserved);
            goto free;
        }
        list_add_tail(&mod->list, &modules.list);
        spin_unlock(&module_lock);
        goto out;
    } else {
        logkfi("[%s] failed with [%s] error: %d, try exit ...\n", mod->info.name, args, rc);
        (*mod->exit)(reserved);
    }

free:
    if (mod->args) kvfree(mod->args);
    kp_free_exec(mod->start);
free1:
    kvfree(mod);
out:
    return rc;
}

long unload_module(const char *name, void *__user reserved)
{
    if (!name) return -EINVAL;
    logkfe("name: %s\n", name);

    spin_lock(&module_lock);
    struct module *mod = find_module_locked(name);
    if (!mod) {
        spin_unlock(&module_lock);
        return -ENOENT;
    }

    list_del_rcu(&mod->list);
    spin_unlock(&module_lock);
    synchronize_rcu();

    long rc = 0;
    rc = (*mod->exit)(reserved);

    if (mod->args) kvfree(mod->args);

    kp_free_exec(mod->start);
    kvfree(mod);

    logkfi("name: %s, rc: %d\n", name, rc);
    return rc;
}

long load_module_path(const char *path, const char *args, void *__user reserved)
{
    long rc = 0;
    logkfd("%s\n", path);
    if (!path) return -EINVAL;

    struct file *filp = filp_open(path, O_RDONLY, 0);
    if (unlikely(!filp || IS_ERR(filp))) {
        logkfe("open module: %s error\n", path);
        rc = PTR_ERR(filp);
        goto out;
    }
    loff_t len = vfs_llseek(filp, 0, SEEK_END);
    logkfd("module size: %llx\n", len);
    if (len <= 0 || len > KPM_MAX_IMAGE_SIZE) {
        rc = len <= 0 ? -ENOEXEC : -EFBIG;
        filp_close(filp, 0);
        goto out;
    }
    if (vfs_llseek(filp, 0, SEEK_SET) < 0) {
        rc = -EIO;
        filp_close(filp, 0);
        goto out;
    }

    void *data = vmalloc(len);
    if (!data) {
        rc = -ENOMEM;
        filp_close(filp, 0);
        goto out;
    }
    memset(data, 0, len);

    loff_t pos = 0;
    ssize_t read = kernel_read(filp, data, len, &pos);
    filp_close(filp, 0);

    if (read != len || pos != len) {
        logkfe("read module: %s error\n", path);
        rc = -EIO;
        goto free;
    }

    rc = load_module(data, len, args, "load-file", reserved);
free:
    kvfree(data);
out:
    return rc;
}

long module_control0(const char *name, const char *ctl_args, char *__user out_msg, int outlen)
{
    if (!name) return -EINVAL;
    if (!ctl_args) ctl_args = "";
    int args_len = strnlen(ctl_args, KPM_MAX_ARGS_LEN + 1);
    if (args_len > KPM_MAX_ARGS_LEN) return -EINVAL;

    logkfi("name %s, args: %s\n", name, ctl_args);

    long rc = 0;
    rcu_read_lock();

    struct module *mod = find_module(name);
    if (!mod) {
        rc = -ENOENT;
        goto out;
    }

    if (!mod->ctl0 || !*mod->ctl0) {
        logkfe("no ctl0\n");
        rc = -ENOSYS;
        goto out;
    }

    char *args_copy = vmalloc(args_len + 1);
    if (!args_copy) {
        rc = -ENOMEM;
        goto out;
    }

    strcpy(args_copy, ctl_args);

    rc = (*mod->ctl0)(args_copy, out_msg, outlen);
    kvfree(args_copy);

    logkfi("name: %s, rc: %d\n", name, rc);
out:
    rcu_read_unlock();
    return rc;
}

long module_control1(const char *name, void *a1, void *a2, void *a3)
{
    logkfi("name %s, a1: %llx, a2: %llx, a3: %llx\n", name, a1, a2, a3);
    long rc = 0;
    rcu_read_lock();

    struct module *mod = find_module(name);
    if (!mod) {
        rc = -ENOENT;
        goto out;
    }

    if (!mod->ctl1 || !*mod->ctl1) {
        logkfe("no ctl1\n");
        rc = -ENOSYS;
        goto out;
    }

    rc = (*mod->ctl1)(a1, a2, a3);

    logkfi("name: %s, rc: %d\n", name, rc);
out:
    rcu_read_unlock();
    return rc;
}

struct module *find_module(const char *name)
{
    struct module *pos;

    list_for_each_entry_rcu(pos, &modules.list, list) {
        if (!strcmp(name, pos->info.name)) {
            return pos;
        }
    }
    return 0;
}

int get_module_nums()
{
    rcu_read_lock();

    struct module *pos;
    int n = 0;
    list_for_each_entry_rcu(pos, &modules.list, list)
    {
        n++;
    }
    rcu_read_unlock();

    logkfd("%d\n", n);
    return n;
}

int list_modules(char *out_names, int size)
{
    if (!out_names || size <= 0) return -EINVAL;

    rcu_read_lock();

    struct module *pos;
    int off = 0;
    list_for_each_entry_rcu(pos, &modules.list, list)
    {
        if (off >= size - 1) {
            out_names[size - 1] = '\0';
            rcu_read_unlock();
            return -ENOSPC;
        }
        int written = snprintf(out_names + off, size - off, "%s\n", pos->info.name);
        if (written < 0) {
            rcu_read_unlock();
            return written;
        }
        if (written >= size - off) {
            out_names[size - 1] = '\0';
            rcu_read_unlock();
            return -ENOSPC;
        }
        off += written;
    }
    if (off > 0) out_names[off - 1] = '\0';

    rcu_read_unlock();
    return off;
}

int get_module_info(const char *name, char *out_info, int size)
{
    if (!name || !out_info || size <= 0) return -EINVAL;
    rcu_read_lock();

    struct module *mod = find_module(name);
    if (!mod) {
        rcu_read_unlock();
        return -ENOENT;
    }

    int sz = snprintf(out_info, size,
                      "name=%s\n"
                      "version=%s\n"
                      "license=%s\n"
                      "author=%s\n"
                      "description=%s\n"
                      "args=%s\n",
                      mod->info.name, mod->info.version,
                      mod->info.license ?: "", mod->info.author ?: "",
                      mod->info.description ?: "", mod->args ?: "");

    if (sz >= size) {
        out_info[size - 1] = '\0';
        sz = -ENOSPC;
    }
    // logkfd("%s", out_info);

    rcu_read_unlock();
    return sz;
}

void module_init()
{
    INIT_LIST_HEAD(&modules.list);
    spin_lock_init(&module_lock);
}
