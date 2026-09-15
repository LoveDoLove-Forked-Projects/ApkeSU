// SPDX-License-Identifier: GPL-2.0
#include <linux/abk_control.h>
#include <linux/atomic.h>
#include <linux/completion.h>
#include <linux/errno.h>
#include <linux/kernel.h>
#include <linux/list.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/string.h>

#define ABK_CONTROL_INITIAL_BUFFER 1024
#define ABK_CONTROL_MAX_ID 64

struct abk_control_registration {
    const struct abk_control_ops *ops;
    struct list_head node;
    atomic_t active;
    struct completion idle;
    bool unregistering;
};

struct abk_control_buffer {
    char *data;
    size_t len;
    size_t cap;
};

struct abk_control_snapshot {
    struct abk_control_registration *registration;
    const struct abk_control_ops *ops;
    bool enabled;
};

struct abk_control_module_view {
    const char *id;
    const char *name;
    const char *version;
    const char *description;
    const char *repo_url;
    const char *stage;
    const char *entry_kind;
    const char *extension_id;
    const char *companion_package;
    const char *companion_display_name;
    const char *companion_asset_name;
    const char *companion_download_url;
    const char *service_activity;
    const char *group_id;
    const char *group_name;
    const char *group_role;
    const char *group_description;
    const char *group_repo_url;
    const char *module_dir;
    const char *web_root;
    const char *source;
    bool readonly;
    bool controllable;
    bool enabled;
    bool requires_companion_app;
    bool settings_supported;
    bool per_app_supported;
    u32 oobe_priority;
    bool has_web_ui;
    bool has_action_script;
    bool action_supported;
};

static LIST_HEAD(abk_control_registry);
static DEFINE_MUTEX(abk_control_lock);
static bool abk_control_shutting_down;

static const char *abk_control_effective_work_mode(void)
{
    const char *work_mode = abk_control_build.work_mode;

    if (work_mode && !strcmp(work_mode, "lkm"))
        return "lkm";
    return "built-in";
}

static bool abk_control_valid_id(const char *id)
{
    size_t length;
    size_t index;

    if (!id)
        return false;
    length = strnlen(id, ABK_CONTROL_MAX_ID + 1);
    if (!length || length > ABK_CONTROL_MAX_ID)
        return false;
    for (index = 0; index < length; index++) {
        unsigned char value = id[index];

        if (!(value == '_' || value == '-' || value == '.' ||
              (value >= '0' && value <= '9') ||
              (value >= 'A' && value <= 'Z') ||
              (value >= 'a' && value <= 'z')))
            return false;
    }
    return true;
}

static struct abk_control_registration *abk_control_find_locked(const char *id)
{
    struct abk_control_registration *registration;

    list_for_each_entry(registration, &abk_control_registry, node) {
        if (!strcmp(registration->ops->id, id))
            return registration;
    }
    return NULL;
}

static struct abk_control_registration *abk_control_get_ref(const char *id)
{
    struct abk_control_registration *registration;

    mutex_lock(&abk_control_lock);
    registration = abk_control_find_locked(id);
    if (registration && !registration->unregistering)
        atomic_inc(&registration->active);
    else
        registration = NULL;
    mutex_unlock(&abk_control_lock);
    return registration;
}

static void abk_control_put_ref(struct abk_control_registration *registration)
{
    if (atomic_dec_and_test(&registration->active) &&
        READ_ONCE(registration->unregistering))
        complete(&registration->idle);
}

static void abk_control_release_snapshot(struct abk_control_snapshot *snapshot,
                                         size_t count)
{
    size_t index;

    for (index = 0; index < count; index++)
        abk_control_put_ref(snapshot[index].registration);
}

