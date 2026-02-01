;; # Pocket - Filesystem-based Caching

;; Pocket provides [content-addressable](https://en.wikipedia.org/wiki/Content-addressable_storage)
;; caching with automatic [serialization](https://en.wikipedia.org/wiki/Serialization),
;; making it easy to cache expensive function calls to disk and reuse results
;; across sessions.
;;
;; Pocket uses a two-layer caching architecture:
;; - **Disk** — durable, content-addressable storage using [Nippy](https://github.com/taoensso/nippy) serialization
;; - **In-memory** — an [LRU](https://en.wikipedia.org/wiki/Cache_replacement_policies#LRU) cache
;;   (backed by [core.cache](https://github.com/clojure/core.cache))
;;   that avoids repeated disk reads and provides [thread-safe](https://en.wikipedia.org/wiki/Thread_safety) coordination
;;
;; When multiple threads deref the same `Cached` value concurrently,
;; the computation runs exactly once. Subsequent derefs are served
;; from the in-memory cache, falling back to disk if evicted.

^{:kindly/options {:kinds-that-hide-code #{:kind/doc}}}
(ns index
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; ## Basic Walkthrough

;; ### Setup

;; First, we set up a cache directory and define an expensive computation:

(def cache-dir "/tmp/pocket-demo")

(pocket/set-base-cache-dir! cache-dir)

(defn expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y))

;; ### Background: deref in Clojure
;;
;; In Clojure, [`deref`](https://clojure.org/reference/concurrency#deref)
;; extracts a value from a reference type. It can be written as `(deref x)`
;; or with the shorthand reader macro `@x` — both are equivalent.
;; Pocket's `cached` returns a `Cached` object that implements `IDeref`,
;; so you use `@` (or `deref`) to trigger the computation and retrieve
;; the result.

;; ### Creating a cached computation

;; `cached` creates a [lazy](https://en.wikipedia.org/wiki/Lazy_evaluation) cached computation.
;; It returns a `Cached` object — the computation won't run until we deref it:

(def cached-result
  (pocket/cached #'expensive-calculation 10 20))

cached-result

;;; First deref (computes and caches):
(time @cached-result)

;;; Second deref (loaded from cache, instant):
(time @cached-result)

;; ### Wrapping functions with `cached-fn`

;; For convenience, `cached-fn` wraps a function so that every call
;; returns a `Cached` object:

(def cached-expensive
  (pocket/cached-fn #'expensive-calculation))

;;; First call:
(time @(cached-expensive 5 15))

;;; Same args — cache hit:
(time @(cached-expensive 5 15))

;;; Different args — new computation:
(time @(cached-expensive 7 8))

;; ## Guides

;; ### Recursive caching in pipelines

;; When you pass a `Cached` value as an argument to another cached function,
;; Pocket handles this recursively. The cache key for the outer computation
;; is derived from the **identity** of the inner computation (its function
;; name and arguments), not from its result. This means the entire pipeline's
;; cache key captures the full [computation graph](https://en.wikipedia.org/wiki/Dataflow_programming).

;; For this to work, functions in the pipeline should call `maybe-deref`
;; on arguments that may be `Cached` objects. This way, the function receives
;; the actual value when executed, while the cache key still reflects the
;; upstream computation identity.

(defn load-dataset [path]
  (println "Loading dataset from" path "...")
  (Thread/sleep 300)
  {:data [1 2 3 4 5] :source path})

(defn preprocess [data opts]
  (let [data (pocket/maybe-deref data)]
    (println "Preprocessing with options:" opts)
    (Thread/sleep 300)
    (update data :data #(map (fn [x] (* x (:scale opts))) %))))

(defn train-model [data params]
  (let [data (pocket/maybe-deref data)]
    (println "Training model with params:" params)
    (Thread/sleep 300)
    {:model :trained :accuracy 0.95 :data data}))

;; Chain cached computations in a pipeline:

;;; First pipeline run:
(time
 (-> "data/raw.csv"
     ((pocket/cached-fn #'load-dataset))
     ((pocket/cached-fn #'preprocess) {:scale 2})
     ((pocket/cached-fn #'train-model) {:epochs 100})
     deref
     (select-keys [:model :accuracy])))

;; Run the same pipeline again — everything loads from cache:

;;; Second pipeline run (all cached):
(time
 (-> "data/raw.csv"
     ((pocket/cached-fn #'load-dataset))
     ((pocket/cached-fn #'preprocess) {:scale 2})
     ((pocket/cached-fn #'train-model) {:epochs 100})
     deref
     (select-keys [:model :accuracy])))

;; Each step caches independently. If you change only the last step
;; (e.g., different training params), the upstream steps load from cache while
;; only the final step recomputes.

;; ### Nil handling

;; Pocket properly handles `nil` values. Since the cache uses files on disk,
;; it needs to distinguish "never computed" from "computed and got `nil`".
;; It does this with a special marker file:

(defn returns-nil [] nil)

(def nil-result (pocket/cached #'returns-nil))

;;; Cached nil value:
(deref nil-result)

;;; Loading nil from cache:
(deref nil-result)

;; ### In-memory cache and thread safety

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

;; ### Usage notes

;; #### Use [vars](https://clojure.org/reference/vars) for functions
;;
;; Always use `#'function-name` (var), not `function-name` (function object).
;; Vars have stable names that produce consistent cache keys across sessions.
;; Function objects have unstable identity and would create a new cache entry
;; every time the code is reloaded.
;;
;; ```clojure
;; ;; ✅ Good — stable cache key from var name
;; (pocket/cached #'my-function args)
;;
;; ;; ❌ Bad — unstable identity, defeats caching
;; (pocket/cached my-function args)
;; ```

;; #### [Cache invalidation](https://en.wikipedia.org/wiki/Cache_invalidation)
;;
;; Pocket does **not** detect function implementation changes. If you modify
;; a function's body, the cache key remains the same (it's based on the
;; function name and arguments, not the implementation). You must manually
;; delete the cache directory to invalidate stale entries, e.g. with `cleanup!`.

;; #### Versioning function inputs
;;
;; Since Pocket derives cache keys from function name and arguments (not the
;; function body), changing a function's implementation won't automatically
;; produce a new cache entry. Rather than invalidating the cache, a practical
;; approach is to add a version key to the function's input map. When you
;; change the implementation, bump the version — Pocket will treat it as
;; a new computation with a distinct cache key:

(defn process-data [data opts]
  (let [data (pocket/maybe-deref data)]
    ;; v3: switched from mean to median
    (:data data)))

;;; Version 3 of the processing:
(deref (pocket/cached #'process-data
                      {:data [1 2 3]}
                      {:scale 2 :version 3}))

;; When the implementation changes again, simply bump to `:version 4`.
;; Previous cached results remain on disk (useful if you need to compare),
;; while the new version computes fresh results.

;; #### Configuration
;;
;; Set the cache directory via the `POCKET_BASE_CACHE_DIR` environment variable:
;;
;; ```bash
;; export POCKET_BASE_CACHE_DIR=/path/to/cache
;; ```
;;
;; Or programmatically with `set-base-cache-dir!`.

;; ### Cleanup

;; To delete all cached values (both disk and in-memory), use `cleanup!`:

(pocket/cleanup!)

;; ## API Reference

(kind/doc #'pocket/*base-cache-dir*)

;; The current value:

pocket/*base-cache-dir*

(kind/doc #'pocket/set-base-cache-dir!)

(pocket/set-base-cache-dir! "/tmp/pocket-demo-2")
pocket/*base-cache-dir*

;; Restore it for the rest of the notebook:

(pocket/set-base-cache-dir! cache-dir)

(kind/doc #'pocket/cached)

;; `cached` returns a `Cached` object — the computation is not yet executed:

(def my-result (pocket/cached #'expensive-calculation 100 200))
my-result

;; The computation runs when we deref:

(deref my-result)

;; Derefing again loads from cache (no recomputation):

(deref my-result)

(kind/doc #'pocket/cached-fn)

;; `cached-fn` wraps a function so that every call returns a `Cached` object:

(def my-cached-fn (pocket/cached-fn #'expensive-calculation))

(deref (my-cached-fn 3 4))

;; Same args hit the cache:

(deref (my-cached-fn 3 4))

(kind/doc #'pocket/maybe-deref)

;; A plain value passes through unchanged:

(pocket/maybe-deref 42)

;; A `Cached` value gets derefed:

(pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200))

(kind/doc #'pocket/->id)

;; A var's identity is its name:

(pocket/->id #'expensive-calculation)

;; A map's identity is itself (with keys sorted for stability):

(pocket/->id {:b 2 :a 1})

;; A `Cached` object's identity captures the full computation —
;; function name and argument identities — without running it:

(pocket/->id (pocket/cached #'expensive-calculation 100 200))

;; `nil` is handled as well:

(pocket/->id nil)

(kind/doc #'pocket/set-mem-cache-options!)

;; Switch to a FIFO policy with 100 entries:

(pocket/set-mem-cache-options! {:policy :fifo :threshold 100})

;; Reset to default:

(pocket/set-mem-cache-options! {:policy :lru :threshold 256})

(kind/doc #'pocket/cleanup!)

(pocket/cleanup!)
