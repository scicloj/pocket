;; # Pocket - Filesystem-based Caching

;; Pocket provides content-addressable caching with automatic serialization,
;; making it easy to cache expensive function calls to disk and reuse results
;; across sessions.

^{:kindly/options {:kinds-that-hide-code #{:kind/doc}}}
(ns index
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]
            [babashka.fs :as fs]))

;; ## Basic Walkthrough

;; First, we set up a cache directory and define an expensive computation:

(def cache-dir "/tmp/pocket-demo")

(pocket/set-base-cache-dir! cache-dir)

(defn expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 1000)
  (+ x y))

;; `cached` creates a lazy cached computation. It returns a `Cached` object —
;; the computation won't run until we deref it:

(def cached-result
  (pocket/cached #'expensive-calculation 10 20))

cached-result

;;; First deref (computes and caches):
(time @cached-result)

;;; Second deref (loaded from cache, instant):
(time @cached-result)

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
@nil-result

;;; Loading nil from cache:
@nil-result

;; ### Usage notes

;; **Use vars for functions.**
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

;; **Cache invalidation.**
;; Pocket does **not** detect function implementation changes. If you modify
;; a function's body, the cache key remains the same (it's based on the
;; function name and arguments, not the implementation). You must manually
;; delete the cache directory to invalidate stale entries.

;; **Configuration.**
;; Set the cache directory via the `POCKET_BASE_CACHE_DIR` environment variable:
;;
;; ```bash
;; export POCKET_BASE_CACHE_DIR=/path/to/cache
;; ```
;;
;; Or programmatically with `set-base-cache-dir!`.

;; ## API Reference

;; ### Configuration

(kind/doc #'pocket/*base-cache-dir*)

;; The current value:

pocket/*base-cache-dir*

(kind/doc #'pocket/set-base-cache-dir!)

(pocket/set-base-cache-dir! "/tmp/pocket-demo-2")
pocket/*base-cache-dir*

;; Restore it for the rest of the notebook:

(pocket/set-base-cache-dir! cache-dir)

;; ### Caching

(kind/doc #'pocket/cached)

;; `cached` returns a `Cached` object — the computation is not yet executed:

(def my-result (pocket/cached #'expensive-calculation 100 200))
my-result

;; The computation runs when we deref:

@my-result

;; Derefing again loads from cache (no recomputation):

@my-result

(kind/doc #'pocket/cached-fn)

;; `cached-fn` wraps a function so that every call returns a `Cached` object:

(def my-cached-fn (pocket/cached-fn #'expensive-calculation))

@(my-cached-fn 3 4)

;; Same args hit the cache:

@(my-cached-fn 3 4)

(kind/doc #'pocket/maybe-deref)

;; A plain value passes through unchanged:

(pocket/maybe-deref 42)

;; A `Cached` value gets derefed:

(pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200))

;; ### Cache key identity

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

;; ## Cleanup

;; Clean up the demo cache:

(fs/delete-tree cache-dir)