static int abk_control_snapshot_registry(struct abk_control_snapshot *snapshot,
                                         size_t capacity, size_t *count)
{
    struct abk_control_registration *registration;
    size_t copied = 0;
    size_t index;

    mutex_lock(&abk_control_lock);
    if (abk_control_shutting_down) {
        mutex_unlock(&abk_control_lock);
        return -ESHUTDOWN;
    }
    list_for_each_entry(registration, &abk_control_registry, node) {
        if (copied == capacity) {
            mutex_unlock(&abk_control_lock);
            abk_control_release_snapshot(snapshot, copied);
            return -E2BIG;
        }
        atomic_inc(&registration->active);
        snapshot[copied].registration = registration;
        snapshot[copied].ops = registration->ops;
        copied++;
    }
    mutex_unlock(&abk_control_lock);

    /* Provider callbacks run without the registry mutex held. */
    for (index = 0; index < copied; index++)
        snapshot[index].enabled = snapshot[index].ops->is_enabled ?
            snapshot[index].ops->is_enabled(snapshot[index].ops->data) : true;
    *count = copied;
    return 0;
}

static int abk_control_buf_reserve(struct abk_control_buffer *buffer,
                                   size_t required)
{
    char *next;
    size_t capacity = buffer->cap;

    if (required > ABK_CONTROL_MAX_STATUS)
        return -E2BIG;
    if (required <= capacity)
        return 0;
    if (!capacity)
        capacity = ABK_CONTROL_INITIAL_BUFFER;
    while (capacity < required) {
        size_t doubled = capacity * 2;

        if (doubled <= capacity) {
            capacity = required;
            break;
        }
        capacity = doubled;
    }
    if (capacity > ABK_CONTROL_MAX_STATUS)
        capacity = ABK_CONTROL_MAX_STATUS;
    next = krealloc(buffer->data, capacity, GFP_KERNEL);
    if (!next)
        return -ENOMEM;
    buffer->data = next;
    buffer->cap = capacity;
    return 0;
}

static int abk_control_buf_append_mem(struct abk_control_buffer *buffer,
                                      const char *data, size_t length)
{
    int ret;

    if (buffer->len >= ABK_CONTROL_MAX_STATUS ||
        length > ABK_CONTROL_MAX_STATUS - buffer->len - 1)
        return -E2BIG;
    ret = abk_control_buf_reserve(buffer, buffer->len + length + 1);
    if (ret)
        return ret;
    memcpy(buffer->data + buffer->len, data, length);
    buffer->len += length;
    buffer->data[buffer->len] = '\0';
    return 0;
}

static int abk_control_buf_append(struct abk_control_buffer *buffer,
                                  const char *text)
{
    return abk_control_buf_append_mem(buffer, text, strlen(text));
}

static int abk_control_buf_appendf(struct abk_control_buffer *buffer,
                                   const char *format, ...)
{
    va_list args;
    int needed;
    size_t available;
    int ret;

    ret = abk_control_buf_reserve(buffer, buffer->len + 1);
    if (ret)
        return ret;
    available = buffer->cap - buffer->len;
    va_start(args, format);
    needed = vsnprintf(buffer->data + buffer->len, available, format, args);
    va_end(args);
    if (needed < 0)
        return needed;
    if ((size_t)needed >= available) {
        ret = abk_control_buf_reserve(buffer, buffer->len + needed + 1);
        if (ret)
            return ret;
        va_start(args, format);
        needed = vsnprintf(buffer->data + buffer->len,
                           buffer->cap - buffer->len, format, args);
        va_end(args);
        if (needed < 0 || (size_t)needed >= buffer->cap - buffer->len)
            return -E2BIG;
    }
    buffer->len += needed;
    return 0;
}

