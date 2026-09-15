#ifndef __KSU_H_THRONE_TRACKER
#define __KSU_H_THRONE_TRACKER

#define TRACK_THRONE_PRUNE_ONLY (1U << 0)
#define TRACK_THRONE_FORCE_SEARCH_MGR (1U << 1)
#define TRACK_THRONE_FORCE_SYNCHRONOUS (1U << 2)

#ifdef CONFIG_KSU_DISABLE_MANAGER
static inline void ksu_throne_tracker_init(void)
{
}

static inline void ksu_throne_tracker_exit(void)
{
}

static inline void track_throne(unsigned int flags)
{
    (void)flags;
}
#else
void ksu_throne_tracker_init(void);

void ksu_throne_tracker_exit(void);

void track_throne(unsigned int flags);
#endif

#if IS_ENABLED(CONFIG_ABK_CONTROL)
#ifdef CONFIG_KSU_DISABLE_MANAGER
static inline void abk_try_register_manager(void)
{
}
#else
void abk_try_register_manager(void);
#endif
#else
static inline void abk_try_register_manager(void)
{
}
#endif

#endif
