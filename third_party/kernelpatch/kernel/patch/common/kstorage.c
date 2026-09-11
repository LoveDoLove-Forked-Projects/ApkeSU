#include <kstorage.h>

#include <linux/kernel.h>
#include <linux/rculist.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/list.h>
#include <compiler.h>
#include <stdbool.h>
#include <symbol.h>
#include <uapi/asm-generic/errno.h>
#include <linux/list.h>
#include <linux/string.h>
#include <linux/err.h>
#include <linux/errno.h>
#include <linux/vmalloc.h>
#include <kputils.h>

#define KSTRORAGE_MAX_GROUP_NUM 4
#define KSTORAGE_MAX_DATA_LEN (1024 * 1024)

// static atomic64_t used_max_group = ATOMIC_INIT(0);
static int used_max_group = -1;
static struct list_head kstorage_groups[KSTRORAGE_MAX_GROUP_NUM];
static spinlock_t kstorage_glocks[KSTRORAGE_MAX_GROUP_NUM];
static int group_sizes[KSTRORAGE_MAX_GROUP_NUM] = { 0 };
static spinlock_t used_max_group_lock;

static void reclaim_callback(struct rcu_head *rcu)
{
    struct kstorage *ks = container_of(rcu, struct kstorage, rcu);
    kvfree(ks);
}

int try_alloc_kstroage_group()
{
    int group;

    spin_lock(&used_max_group_lock);
    if (used_max_group + 1 >= KSTRORAGE_MAX_GROUP_NUM) {
        spin_unlock(&used_max_group_lock);
        return -1;
    }
    group = ++used_max_group;
    spin_unlock(&used_max_group_lock);
    return group;
}

int kstorage_group_size(int gid)
{
    if (gid < 0 || gid >= KSTRORAGE_MAX_GROUP_NUM) return -ENOENT;
    return READ_ONCE(group_sizes[gid]);
}

int write_kstorage(int gid, long did, void *data, int offset, int len, bool data_is_user)
{
    if (gid < 0 || gid >= KSTRORAGE_MAX_GROUP_NUM) return -ENOENT;
    if (offset < 0 || len < 0 || len > KSTORAGE_MAX_DATA_LEN) return -EINVAL;
    if (len && !data) return -EFAULT;

    struct list_head *head = &kstorage_groups[gid];
    spinlock_t *lock = &kstorage_glocks[gid];
    struct kstorage *old = 0;
    struct kstorage *pos;

    void *user_copy = 0;
    if (len && data_is_user) {
        user_copy = memdup_user((const char __user *)data + offset, len);
        if (IS_ERR(user_copy)) return PTR_ERR(user_copy);
    }

    struct kstorage *new = (struct kstorage *)vmalloc(sizeof(struct kstorage) + (size_t)len);
    if (!new) {
        kvfree(user_copy);
        return -ENOMEM;
    }
    new->gid = gid;
    new->did = did;
    new->dlen = 0;
    if (len) {
        if (data_is_user) {
            memcpy(new->data, user_copy, len);
        } else {
            memcpy(new->data, (const char *)data + offset, len);
        }
    }
    kvfree(user_copy);
    new->dlen = len;

    spin_lock(lock);
    list_for_each_entry(pos, head, list) {
        if (pos->did == did) {
            old = pos;
            break;
        }
    }
    if (old) { // update
        list_replace_rcu(&old->list, &new->list);
    } else { // add new one
        list_add_rcu(&new->list, head);
        group_sizes[gid]++;
    }
    spin_unlock(lock);

    if (old) {
        call_rcu(&old->rcu, reclaim_callback);
    }
    return 0;
}
KP_EXPORT_SYMBOL(write_kstorage);

const struct kstorage *get_kstorage(int gid, long did)
{
    if (gid < 0 || gid >= KSTRORAGE_MAX_GROUP_NUM) return ERR_PTR(-ENOENT);

    struct list_head *head = &kstorage_groups[gid];
    struct kstorage *pos = 0;

    list_for_each_entry_rcu(pos, head, list)
    {
        if (pos->did == did) {
            return pos;
        }
    }

    return ERR_PTR(-ENOENT);
}
KP_EXPORT_SYMBOL(get_kstorage);

