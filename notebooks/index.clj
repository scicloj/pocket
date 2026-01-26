;; # Pocket - Filesystem-based Caching

;; Pocket provides content-addressable caching with automatic serialization,
;; making it easy to cache expensive function calls to disk and reuse results
;; across sessions.

(ns index
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; ## Quick Start

;; Let's demonstrate Pocket's core functionality with a simple example.

;; First, we'll set up a cache directory:

(def cache-dir "/tmp/pocket-demo")

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

(binding [pocket/*base-cache-dir* cache-dir]
  (def cached-result
    (pocket/cached #'expensive-calculation 10 20)))

;; Now let's deref it. The first time, it will compute and cache:

(binding [pocket/*base-cache-dir* cache-dir]
  (println "First call:")
  (time @cached-result))

;; The second deref loads from cache (instant!):

(binding [pocket/*base-cache-dir* cache-dir]
  (println "\nSecond call (from cache):")
  (time @cached-result))

;; ## Using cached-fn

;; For convenience, you can wrap a function to automatically cache all calls:

(binding [pocket/*base-cache-dir* cache-dir]
  (def cached-expensive 
    (pocket/cached-fn #'expensive-calculation)))

;; Use it like a normal function, but it returns a cached IDeref:

(binding [pocket/*base-cache-dir* cache-dir]
  (println "\nUsing cached-fn:")
  (time @(cached-expensive 5 15))
  
  (println "\nSecond call with same args (cached):")
  (time @(cached-expensive 5 15))
  
  (println "\nDifferent args (new computation):")
  (time @(cached-expensive 7 8)))

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

(binding [pocket/*base-cache-dir* cache-dir]
  (println "\n=== First pipeline run ===")
  (time
    (-> "data/raw.csv"
        ((pocket/cached-fn #'load-dataset))
        ((pocket/cached-fn #'preprocess) {:scale 2})
        ((pocket/cached-fn #'train-model) {:epochs 100})
        deref
        (select-keys [:model :accuracy]))))

;; Run the same pipeline again - everything loads from cache:

(binding [pocket/*base-cache-dir* cache-dir]
  (println "\n=== Second pipeline run (all cached) ===")
  (time
    (-> "data/raw.csv"
        ((pocket/cached-fn #'load-dataset))
        ((pocket/cached-fn #'preprocess) {:scale 2})
        ((pocket/cached-fn #'train-model) {:epochs 100})
        deref
        (select-keys [:model :accuracy]))))

;; ## Nil Handling

;; Pocket properly handles nil values:

(defn returns-nil [] nil)

(binding [pocket/*base-cache-dir* cache-dir]
  (def nil-result (pocket/cached #'returns-nil))
  (println "\nCached nil value:" @nil-result)
  (println "Loading from cache:" @nil-result))

;; ## Cache Inspection

;; You can inspect the cache directory to see how Pocket organizes cached values:

^kind/hiccup
[:div
 [:h3 "Cache Directory Structure"]
 [:pre
  "$POCKET_BASE_CACHE_DIR/.cache/\n"
  "  <sha1-prefix>/\n"
  "    <function-name-args>/\n"
  "      _.nippy    # serialized value\n"
  "      nil        # marker for cached nil"]]

;; ## Configuration

;; Set cache directory via environment variable:

^kind/code
{:language "bash"
 :content "export POCKET_BASE_CACHE_DIR=/path/to/cache\nclojure -M:dev"}

;; Or bind dynamically (as shown in examples above):

^kind/code
{:language "clojure"
 :content "(binding [pocket/*base-cache-dir* \"/tmp/cache\"]\n  @(pocket/cached #'my-fn args))"}

;; ## Key Features

^kind/hiccup
[:ul
 [:li "Content-addressable storage - cache keys from function + arguments"]
 [:li "Automatic serialization via Nippy"]
 [:li "Lazy evaluation with IDeref"]
 [:li "Nil-safe caching"]
 [:li "Extensible via PIdentifiable protocol"]
 [:li "Simple API - just " [:code "cached"] " and " [:code "cached-fn"]]]

;; ## Important Notes

^kind/hiccup
[:div
 [:h4 "⚠️ Use Vars for Functions"]
 [:p "Always use " [:code "#'function-name"] " (var), not " [:code "function-name"] " (function object):"]
 [:pre
  ";; ✅ Good\n"
  "(pocket/cached #'my-function args)\n\n"
  ";; ❌ Bad - unstable cache keys\n"
  "(pocket/cached my-function args)"]]

;; ## Cleanup

;; Clean up the demo cache:

(require '[babashka.fs :as fs])
(fs/delete-tree cache-dir)
(println "\nDemo cache cleaned up!")
