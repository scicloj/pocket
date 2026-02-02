;; # Recursive Caching in Pipelines

(ns pocket-book.recursive-caching-in-pipelines
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; ## Setup

;; When you pass a `Cached` value as an argument to another cached function,
;; Pocket handles this recursively. The cache key for the outer computation
;; is derived from the **identity** of the inner computation (its function
;; name and arguments), not from its result. This means the entire pipeline's
;; cache key captures the full [computation graph](https://en.wikipedia.org/wiki/Dataflow_programming).

;; Pocket automatically derefs any `Cached` arguments before calling the
;; function, so pipeline functions receive plain values and don't need
;; any special handling.

^:kindly/hide-code
(kind/mermaid
 "flowchart LR
    LD[load-dataset] --> PP[preprocess]
    PP --> TM[train-model]")

(def cache-dir "/tmp/pocket-demo-pipelines")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ## Pipeline functions
(defn load-dataset [path]
  (println "Loading dataset from" path "...")
  (Thread/sleep 300)
  {:data [1 2 3 4 5] :source path})

(defn preprocess [data opts]
  (println "Preprocessing with options:" opts)
  (Thread/sleep 300)
  (update data :data #(map (fn [x] (* x (:scale opts))) %)))

(defn train-model [data params]
  (println "Training model with params:" params)
  (Thread/sleep 300)
  {:model :trained :accuracy 0.95 :data data})

;; Wrap each function with `caching-fn` so every call returns a `Cached` object:

(def load-dataset* (pocket/caching-fn #'load-dataset))
(def preprocess* (pocket/caching-fn #'preprocess))
(def train-model* (pocket/caching-fn #'train-model))

;; ## Running the pipeline

;; Chain cached computations in a pipeline:

;;; First pipeline run:
(time
 (-> "data/raw.csv"
     (load-dataset*)
     (preprocess* {:scale 2})
     (train-model* {:epochs 100})
     deref
     (select-keys [:model :accuracy])))

;; Run the same pipeline again — everything loads from cache:

;;; Second pipeline run (all cached):
(time
 (-> "data/raw.csv"
     (load-dataset*)
     (preprocess* {:scale 2})
     (train-model* {:epochs 100})
     deref
     (select-keys [:model :accuracy])))

;; Each step caches independently. If you change only the last step
;; (e.g., different training params), the upstream steps load from cache while
;; only the final step recomputes.

;; ## Cleanup

(pocket/cleanup!)