static int abk_control_buf_append_json_string(struct abk_control_buffer *buffer,
                                              const char *value)
{
    const unsigned char *cursor = (const unsigned char *)(value ? value : "");
    int ret;

    ret = abk_control_buf_append(buffer, "\"");
    if (ret)
        return ret;
    for (; *cursor; cursor++) {
        switch (*cursor) {
        case '\\':
            ret = abk_control_buf_append(buffer, "\\\\");
            break;
        case '"':
            ret = abk_control_buf_append(buffer, "\\\"");
            break;
        case '\b':
            ret = abk_control_buf_append(buffer, "\\b");
            break;
        case '\f':
            ret = abk_control_buf_append(buffer, "\\f");
            break;
        case '\n':
            ret = abk_control_buf_append(buffer, "\\n");
            break;
        case '\r':
            ret = abk_control_buf_append(buffer, "\\r");
            break;
        case '\t':
            ret = abk_control_buf_append(buffer, "\\t");
            break;
        default:
            if (*cursor < 0x20)
                ret = abk_control_buf_appendf(buffer, "\\u%04x", *cursor);
            else
                ret = abk_control_buf_append_mem(buffer,
                                                 (const char *)cursor, 1);
            break;
        }
        if (ret)
            return ret;
    }
    return abk_control_buf_append(buffer, "\"");
}

static int abk_control_json_string(struct abk_control_buffer *buffer,
                                   const char *name, const char *value,
                                   bool comma)
{
    int ret = abk_control_buf_appendf(buffer, "\"%s\": ", name);

    if (ret)
        return ret;
    ret = abk_control_buf_append_json_string(buffer, value);
    if (ret)
        return ret;
    return abk_control_buf_append(buffer, comma ? ", " : "");
}

static int abk_control_json_bool(struct abk_control_buffer *buffer,
                                 const char *name, bool value, bool comma)
{
    return abk_control_buf_appendf(buffer, "\"%s\": %s%s", name,
                                   value ? "true" : "false",
                                   comma ? ", " : "");
}

static int abk_control_json_u32(struct abk_control_buffer *buffer,
                                const char *name, u32 value, bool comma)
{
    return abk_control_buf_appendf(buffer, "\"%s\": %u%s", name, value,
                                   comma ? ", " : "");
}

static int abk_control_append_manager(struct abk_control_buffer *buffer)
{
    const char *work_mode = abk_control_effective_work_mode();
    int ret;

    ret = abk_control_buf_append(buffer, "  \"manager\": {");
    if (ret)
        return ret;
    ret = abk_control_json_string(buffer, "display_name", "ABK Control", true);
    if (ret)
        return ret;
    ret = abk_control_json_string(buffer, "variant",
                                  abk_control_build.kernelsu_variant, true);
    if (ret)
        return ret;
    ret = abk_control_json_string(buffer, "backend", "kernel", true);
    if (ret)
        return ret;
    ret = abk_control_json_string(buffer, "version",
                                  abk_control_build.version, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "active", true, true);
    if (ret)
        return ret;
    ret = abk_control_buf_appendf(
        buffer,
        "\"capabilities\": [\"build\", \"modules\", \"abk_control\"%s]},\n",
        !strcmp(work_mode, "lkm") ? ", \"lkm\"" : "");
    return ret;
}

