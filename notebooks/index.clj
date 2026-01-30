;; # Pocket - Filesystem-based Caching

;; Pocket provides content-addressable caching with automatic serialization,
;; making it easy to cache expensive function calls to disk and reuse results
;; across sessions.

(ns index
  (:require [scicloj.pocket :as pocket]
            [scicloj.pocket.impl.cache :as impl]
            [babashka.fs :as fs]))

;; ## Quick Start

;; Let's demonstrate Pocket's core functionality with a simple example.

;; First, we'll set up a cache directory:

(def cache-dir "/tmp/pocket-demo")

(alter-var-root #'impl/*base-cache-dir* (constantly cache-dir))

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

;; Now let's deref it. The first time, it will compute and cache:

(println "First call:")
(time @cached-result)

;; The second deref loads from cache (instant!):

(println "\nSecond call (from cache):")
(time @cached-result)

;; ## Using cached-fn

;; For convenience, you can wrap a function to automatically cache all calls:

(def cached-expensive 
  (pocket/cached-fn #'expensive-calculation))

;; Use it like a normal function, but it returns a cached IDeref:

(println "\nUsing cached-fn:")
(time @(cached-expensive 5 15))

(println "\nSecond call with same args (cached):")
(time @(cached-expensive 5 15))

(println "\nDifferent args (new computation):")
(time @(cached-expensive 7 8))

;; ## Data Science Pipeline Example

;; Pocket shines in data science workflows where intermediate steps are expensive:

(defn load-dataset [path]
  (println "Loading dataset from" path "...")
  (Thread/sleep 800)
  {:data [1 2 3 4 5] :source path})

(defn preprocess [data opts]
  (println "Preprocessing with options:" opts)
  (Thread/sleep 600)
  (update data :data #(map (fn [x] (* x (:scale opts))) %)))

(defn train-model [data params]
  (println "Training model with params:" params)
  (Thread/sleep 1000)
  {:model :trained :accuracy 0.95 :data data})

;; Chain cached computations in a pipeline:

(println "\n=== First pipeline run ===")
(time
  (-> "data/raw.csv"
      ((pocket/cached-fn #'load-dataset))
      ((pocket/cached-fn #'preprocess) {:scale 2})
      ((pocket/cached-fn #'train-model) {:epochs 100})
      deref
      (select-keys [:model :accuracy])))

;; Run the same pipeline again - everything loads from cache:

(println "\n=== Second pipeline run (all cached) ===")
(time
  (-> "data/raw.csv"
      ((pocket/cached-fn #'load-dataset))
      ((pocket/cached-fn #'preprocess) {:scale 2})
      ((pocket/cached-fn #'train-model) {:epochs 100})
      deref
      (select-keys [:model :accuracy])))

;; ## Nil Handling

;; Pocket properly handles nil values:

(defn returns-nil [] nil)

(def nil-result (pocket/cached #'returns-nil))
(println "\nCached nil value:" @nil-result)
(println "Loading from cache:" @nil-result)

;; ## Cache Inspection

;; You can inspect the cache directory to see how Pocket organizes cached values:
;;
;; ### Cache Directory Structure
;;
;; ```
;; $POCKET_BASE_CACHE_DIR/.cache/
;;   <sha1-prefix>/
;;     <function-name-args>/
;;       _.nippy    # serialized value
;;       nil        # marker for cached nil
;; ```

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
;; (alter-var-root #'impl/*base-cache-dir* (constantly "/tmp/cache"))
;; ```

;; ## Key Features

;; - Content-addressable storage - cache keys from function + arguments
;; - Automatic serialization via Nippy
;; - Lazy evaluation with IDeref
;; - Nil-safe caching
;; - Extensible via PIdentifiable protocol
;; - Simple API - just `cached` and `cached-fn`

;; ## Important Notes

;; ### ⚠️ Use Vars for Functions
;;
;; Always use `#'function-name` (var), not `function-name` (function object):
;;
;; ```clojure
;; ;; ✅ Good
;; (pocket/cached #'my-function args)
;;
;; ;; ❌ Bad - unstable cache keys
;; (pocket/cached my-function args)
;; ```

;; ## Cleanup

;; Clean up the demo cache:

(fs/delete-tree cache-dir)
(println "\nDemo cache cleaned up!")
