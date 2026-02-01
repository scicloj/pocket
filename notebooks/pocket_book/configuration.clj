;; # Configuration

^{:kindly/options {:kinds-that-hide-code #{:kind/doc}}}
(ns pocket-book.configuration
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; ## Cache directory

;; Set the cache directory via the `POCKET_BASE_CACHE_DIR` environment variable:
;;
;; ```bash
;; export POCKET_BASE_CACHE_DIR=/path/to/cache
;; ```
;;
;; Or programmatically with `set-base-cache-dir!`:

(pocket/set-base-cache-dir! "/tmp/pocket-demo-config")

;; ## In-memory cache and thread safety

;; Pocket maintains an in-memory cache in front of the disk layer,
;; backed by [core.cache](https://github.com/clojure/core.cache).
;; This provides two benefits:
;;
;; 1. **Performance** — repeated derefs of the same computation skip disk I/O entirely
;;    (until the entry is evicted from memory).
;; 2. **[Thread safety](https://en.wikipedia.org/wiki/Thread_safety)** — when multiple
;;    threads deref the same `Cached` value concurrently, the computation runs exactly once.
;;    This is coordinated via a [`ConcurrentHashMap`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html)
;;    of [delays](https://clojure.org/reference/concurrency#delay), so no duplicate work is performed.
;;
;; By default, the in-memory layer uses an **[LRU](https://en.wikipedia.org/wiki/Cache_replacement_policies#LRU)**
;; (Least Recently Used) policy with a threshold of 256 entries. You can configure
;; the policy and its parameters with `set-mem-cache-options!`.

;; ## Cache policies

;; Supported policies and their parameters:
;;
;; | Policy | Key | Parameters |
;; |--------|---------|-----------|
;; | [LRU](https://en.wikipedia.org/wiki/Cache_replacement_policies#LRU) (Least Recently Used) | `:lru` | `:threshold` (default 256) |
;; | [FIFO](https://en.wikipedia.org/wiki/Cache_replacement_policies#FIFO) (First In First Out) | `:fifo` | `:threshold` (default 256) |
;; | [LFU](https://en.wikipedia.org/wiki/Least_frequently_used) (Least Frequently Used) | `:lu` | `:threshold` (default 256) |
;; | [TTL](https://en.wikipedia.org/wiki/Time_to_live) (Time To Live) | `:ttl` | `:ttl` in ms (default 30000) |
;; | [LIRS](https://en.wikipedia.org/wiki/LIRS_caching_algorithm) | `:lirs` | `:s-history-limit`, `:q-history-limit` |
;; | [Soft references](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/SoftReference.html) | `:soft` | (none — uses JVM garbage collection) |
;; | Basic (unbounded) | `:basic` | (none) |

;; For example, to use a FIFO policy with a smaller threshold:

(pocket/set-mem-cache-options! {:policy :fifo :threshold 100})

;; Or a TTL policy where entries expire after 60 seconds:

(pocket/set-mem-cache-options! {:policy :ttl :ttl 60000})

;; Reset to the default LRU policy:

(pocket/set-mem-cache-options! {:policy :lru :threshold 256})

;; ## Cleanup

;; To delete all cached values (both disk and in-memory), use `cleanup!`:

(pocket/cleanup!)