static int abk_control_append_build(struct abk_control_buffer *buffer)
{
    const struct abk_control_build_info *build = &abk_control_build;
    const struct abk_control_build_features *features = &build->features;
    int ret;

    ret = abk_control_json_string(buffer, "abk_version",
                                  build->abk_version, true);
    if (ret)
        return ret;
    ret = abk_control_json_string(buffer, "abk_commit", build->abk_commit, true);
    if (ret)
        return ret;
    ret = abk_control_json_string(buffer, "work_mode",
                                  abk_control_effective_work_mode(), true);
    if (ret)
        return ret;
    ret = abk_control_buf_append(buffer, "  \"build\": {");
    if (ret)
        return ret;
#define ABK_BUILD_STRING(name, value) \
    do { \
        ret = abk_control_json_string(buffer, name, value, true); \
        if (ret) \
            return ret; \
    } while (0)
    ABK_BUILD_STRING("abk_version", build->abk_version);
    ABK_BUILD_STRING("abk_commit", build->abk_commit);
    ABK_BUILD_STRING("work_mode", abk_control_effective_work_mode());
    ABK_BUILD_STRING("android_version", build->android_version);
    ABK_BUILD_STRING("kernel_version", build->kernel_version);
    ABK_BUILD_STRING("sub_level", build->sub_level);
    ABK_BUILD_STRING("os_patch_level", build->os_patch_level);
    ABK_BUILD_STRING("revision", build->revision);
    ABK_BUILD_STRING("kernelsu_variant", build->kernelsu_variant);
    ABK_BUILD_STRING("kernelsu_branch", build->kernelsu_branch);
    ABK_BUILD_STRING("version", build->version);
    ABK_BUILD_STRING("build_time", build->build_time);
    ABK_BUILD_STRING("virtualization_support", build->virtualization_support);
    ABK_BUILD_STRING("zram_extra_algos", build->zram_extra_algos);
#undef ABK_BUILD_STRING
    ret = abk_control_buf_append(buffer, "\"features\": {");
    if (ret)
        return ret;
#define ABK_BUILD_BOOL(name, value) \
    do { \
        ret = abk_control_json_bool(buffer, name, value, true); \
        if (ret) \
            return ret; \
    } while (0)
    ABK_BUILD_BOOL("use_zram", features->use_zram);
    ABK_BUILD_BOOL("use_bbg", features->use_bbg);
    ABK_BUILD_BOOL("use_ddk", features->use_ddk);
    ABK_BUILD_BOOL("use_ntsync", features->use_ntsync);
    ABK_BUILD_BOOL("use_networking", features->use_networking);
    ABK_BUILD_BOOL("use_kpm", features->use_kpm);
    ABK_BUILD_BOOL("use_rekernel", features->use_rekernel);
    ABK_BUILD_BOOL("enable_susfs", features->enable_susfs);
    ABK_BUILD_BOOL("supp_op", features->supp_op);
    ret = abk_control_json_bool(buffer, "zram_full_algo",
                                 features->zram_full_algo, false);
#undef ABK_BUILD_BOOL
    if (ret)
        return ret;
    return abk_control_buf_append(buffer, "}},\n");
}

static void abk_control_view_from_manifest(
    struct abk_control_module_view *view,
    const struct abk_control_manifest_entry *entry,
    const struct abk_control_ops *ops,
    bool enabled)
{
    memset(view, 0, sizeof(*view));
    view->id = entry ? entry->id : ops->id;
    view->name = entry ? entry->name : ops->name;
    view->version = entry ? entry->version : ops->version;
    view->description = entry ? entry->description : ops->description;
    view->repo_url = entry ? entry->repo_url : "";
    view->stage = entry ? entry->stage : "runtime";
    view->entry_kind = entry ? entry->entry_kind : "module";
    view->extension_id = entry ? entry->extension_id : ops->extension_id;
    view->companion_package = entry ? entry->companion_package :
                                      ops->companion_package;
    view->companion_display_name = entry ? entry->companion_display_name :
                                            ops->companion_display_name;
    view->companion_asset_name = entry ? entry->companion_asset_name :
                                         ops->companion_asset_name;
    view->companion_download_url = entry ? entry->companion_download_url :
                                             ops->companion_download_url;
    view->service_activity = entry ? entry->service_activity :
                                      ops->service_activity;
    view->group_id = entry ? entry->group_id : "";
    view->group_name = entry ? entry->group_name : "";
    view->group_role = entry ? entry->group_role : "";
    view->group_description = entry ? entry->group_description : "";
    view->group_repo_url = entry ? entry->group_repo_url : "";
    view->module_dir = ops ? ops->module_dir : "";
    view->web_root = ops ? ops->web_root : "";
    view->source = "abk";
    view->readonly = !ops || !ops->set_enabled;
    view->controllable = ops && ops->set_enabled;
    view->enabled = ops ? enabled : true;
    view->requires_companion_app = ops ? ops->requires_companion_app :
                                            entry->requires_companion_app;
    view->settings_supported = ops ? ops->settings_supported :
                                          entry->settings_supported;
    view->per_app_supported = ops ? ops->per_app_supported :
                                      entry->per_app_supported;
    view->oobe_priority = ops ? ops->oobe_priority : entry->oobe_priority;
    view->has_web_ui = ops && ops->has_web_ui;
    view->has_action_script = ops && ops->has_action_script;
    view->action_supported = ops && ops->action_supported;
}

