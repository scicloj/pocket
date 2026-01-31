;; # Pocket - Filesystem-based Caching

;; Pocket provides content-addressable caching with automatic serialization,
;; making it easy to cache expensive function calls to disk and reuse results
;; across sessions.

^{:kindly/options {:kinds-that-hide-code #{:kind/doc}}}
(ns index
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]
            [babashka.fs :as fs]))

;; ## Quick Start

;; Let's demonstrate Pocket's core functionality with a simple example.

;; First, we'll set up a cache directory:

(def cache-dir "/tmp/pocket-demo")

(pocket/set-base-cache-dir! cache-dir)

;; Define an expensive computation that we want to cache:

(defn expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 1000)
  (+ x y))

;; ## Basic Caching

;; Create a cached computation. This returns an `IDeref` object - the
;; computation won't run until we deref it:

(def cached-result
  (pocket/cached #'expensive-calculation 10 20))

;;; First call (computes and caches):
(time @cached-result)

;;; Second call (from cache, instant!):
(time @cached-result)

;; ## Using cached-fn

;; For convenience, you can wrap a function to automatically cache all calls:

(def cached-expensive
  (pocket/cached-fn #'expensive-calculation))

;; Use it like a normal function, but it returns a cached IDeref:

;;; Using cached-fn:
(time @(cached-expensive 5 15))

;;; Second call with same args (cached):
(time @(cached-expensive 5 15))

;;; Different args (new computation):
(time @(cached-expensive 7 8))

;; ## Recursive Caching in Pipelines

;; When you pass a `Cached` value as an argument to another cached function,
;; Pocket handles this recursively. The cache key for the outer computation
;; is derived from the **identity** of the inner computation (its function
;; name and arguments), not from its result. This means the entire pipeline's
;; cache key captures the full computation graph.

;; For this to work, functions in the pipeline should call `maybe-deref`
;; on arguments that may be `Cached` objects. This way, the function receives
;; the actual value when executed, while the cache key still reflects the
;; upstream computation identity.

(defn load-dataset [path]
  (println "Loading dataset from" path "...")
  (Thread/sleep 800)
  {:data [1 2 3 4 5] :source path})

(defn preprocess [data opts]
  (let [data (pocket/maybe-deref data)]
    (println "Preprocessing with options:" opts)
    (Thread/sleep 600)
    (update data :data #(map (fn [x] (* x (:scale opts))) %))))

(defn train-model [data params]
  (let [data (pocket/maybe-deref data)]
    (println "Training model with params:" params)
    (Thread/sleep 1000)
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

;; Run the same pipeline again - everything loads from cache:

;;; Second pipeline run (all cached):
(time
 (-> "data/raw.csv"
     ((pocket/cached-fn #'load-dataset))
     ((pocket/cached-fn #'preprocess) {:scale 2})
     ((pocket/cached-fn #'train-model) {:epochs 100})
     deref
     (select-keys [:model :accuracy])))

;; Note that each step caches independently. If you change only the last step
;; (e.g., different training params), the upstream steps load from cache while
;; only the final step recomputes.

;; ## Nil Handling

;; Pocket properly handles nil values. Since the cache uses files on disk,
;; it needs to distinguish "never computed" from "computed and got nil".
;; It does this with a special marker file rather than Nippy serialization:

(defn returns-nil [] nil)

(def nil-result (pocket/cached #'returns-nil))

;;; Cached nil value:
@nil-result

;;; Loading from cache:
@nil-result

;; ## Important Notes

;; ### Use Vars for Functions
;;
;; Always use `#'function-name` (var), not `function-name` (function object).
;; Vars have stable names that produce consistent cache keys across sessions.
;; Function objects have unstable identity and would create a new cache entry
;; every time the code is reloaded.
;;
;; ```clojure
;; ;; ✅ Good - stable cache key from var name
;; (pocket/cached #'my-function args)
;;
;; ;; ❌ Bad - unstable identity, defeats caching
;; (pocket/cached my-function args)
;; ```

;; ### Cache Invalidation
;;
;; Pocket does **not** detect function implementation changes. If you modify
;; a function's body, the cache key remains the same (it's based on the
;; function name and arguments, not the implementation). You must manually
;; delete the cache directory to invalidate stale entries.

;; ## Configuration

;; Set cache directory via environment variable:
;;
;; ```bash
;; export POCKET_BASE_CACHE_DIR=/path/to/cache
;; clojure -M:dev
;; ```

;; Or set it once at the top of your script:
;;
;; ```clojure
;; (pocket/set-base-cache-dir! "/tmp/cache")
;; ```

;; ## API Reference

(kind/doc #'pocket/*base-cache-dir*)

(kind/doc #'pocket/set-base-cache-dir!)

;; Set to a custom directory:
;;
;; ```clojure
;; (pocket/set-base-cache-dir! "/tmp/my-cache")
;; ```

(kind/doc #'pocket/cached)

;; Returns a `Cached` object implementing `IDeref`. The computation runs on
;; first deref and is loaded from cache on subsequent derefs:

;;; Example:
@(pocket/cached #'expensive-calculation 1 2)

(kind/doc #'pocket/cached-fn)

;; Returns a wrapped function whose calls return `Cached` objects:

;;; Example:
(def my-cached-fn (pocket/cached-fn #'expensive-calculation))
@(my-cached-fn 3 4)

(kind/doc #'pocket/maybe-deref)

;; Useful in pipeline functions that may receive either cached or plain values:

;;; Example:
(pocket/maybe-deref 42)

(pocket/maybe-deref (pocket/cached #'expensive-calculation 1 2))

(kind/doc #'pocket/->id)

;; The `PIdentifiable` protocol allows customizing how values contribute to
;; cache keys. Default implementations exist for Var, MapEntry, Object, and nil:

;;; Example:
(pocket/->id #'expensive-calculation)

(pocket/->id {:a 1})

;; ## Cleanup

;; Clean up the demo cache:

(fs/delete-tree cache-dir)
