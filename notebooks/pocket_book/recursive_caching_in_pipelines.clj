;; # Recursive Caching in Pipelines

^{:kindly/options {:kinds-that-hide-code #{:kind/doc}}}
(ns pocket-book.recursive-caching-in-pipelines
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; When you pass a `Cached` value as an argument to another cached function,
;; Pocket handles this recursively. The cache key for the outer computation
;; is derived from the **identity** of the inner computation (its function
;; name and arguments), not from its result. This means the entire pipeline's
;; cache key captures the full [computation graph](https://en.wikipedia.org/wiki/Dataflow_programming).

;; For this to work, functions in the pipeline should call `maybe-deref`
;; on arguments that may be `Cached` objects. This way, the function receives
;; the actual value when executed, while the cache key still reflects the
;; upstream computation identity.

;; ## Setup

(def cache-dir "/tmp/pocket-demo-pipelines")

(pocket/set-base-cache-dir! cache-dir)

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

;; ## Running the pipeline

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

;; ## Cleanup

(pocket/cleanup!)
