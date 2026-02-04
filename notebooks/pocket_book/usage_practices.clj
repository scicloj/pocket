;; # Usage Practices

;; This chapter covers practical patterns for day-to-day work with Pocket:
;; function identity, cache invalidation, testing, REPL introspection,
;; serialization constraints, and guidance on when Pocket is the right tool.

;; ## Setup

(ns pocket-book.usage-practices
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

(def test-dir "/tmp/pocket-dev-practices")
(pocket/set-base-cache-dir! test-dir)

;; ## Function Identity: Always Use Vars

;; Pocket requires functions to be passed as **vars** (`#'fn-name`),
;; not as bare function objects. This is the most common mistake:

;; ```clojure
;; ;; ❌ WRONG - bare function, unstable identity
;; (pocket/cached my-expensive-fn arg1 arg2)
;;
;; ;; ✅ CORRECT - var, stable identity
;; (pocket/cached #'my-expensive-fn arg1 arg2)
;; ```

;; **Why?** Function objects have different identity across JVM sessions,
;; making cache keys unpredictable. Vars provide stable symbol names
;; that survive restarts.

;; Pocket validates this and throws a clear error if you forget:

(defn example-fn [x] (* x x))

(try
  (pocket/cached example-fn 5)
  (catch Exception e
    (ex-message e)))

(kind/test-last [#(re-find #"requires a var" %)])

;; ## Cache Invalidation Strategies

;; Pocket does **not** automatically detect when a function's implementation
;; changes. You must invalidate manually. Here are the strategies:

;; ### Strategy 1: Manual Invalidation

;; Use `invalidate!` for specific entries or `invalidate-fn!` for all
;; entries of a function:

(pocket/cleanup!)

(defn transform [x] (* x 2))

;; Cache a computation:
(deref (pocket/cached #'transform 10))

(kind/test-last [= 20])

;; Function implementation changed — invalidate a single entry:
(pocket/invalidate! #'transform 10)

;; Or invalidate all entries for a function:

(deref (pocket/cached #'transform 1))
(deref (pocket/cached #'transform 2))

(pocket/invalidate-fn! #'transform)

;; ### Strategy 2: Versioning Pattern

;; Add a version key to your function's input. Bumping the version
;; creates new cache entries while preserving old ones for comparison:

(pocket/cleanup!)

(defn process-data [{:keys [data version]}]
  {:result (reduce + data)
   :version version})

;; Version 1:
(deref (pocket/cached #'process-data {:data [1 2 3] :version 1}))

(kind/test-last [= {:result 6 :version 1}])

;; After changing the function, bump version:
(deref (pocket/cached #'process-data {:data [1 2 3] :version 2}))

(kind/test-last [= {:result 6 :version 2}])

;; Both versions coexist in cache — useful for A/B comparison.

;; ### Strategy 3: Full Cleanup

;; For a fresh start, use `cleanup!` to delete the entire cache:

(pocket/cleanup!)

;; ## Testing with Pocket

;; Tests should be isolated from production caches and from each other.
;; Use `binding` and cleanup fixtures:

;; ### Test Fixture Pattern

;; ```clojure
;; (def test-cache-dir "/tmp/my-project-test-cache")
;;
;; (defn cleanup-fixture [f]
;;   (binding [pocket/*base-cache-dir* test-cache-dir]
;;     (pocket/cleanup!)
;;     (try
;;       (f)
;;       (finally
;;         (pocket/cleanup!)))))
;;
;; (use-fixtures :each cleanup-fixture)
;; ```

;; This ensures:
;; 1. Tests use a separate cache directory
;; 2. Cache is cleared before and after each test
;; 3. Tests don't affect each other

;; ### Verifying Cache Behavior

;; Use an atom to track computation calls:

(pocket/cleanup!)

(def call-count (atom 0))

(defn tracked-fn [x]
  (swap! call-count inc)
  (* x x))

;; First call computes:
(reset! call-count 0)

(let [result (deref (pocket/cached #'tracked-fn 5))
      calls @call-count]
  {:result result :calls calls})

(kind/test-last [= {:result 25 :calls 1}])

;; Second call uses cache (no additional computation):
(let [result (deref (pocket/cached #'tracked-fn 5))
      calls @call-count]
  {:result result :calls calls})

(kind/test-last [= {:result 25 :calls 1}])

;; ## REPL Development Workflow

;; ### Inspecting the Cache

;; Use introspection functions to understand cache state:

(pocket/cleanup!)
(deref (pocket/cached #'transform 1))
(deref (pocket/cached #'transform 2))
(deref (pocket/cached #'tracked-fn 3))

;; See all cached entries:
(count (pocket/cache-entries))

(kind/test-last [= 3])

;; Get aggregate statistics:
(:total-entries (pocket/cache-stats))

(kind/test-last [= 3])

;; Visualize cache structure:
(kind/code (pocket/dir-tree))

;; Each directory contains a `meta.edn` file with metadata
;; about the cached computation:

(-> (pocket/cache-entries)
    first
    :path
    (str "/meta.edn")
    slurp
    clojure.edn/read-string)

;; This same information is available through the API:

(pocket/cache-entries)

;; Filter entries by function name:

(pocket/cache-entries (str (ns-name *ns*) "/transform"))

;; ### Checking Cached Status

;; `Cached` values print their status without forcing computation:

(pocket/cleanup!)

(def pending-value (pocket/cached #'transform 99))

;; Before deref:
(pr-str pending-value)

(kind/test-last [#(re-find #":pending" %)])

(deref pending-value)

;; After deref:
(pr-str pending-value)

(kind/test-last [#(re-find #":cached" %)])

;; ## Debugging with Logging

;; Enable debug logging to see cache hits, misses, and writes.
;; See the [Logging chapter](pocket_book.logging.html) for setup.

;; With debug logging enabled, you'll see:
;; - `Cache miss, computing: ...` — computation triggered
;; - `Cache hit (memory): ...` — served from in-memory cache
;; - `Cache hit (disk): ...` — loaded from disk
;; - `Cache write: ...` — written to disk

;; ## Long Cache Keys

;; When a cache key string exceeds 240 characters, Pocket falls back to
;; using a SHA-1 hash as the directory name. This ensures the filesystem
;; can handle arbitrarily complex arguments while maintaining correct
;; caching behavior.

(pocket/cleanup!)

(defn process-long-text [text]
  (str "Processed: " (count text) " chars"))

(def long-text (apply str (repeat 300 "x")))

(deref (pocket/cached #'process-long-text long-text))

(kind/test-last [(fn [result] (clojure.string/starts-with? result "Processed:"))])

;; The entry is stored with a hash-based directory name:

(kind/code (pocket/dir-tree))

;; But `meta.edn` inside still contains the full details,
;; so `cache-entries` and `invalidate-fn!` work correctly:

(-> (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
    first
    :fn-name)

(kind/test-last [(fn [fn-name] (and fn-name (clojure.string/ends-with? fn-name "/process-long-text")))])

;; ## Serialization Constraints

;; Pocket uses [Nippy](https://github.com/ptaoussanis/nippy) for
;; serialization. Most Clojure data structures work, but some don't:

;; ### ✅ Safe to Cache
;; - Primitive types (numbers, strings, keywords, symbols)
;; - Collections (vectors, maps, sets, lists)
;; - Records and most deftypes
;; - Java Serializable objects

;; ### ⚠️ Requires Care
;; - Lazy sequences — may serialize only the realized portion;
;;   force them first with `doall` (see below)
;;
;; ### ❌ Cannot Cache
;; - Open file handles, streams
;; - Network connections, sockets
;; - Functions, closures (use vars instead)
;; - Atoms, refs, agents (stateful references)

;; ### Lazy Sequence Gotcha

;; Lazy sequences may cause issues. Force them before caching:

(pocket/cleanup!)

(defn generate-data [n]
  ;; doall forces full evaluation of the lazy sequence
  (doall (range n)))

(deref (pocket/cached #'generate-data 5))

(kind/test-last [= [0 1 2 3 4]])

;; ## When to Use Pocket
;;
;; ### Good use cases
;;
;; - **Data science pipelines** with expensive intermediate steps
;;   (data loading, preprocessing, feature engineering, model training)
;; - **Reproducible research** where cached intermediate results let you
;;   iterate on downstream steps without re-running upstream computations
;; - **Long-running computations** (minutes to hours) that need to survive
;;   JVM restarts, crashes, or machine reboots
;; - **Multi-threaded workflows** where multiple threads may request the
;;   same expensive computation — Pocket ensures it runs only once
;;
;; ### When to use something else
;;
;; - **Fast computations** (milliseconds) — use `clojure.core/memoize`
;; - **Memory-only caching** within a single session — use `memoize` or
;;   [`core.memoize`](https://github.com/clojure/core.memoize)
;; - **Frequently changing function implementations** — Pocket doesn't
;;   detect code changes, so you'd need to manually invalidate or use
;;   the versioning pattern
;;
;; ### Comparison to alternatives
;;
;; | Feature | Pocket | `clojure.core/memoize` | `core.memoize` |
;; |---------|--------|------------------------|----------------|
;; | Persistence | Disk + memory | Memory only | Memory only |
;; | Cross-session | Yes | No | No |
;; | Content-addressable | Yes | No | No |
;; | Lazy evaluation | `IDeref` | Eager | Eager |
;; | Eviction policies | LRU, FIFO, TTL, etc. | None | LRU, TTL, etc. |
;; | Thread-safe (single computation) | Yes | No | Yes |
;; | Pipeline caching | Yes (recursive) | No | No |

;; ## Known Limitations
;;
;; - **No automatic cache invalidation** — Pocket doesn't detect when a
;;   function's implementation changes. Use `invalidate!`, `invalidate-fn!`,
;;   or the versioning pattern described above.
;;
;; - **Requires serializable values** — Nippy handles most Clojure types,
;;   but you can't cache functions, atoms, channels, file handles, or
;;   other stateful objects.
;;
;; - **Disk cache grows indefinitely** — The in-memory cache supports
;;   eviction policies (LRU, TTL, etc.), but the disk cache has no
;;   automatic cleanup. Use `cleanup!` or `invalidate-fn!` periodically
;;   if disk space is a concern.
;;
;; - **No disk cache TTL** — Cached values on disk never expire
;;   automatically. If you need time-based expiration, you'll need to
;;   manage it externally or use `cleanup!`.

;; ## Summary

;; | Practice | Recommendation |
;; |----------|----------------|
;; | Function identity | Always use vars (`#'fn-name`) |
;; | Invalidation | Manual, versioning, or full cleanup |
;; | Testing | Use `binding` + cleanup fixtures |
;; | Debugging | Enable logging, use introspection |
;; | Long cache keys | Auto-handled with SHA-1 fallback |
;; | Serialization | Avoid stateful objects, force lazy seqs |
;; | Configuration | Use `pocket.edn` — see [Configuration](pocket_book.configuration.html) |

(pocket/cleanup!)