int on_each_kstorage_elem(int gid, on_kstorage_cb cb, void *udata)
{
    if (gid < 0 || gid >= KSTRORAGE_MAX_GROUP_NUM) return -ENOENT;
    if (!cb) return -EINVAL;

    int rc = 0;

    struct list_head *head = &kstorage_groups[gid];
    struct kstorage *pos = 0;

    rcu_read_lock();

    list_for_each_entry_rcu(pos, head, list)
    {
        rc = cb(pos, udata);
        if (rc) break;
    }

    rcu_read_unlock();

    return rc;
}
KP_EXPORT_SYMBOL(on_each_kstorage_elem);

int read_kstorage(int gid, long did, void *data, int offset, int len, bool data_is_user)
{
    int rc = 0;
    if (gid < 0 || gid >= KSTRORAGE_MAX_GROUP_NUM) return -ENOENT;
    if (offset < 0 || len < 0) return -EINVAL;
    if (len && !data) return -EFAULT;
    rcu_read_lock();

    const struct kstorage *pos = get_kstorage(gid, did);

    if (IS_ERR(pos)) {
        rcu_read_unlock();
        return PTR_ERR(pos);
    }

    if (offset >= pos->dlen || len == 0) {
        rcu_read_unlock();
        return 0;
    }
    int available = pos->dlen - offset;
    int min_len = len < available ? len : available;

    if (data_is_user) {
        int cplen = compat_copy_to_user(data, pos->data + offset, min_len);
        if (cplen != min_len) {
            logkfe("compat_copy_to_user error: %d", cplen);
            rc = -EFAULT;
        }
    } else {
        memcpy(data, pos->data + offset, min_len);
    }

    rcu_read_unlock();
    return rc;
}
KP_EXPORT_SYMBOL(read_kstorage);

int list_kstorage_ids(int gid, long *ids, int idslen, bool data_is_user)
{
    if (gid < 0 || gid >= KSTRORAGE_MAX_GROUP_NUM) return -ENOENT;
    if (idslen < 0 || (idslen && !ids)) return -EINVAL;

    int cnt = 0;

    struct list_head *head = &kstorage_groups[gid];
    struct kstorage *pos = 0;

    rcu_read_lock();

    list_for_each_entry_rcu(pos, head, list)
    {
        if (cnt >= idslen) break;

        if (data_is_user) {
            int cplen = compat_copy_to_user(ids + cnt, &pos->did, sizeof(pos->did));
            if (cplen != sizeof(pos->did)) {
                logkfe("compat_copy_to_user error: %d", cplen);
                rcu_read_unlock();
                return -EFAULT;
            }
        } else {
            memcpy(ids + cnt, &pos->did, sizeof(pos->did));
        }
        cnt++;
    }

    rcu_read_unlock();

    return cnt;
}
KP_EXPORT_SYMBOL(list_kstorage_ids);

int remove_kstorage(int gid, long did)
{
    int rc = -ENOENT;
    if (gid < 0 || gid >= KSTRORAGE_MAX_GROUP_NUM) return rc;

    struct list_head *head = &kstorage_groups[gid];
    spinlock_t *lock = &kstorage_glocks[gid];
    struct kstorage *pos = 0;

    spin_lock(lock);

    list_for_each_entry(pos, head, list)
    {
        if (pos->did == did) {
            list_del_rcu(&pos->list);
            group_sizes[gid]--;
            spin_unlock(lock);

            bool async = true;
            if (async) {
                call_rcu(&pos->rcu, reclaim_callback);
            } else {
                synchronize_rcu();
                kvfree(pos);
            }
            return 0;
        }
    }

    spin_unlock(lock);

    return rc;
}
KP_EXPORT_SYMBOL(remove_kstorage);

int kstorage_init()
{
    for (int i = 0; i < KSTRORAGE_MAX_GROUP_NUM; i++) {
        INIT_LIST_HEAD(&kstorage_groups[i]);
        spin_lock_init(&kstorage_glocks[i]);
    }
    spin_lock_init(&used_max_group_lock);

    return 0;
}
