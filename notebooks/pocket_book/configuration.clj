;; # Configuration

(ns pocket-book.configuration
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; ## Setup
;; Pocket resolves configuration using a precedence chain
;; (for both cache directory and in-memory cache options),
;; from highest to lowest priority:
;;
;; 1. `binding` (thread-local override)
;; 2. `set-*!` functions (`set-base-cache-dir!`, `set-mem-cache-options!`)
;; 3. Environment variable (`POCKET_BASE_CACHE_DIR`, `POCKET_MEM_CACHE`)
;; 4. `pocket.edn` on classpath
;; 5. Library defaults (`pocket-defaults.edn`)

^:kindly/hide-code
(kind/mermaid
 "flowchart TD
    B(binding) -->|if nil| S(set-*!)
    S -->|if nil| E(Environment variable)
    E -->|if nil| P(pocket.edn)
    P -->|if nil| D(Hardcoded default)
    style B fill:#4a9,color:#fff
    style D fill:#888,color:#fff")

;; ## `pocket.edn`
;;
;; Place a `pocket.edn` file on your classpath root for declarative,
;; project-level configuration:
;;
;; ```edn
;; {:base-cache-dir "/tmp/my-project-cache"
;;  :mem-cache {:policy :lru :threshold 256}}
;; ```
;;
;; It is re-read on each cache operation (with a 1-second TTL cache),
;; so changes take effect quickly during REPL development.
;; It provides defaults that can be
;; overridden by environment variables, `set-*!` calls, or `binding`.

;; ## Library defaults
;;
;; Pocket ships with `pocket-defaults.edn` containing the library defaults.
;; These are used when no other configuration is provided:

(-> (clojure.java.io/resource "pocket-defaults.edn")
    slurp
    clojure.edn/read-string)

;; You can override any of these via `pocket.edn`, environment variables,
;; or the `set-*!` functions.


;; ## Cache directory

;; The cache directory can be set in several ways.

;; **Environment variable** — `POCKET_BASE_CACHE_DIR`:
;;
;; ```bash
;; export POCKET_BASE_CACHE_DIR=/path/to/cache
;; ```

;; **Programmatically** with `set-base-cache-dir!`:

(pocket/set-base-cache-dir! "/tmp/pocket-demo-config")

(pocket/cleanup!)

;; You can inspect the effective resolved configuration at any time:

(pocket/config)

(kind/test-last [(fn [cfg] (= "/tmp/pocket-demo-config" (:base-cache-dir cfg)))])

;; **Thread-local binding** (useful for tests):
;;
;; ```clojure
;; (binding [pocket/*base-cache-dir* "/tmp/test-cache"]
;;   @(pocket/cached #'my-fn args))
;; ```

;; ## In-memory cache and thread safety

;; Pocket maintains an in-memory cache in front of the disk layer,
;; backed by [core.cache](https://github.com/clojure/core.cache).
^:kindly/hide-code
(kind/mermaid
 "flowchart LR
    D(deref) --> MC{In-memory\ncache?}
    MC -->|hit| R[Return value]
    MC -->|miss| DC{Disk\ncache?}
    DC -->|hit| R
    DC -->|miss| C[Compute] --> W[Write to disk] --> R")

;; This provides two benefits:
;;
;; 1. **Performance** — repeated derefs of the same computation skip disk I/O entirely
;;    (until the entry is evicted from memory).
;; 2. **[Thread safety](https://en.wikipedia.org/wiki/Thread_safety)** — when multiple
;;    threads deref the same `Cached` value concurrently, the computation runs exactly once.
;;    This is coordinated via a [`ConcurrentHashMap`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html)
;;    of [delays](https://clojure.org/reference/concurrency#delay), so no duplicate work is performed.
;;    See the [Concurrency chapter](pocket_book.concurrency.html) for a detailed explanation.
;;
;; By default, the in-memory layer uses an **[LRU](https://en.wikipedia.org/wiki/Cache_replacement_policies#LRU)**
;; (Least Recently Used) policy — see defaults above.

;; ## Cache policies

;; Supported policies and their parameters:
;;
;; | Policy | Key | Parameters |
;; |--------|---------|-----------|
;; | [LRU](https://en.wikipedia.org/wiki/Cache_replacement_policies#LRU) (Least Recently Used) | `:lru` | `:threshold` (see defaults above) |
;; | [FIFO](https://en.wikipedia.org/wiki/Cache_replacement_policies#FIFO) (First In First Out) | `:fifo` | `:threshold` (see defaults above) |
;; | [LFU](https://en.wikipedia.org/wiki/Least_frequently_used) (Least Frequently Used) | `:lu` | `:threshold` (see defaults above) |
;; | [TTL](https://en.wikipedia.org/wiki/Time_to_live) (Time To Live) | `:ttl` | `:ttl` in ms (see defaults above) |
;; | [LIRS](https://en.wikipedia.org/wiki/LIRS_caching_algorithm) | `:lirs` | `:s-history-limit`, `:q-history-limit` |
;; | [Soft references](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/SoftReference.html) | `:soft` | (none — uses JVM garbage collection) |
;; | Basic (unbounded) | `:basic` | (none) |

;; Configure via `set-mem-cache-options!`:

(pocket/set-mem-cache-options! {:policy :fifo :threshold 100})

(kind/test-last [(fn [result] (= :fifo (:policy result)))])

;; Or a TTL policy where entries expire after 60 seconds:

(pocket/set-mem-cache-options! {:policy :ttl :ttl 60000})

;; Reset to the default LRU policy:

(pocket/set-mem-cache-options! {:policy :lru :threshold 256})

;; **Environment variable** — `POCKET_MEM_CACHE` (EDN string):
;;
;; ```bash
;; export POCKET_MEM_CACHE='{:policy :lru :threshold 512}'
;; ```

;; **Thread-local binding** (useful for tests):
;;
;; ```clojure
;; (binding [pocket/*mem-cache-options* {:policy :fifo :threshold 50}]
;;   @(pocket/cached #'my-fn args))
;; ```
;;
;; **Caution**: binding `*mem-cache-options*` reconfigures the shared global
;; mem-cache, which affects all threads. This is useful for test fixtures
;; but should be avoided in concurrent production use with different policies.

;; ## Storage policies

;; Pocket supports three storage modes, controlled by `*storage*`:
;;
;; | Mode | Behavior |
;; |------|----------|
;; | `:mem+disk` | In-memory cache backed by disk persistence (default) |
;; | `:mem` | In-memory cache only — no disk I/O |
;; | `:none` | No shared cache — instance-local memoization only |
;;
;; `:mem` is useful for cheap computations that are called many times
;; with the same arguments. It avoids disk serialization overhead while
;; still deduplicating concurrent access.
;;
;; `:none` is useful for trivially cheap functions that you want to
;; participate in DAG identity tracking (see
;; [Recursive Caching in Pipelines](pocket_book.recursive_caching_in_pipelines.html))
;; without any shared caching. Each `Cached` instance memoizes its own
;; result (like a `delay`), but separate instances recompute.

;; **Programmatically** with `set-storage!`:

(pocket/set-storage! :mem)

(pocket/config)

(kind/test-last [(fn [cfg] (= :mem (:storage cfg)))])

;; **Per-function** via `caching-fn` options:
;;
;; ```clojure
;; (def fast-fn* (pocket/caching-fn #'fast-fn {:storage :mem}))
;; (def identity-fn* (pocket/caching-fn #'identity-fn {:storage :none}))
;; ```
;;
;; The option map can also override `:cache-dir` and `:mem-cache`:
;;
;; ```clojure
;; (pocket/caching-fn #'f {:storage :mem+disk
;;                         :cache-dir "/data/project-cache"
;;                         :mem-cache {:policy :ttl :ttl 60000}})
;; ```

;; **Thread-local binding**:
;;
;; ```clojure
;; (binding [pocket/*storage* :mem]
;;   @(pocket/cached #'my-fn args))
;; ```

;; **Environment variable** — `POCKET_STORAGE`:
;;
;; ```bash
;; export POCKET_STORAGE=mem
;; ```

;; **Note**: `:mem`-only entries do not appear in `cache-entries` or
;; `cache-stats`, which scan the disk cache. `:none` entries are not
;; tracked anywhere — they exist only as in-memory `Cached` objects.

;; Reset to default:

(pocket/set-storage! nil)

(kind/test-last [(fn [_] (= :mem+disk (:storage (pocket/config))))])

;; ## Cleanup

;; To delete all cached values (both disk and in-memory), use `cleanup!`:

(pocket/cleanup!)
