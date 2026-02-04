(ns pocket-book.usage-practices
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; # Usage Practices

;; This chapter covers recommended patterns and practices for
;; working effectively with Pocket during development.

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

(def test-dir "/tmp/pocket-dev-practices")
(pocket/set-base-cache-dir! test-dir)

(defn example-fn [x] (* x x))

(try
  (pocket/cached example-fn 5)  ; bare function
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

;; Function implementation changed — invalidate:
(pocket/invalidate! #'transform 10)

;; ### Strategy 2: Versioning Pattern

;; Add a version key to your function's input. Bumping the version
;; creates new cache entries while preserving old ones for comparison:

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

(kind/test-last [= {:result 25 :calls 1}])  ; still 1, no recomputation

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
(println (pocket/dir-tree))

;; ### Checking Cached Status

;; `Cached` values print their status without forcing computation:

(pocket/cleanup!)

(def pending-value (pocket/cached #'transform 99))

(pr-str pending-value)  ; shows :pending

(kind/test-last [#(re-find #":pending" %)])

(deref pending-value)

(pr-str pending-value)  ; now shows :cached

(kind/test-last [#(re-find #":cached" %)])

;; ## Debugging with Logging

;; Enable debug logging to see cache hits, misses, and writes.
;; See the [Logging chapter](pocket_book.logging.html) for setup.

;; With debug logging enabled, you'll see:
;; - `Cache miss, computing: ...` — computation triggered
;; - `Cache hit (memory): ...` — served from in-memory cache
;; - `Cache hit (disk): ...` — loaded from disk
;; - `Cache write: ...` — written to disk

;; ## Serialization Constraints

;; Pocket uses [Nippy](https://github.com/ptaoussanis/nippy) for
;; serialization. Most Clojure data structures work, but some don't:

;; ### ✅ Safe to Cache
;; - Primitive types (numbers, strings, keywords, symbols)
;; - Collections (vectors, maps, sets, lists)
;; - Records and most deftypes
;; - Java Serializable objects

;; ### ❌ Cannot Cache
;; - Open file handles, streams
;; - Network connections, sockets
;; - Functions, closures (use vars instead)
;; - Atoms, refs, agents (stateful references)
;; - Lazy sequences (force them first with `doall`)

;; ### Lazy Sequence Gotcha

;; Lazy sequences may cause issues. Force them before caching:

(defn generate-data [n]
  (doall (range n)))  ; doall forces evaluation

(deref (pocket/cached #'generate-data 5))

(kind/test-last [= [0 1 2 3 4]])

;; ## When to Use Pocket vs Memoize

;; | Use Case | Solution |
;; |----------|----------|
;; | Fast computations (< 100ms) | `clojure.core/memoize` |
;; | Single-session memory cache | `clojure.core/memoize` or `core.memoize` |
;; | Expensive computations (seconds+) | **Pocket** |
;; | Cross-session persistence | **Pocket** |
;; | Data science pipelines | **Pocket** |
;; | Concurrent access with single computation | **Pocket** |

;; Rule of thumb: if the computation takes longer than disk I/O,
;; Pocket is beneficial.

;; ## Project Configuration

;; For team projects, use `pocket.edn` at the classpath root:

;; ```edn
;; {:base-cache-dir ".cache/my-project"
;;  :mem-cache {:policy :lru :threshold 256}}
;; ```

;; This ensures consistent configuration without code changes,
;; and works correctly in pooled thread environments where
;; `binding` doesn't propagate.

;; ## Summary

;; | Practice | Recommendation |
;; |----------|----------------|
;; | Function identity | Always use vars (`#'fn-name`) |
;; | Invalidation | Manual or versioning pattern |
;; | Testing | Use `binding` + cleanup fixtures |
;; | Debugging | Enable logging, use introspection |
;; | Serialization | Avoid stateful objects, force lazy seqs |
;; | Configuration | Use `pocket.edn` for projects |

(pocket/cleanup!)