static bool abk_control_is_extension(const struct abk_control_module_view *view)
{
    return view->extension_id && view->extension_id[0] &&
           (!view->entry_kind || strcmp(view->entry_kind, "module_set_child"));
}

static int abk_control_append_module(struct abk_control_buffer *buffer,
                                     bool *first,
                                     const struct abk_control_module_view *view)
{
    int ret;

    ret = abk_control_buf_append(buffer, *first ? "\n    {" : ",\n    {");
    if (ret)
        return ret;
    *first = false;
#define ABK_MODULE_STRING(name, value) \
    do { \
        ret = abk_control_json_string(buffer, name, value, true); \
        if (ret) \
            return ret; \
    } while (0)
    ABK_MODULE_STRING("id", view->id);
    ABK_MODULE_STRING("name", view->name);
    ABK_MODULE_STRING("version", view->version);
    ABK_MODULE_STRING("description", view->description);
    ABK_MODULE_STRING("repo_url", view->repo_url);
    ABK_MODULE_STRING("stage", view->stage);
    ABK_MODULE_STRING("entry_kind", view->entry_kind);
    ABK_MODULE_STRING("extension_id", view->extension_id);
    ABK_MODULE_STRING("companion_package", view->companion_package);
    ABK_MODULE_STRING("companion_display_name", view->companion_display_name);
    ABK_MODULE_STRING("companion_asset_name", view->companion_asset_name);
    ABK_MODULE_STRING("companion_download_url", view->companion_download_url);
    ABK_MODULE_STRING("service_activity", view->service_activity);
    ABK_MODULE_STRING("group_id", view->group_id);
    ABK_MODULE_STRING("group_name", view->group_name);
    ABK_MODULE_STRING("group_role", view->group_role);
    ABK_MODULE_STRING("group_description", view->group_description);
    ABK_MODULE_STRING("group_repo_url", view->group_repo_url);
    ABK_MODULE_STRING("type", "builtin");
    ABK_MODULE_STRING("source", view->source);
    ABK_MODULE_STRING("module_dir", view->module_dir);
    ABK_MODULE_STRING("web_root", view->web_root);
#undef ABK_MODULE_STRING
    ret = abk_control_json_bool(buffer, "readonly", view->readonly, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "controllable", view->controllable, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "enabled", view->enabled, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "requires_companion_app",
                                view->requires_companion_app, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "settings_supported",
                                view->settings_supported, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "per_app_supported",
                                view->per_app_supported, true);
    if (ret)
        return ret;
    ret = abk_control_json_u32(buffer, "oobe_priority", view->oobe_priority, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "has_web_ui", view->has_web_ui, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "has_action_script",
                                view->has_action_script, true);
    if (ret)
        return ret;
    ret = abk_control_json_bool(buffer, "action_supported",
                                view->action_supported, false);
    if (ret)
        return ret;
    return abk_control_buf_append(buffer, "}");
}

static bool abk_control_manifest_has_id(const char *id)
{
    size_t index;

    for (index = 0; index < abk_control_manifest_count; index++) {
        if (!strcmp(abk_control_manifest[index].id, id))
            return true;
    }
    return false;
}

static const struct abk_control_snapshot *abk_control_snapshot_find(
    const struct abk_control_snapshot *snapshot, size_t count, const char *id)
{
    size_t index;

    for (index = 0; index < count; index++) {
        if (!strcmp(snapshot[index].ops->id, id))
            return &snapshot[index];
    }
    return NULL;
}

