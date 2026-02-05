;; # Recursive Caching in Pipelines

;; When you pass a `Cached` value as an argument to another cached function,
;; Pocket handles this recursively. The cache key for the outer computation
;; is derived from the **identity** of the inner computation (its function
;; name and arguments), not from its result. This means the entire pipeline's
;; cache key captures the full [computation graph](https://en.wikipedia.org/wiki/Dataflow_programming).
;;
;; Pocket automatically derefs any `Cached` arguments before calling the
;; function, so pipeline functions receive plain values and don't need
;; any special handling.

;; ## Setup

(ns pocket-book.recursive-caching-in-pipelines
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]
            [clojure.string :as str]))

(def cache-dir "/tmp/pocket-demo-pipelines")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ## A three-step pipeline

;; We'll build a simple data science pipeline with three stages:
;; load data, preprocess it, and train a model. Each stage is
;; wrapped with `caching-fn` so every call returns a `Cached` object.
;; Passing one `Cached` result into the next stage is what makes
;; the caching recursive.

^:kindly/hide-code
(kind/mermaid
 "flowchart LR
    LD[load-dataset] --> PP[preprocess]
    PP --> TM[train-model]")

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

(kind/test-last [= {:model :trained, :accuracy 0.95}])

;; Run the same pipeline again — everything loads from cache:

;;; Second pipeline run (all cached):
(time
 (-> "data/raw.csv"
     (load-dataset*)
     (preprocess* {:scale 2})
     (train-model* {:epochs 100})
     deref
     (select-keys [:model :accuracy])))

(kind/test-last [= {:model :trained, :accuracy 0.95}])

;; No log output above — the result was served entirely from the
;; in-memory cache, so no disk I/O or computation occurred.
;; Each step caches independently. If you change only the last step
;; (e.g., different training params), the upstream steps load from cache while
;; only the final step recomputes.

;; ## Provenance in cache entries

;; The cache entries reveal the pipeline structure. Each entry's
;; identity encodes its full computation history — not just the
;; function name, but the nested identities of all its cached inputs.

(->> (pocket/cache-entries)
     (mapv :id))

(->> (pocket/cache-entries)
     (mapv :id)
     (str/join "\n")
     kind/code)

;; The inner step appears as a literal sub-expression in the outer
;; step's identity. This is how Pocket tracks provenance: the cache
;; key for `train-model` records that its input came from
;; `preprocess`, which in turn came from `load-dataset`.
;;
;; This happens automatically when you pass `Cached` objects (without
;; derefing) from one cached step to the next. If you deref early
;; with `@`, the downstream step sees a plain value and the
;; provenance link is lost — the cache key is based on the value's
;; hash instead. Both patterns work; the choice is whether you need
;; traceability.

;; For a fuller example with branching dependencies, see the
;; [Real-World Walkthrough](pocket_book.real_world_walkthrough.html).

;; ## Inspecting the DAG
;;
;; `origin-story` reconstructs the computation graph from a `Cached` value.
;; It walks the argument tree recursively, returning a nested map where
;; each cached step is `{:fn <var> :args [...]}` and plain arguments
;; are `{:value ...}` leaves. If a step has already been computed,
;; `:value` is included in the node.

;; Build the pipeline keeping the intermediate `Cached` objects:

(def data-c (load-dataset* "data/raw.csv"))
(def preprocessed-c (preprocess* data-c {:scale 2}))
(def model-c (train-model* preprocessed-c {:epochs 100}))

;; Inspect the DAG before forcing any computation:

(pocket/origin-story model-c)

;; No `:value` keys — nothing has been computed yet (in this
;; pipeline instance). Now deref to trigger computation:

@model-c

(pocket/origin-story model-c)

;; Every node now includes its `:value`.

;; `origin-story-mermaid` renders the same tree as a
;; [Mermaid](https://mermaid.js.org/) flowchart:

(kind/mermaid (pocket/origin-story-mermaid model-c))

;; ## Cleanup

(pocket/cleanup!)