static int abk_control_build_status(char **out, size_t *out_len)
{
    struct abk_control_snapshot snapshot[ABK_CONTROL_MAX_REGISTRATIONS];
    struct abk_control_buffer buffer = {};
    bool first = true;
    size_t snapshot_count = 0;
    size_t index;
    int ret;

    ret = abk_control_snapshot_registry(snapshot, ARRAY_SIZE(snapshot),
                                        &snapshot_count);
    if (ret)
        return ret;

    ret = abk_control_buf_append(&buffer, "{\n  \"schema\": 6,\n");
    if (ret)
        goto out;
    ret = abk_control_append_manager(&buffer);
    if (ret)
        goto out;
    ret = abk_control_append_build(&buffer);
    if (ret)
        goto out;
    ret = abk_control_buf_append(&buffer, "  \"modules\": [");
    if (ret)
        goto out;

    for (index = 0; index < abk_control_manifest_count; index++) {
        const struct abk_control_manifest_entry *entry =
            &abk_control_manifest[index];
        const struct abk_control_snapshot *runtime =
            abk_control_snapshot_find(snapshot, snapshot_count, entry->id);
        struct abk_control_module_view view;

        abk_control_view_from_manifest(&view, entry, runtime ? runtime->ops : NULL,
                                       runtime ? runtime->enabled : true);
        ret = abk_control_append_module(&buffer, &first, &view);
        if (ret)
            goto out;
    }
    for (index = 0; index < snapshot_count; index++) {
        struct abk_control_module_view view;

        if (abk_control_manifest_has_id(snapshot[index].ops->id))
            continue;
        abk_control_view_from_manifest(&view, NULL, snapshot[index].ops,
                                       snapshot[index].enabled);
        ret = abk_control_append_module(&buffer, &first, &view);
        if (ret)
            goto out;
    }
    ret = abk_control_buf_append(&buffer, "\n  ],\n  \"extension_modules\": [");
    if (ret)
        goto out;

    first = true;
    for (index = 0; index < abk_control_manifest_count; index++) {
        const struct abk_control_manifest_entry *entry =
            &abk_control_manifest[index];
        const struct abk_control_snapshot *runtime =
            abk_control_snapshot_find(snapshot, snapshot_count, entry->id);
        struct abk_control_module_view view;

        abk_control_view_from_manifest(&view, entry, runtime ? runtime->ops : NULL,
                                       runtime ? runtime->enabled : true);
        if (!abk_control_is_extension(&view))
            continue;
        ret = abk_control_append_module(&buffer, &first, &view);
        if (ret)
            goto out;
    }
    for (index = 0; index < snapshot_count; index++) {
        struct abk_control_module_view view;

        if (abk_control_manifest_has_id(snapshot[index].ops->id))
            continue;
        abk_control_view_from_manifest(&view, NULL, snapshot[index].ops,
                                       snapshot[index].enabled);
        if (!abk_control_is_extension(&view))
            continue;
        ret = abk_control_append_module(&buffer, &first, &view);
        if (ret)
            goto out;
    }
    ret = abk_control_buf_append(&buffer, "\n  ]\n}\n");
    if (ret)
        goto out;

    *out = buffer.data;
    *out_len = buffer.len;
    buffer.data = NULL;
out:
    kfree(buffer.data);
    abk_control_release_snapshot(snapshot, snapshot_count);
    return ret;
}

int abk_control_register(const struct abk_control_ops *ops)
{
    struct abk_control_registration *registration;
    struct abk_control_registration *existing;
    size_t count = 0;
    int ret = 0;

    if (!ops || !abk_control_valid_id(ops->id))
        return -EINVAL;
    registration = kzalloc(sizeof(*registration), GFP_KERNEL);
    if (!registration)
        return -ENOMEM;
    registration->ops = ops;
    atomic_set(&registration->active, 0);
    init_completion(&registration->idle);

    mutex_lock(&abk_control_lock);
    if (abk_control_shutting_down)
        ret = -ESHUTDOWN;
    else if (abk_control_find_locked(ops->id))
        ret = -EEXIST;
    else {
        list_for_each_entry(existing, &abk_control_registry, node)
            count++;
        if (count >= ABK_CONTROL_MAX_REGISTRATIONS)
            ret = -ENOSPC;
    }
    if (!ret)
        list_add_tail(&registration->node, &abk_control_registry);
    mutex_unlock(&abk_control_lock);
    if (ret)
        kfree(registration);
    return ret;
}
EXPORT_SYMBOL_GPL(abk_control_register);

void abk_control_unregister(const struct abk_control_ops *ops)
{
    struct abk_control_registration *registration;
    bool wait;

    if (!ops)
        return;
    mutex_lock(&abk_control_lock);
    list_for_each_entry(registration, &abk_control_registry, node) {
        if (registration->ops != ops)
            continue;
        list_del(&registration->node);
        WRITE_ONCE(registration->unregistering, true);
        wait = atomic_read(&registration->active) != 0;
        mutex_unlock(&abk_control_lock);
        if (wait)
            wait_for_completion(&registration->idle);
        kfree(registration);
        return;
    }
    mutex_unlock(&abk_control_lock);
}
EXPORT_SYMBOL_GPL(abk_control_unregister);

int abk_control_get_status_json(char **out, size_t *out_len)
{
    if (!out || !out_len)
        return -EINVAL;
    *out = NULL;
    *out_len = 0;
    return abk_control_build_status(out, out_len);
}
EXPORT_SYMBOL_GPL(abk_control_get_status_json);

static int abk_control_set_enabled(const char *id, bool enabled)
{
    struct abk_control_registration *registration;
    int ret;

    registration = abk_control_get_ref(id);
    if (!registration)
        return abk_control_manifest_has_id(id) ? -EOPNOTSUPP : -ENOENT;
    if (!registration->ops->set_enabled)
        ret = -EOPNOTSUPP;
    else
        ret = registration->ops->set_enabled(enabled, registration->ops->data);
    abk_control_put_ref(registration);
    return ret;
}

static int abk_control_run_module_command(const char *id, const char *payload)
{
    struct abk_control_registration *registration;
    int ret;

    registration = abk_control_get_ref(id);
    if (!registration)
        return abk_control_manifest_has_id(id) ? -EOPNOTSUPP : -ENOENT;
    if (!registration->ops->run_command)
        ret = -EOPNOTSUPP;
    else
        ret = registration->ops->run_command(payload,
                                             registration->ops->data);
    abk_control_put_ref(registration);
    return ret;
}

int abk_control_run_command(const char *input, size_t count)
{
    char command[ABK_CONTROL_MAX_COMMAND + 1];
    char *cursor;
    char *verb;
    char *id;
    char *payload;
    size_t length;

    if (!input || !count || count > ABK_CONTROL_MAX_COMMAND)
        return -EINVAL;
    length = count;
    memcpy(command, input, length);
    command[length] = '\0';
    cursor = strim(command);
    verb = strsep(&cursor, " \t\r\n");
    id = strsep(&cursor, " \t\r\n");
    if (!verb || !verb[0] || !id || !id[0] || !abk_control_valid_id(id))
        return -EINVAL;
    payload = cursor ? strim(cursor) : (char *)"";

    if (!strcmp(verb, "enable"))
        return abk_control_set_enabled(id, true);
    if (!strcmp(verb, "disable"))
        return abk_control_set_enabled(id, false);
    if (!strcmp(verb, "status")) {
        struct abk_control_registration *registration =
            abk_control_get_ref(id);

        if (registration) {
            abk_control_put_ref(registration);
            return 0;
        }
        return abk_control_manifest_has_id(id) ? 0 : -ENOENT;
    }
    if (!strcmp(verb, "command"))
        return abk_control_run_module_command(id, payload);
    return -EINVAL;
}
EXPORT_SYMBOL_GPL(abk_control_run_command);

void abk_control_shutdown(void)
{
    struct abk_control_registration *registration;
    bool wait;

    for (;;) {
        mutex_lock(&abk_control_lock);
        if (list_empty(&abk_control_registry)) {
            abk_control_shutting_down = true;
            mutex_unlock(&abk_control_lock);
            return;
        }
        registration = list_first_entry(&abk_control_registry,
                                        struct abk_control_registration, node);
        list_del(&registration->node);
        WRITE_ONCE(registration->unregistering, true);
        wait = atomic_read(&registration->active) != 0;
        mutex_unlock(&abk_control_lock);
        if (wait)
            wait_for_completion(&registration->idle);
        kfree(registration);
    }
}
