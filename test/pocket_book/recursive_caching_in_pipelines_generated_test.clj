(ns
 pocket-book.recursive-caching-in-pipelines-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.string :as str]
  [clojure.test :refer [deftest is]]))


(def v2_l21 (def cache-dir "/tmp/pocket-demo-pipelines"))


(def v3_l23 (pocket/set-base-cache-dir! cache-dir))


(def v4_l25 (pocket/cleanup!))


(def
 v6_l35
 (kind/mermaid
  "flowchart LR\n    LD[load-dataset] --> PP[preprocess]\n    PP --> TM[train-model]"))


(def
 v8_l42
 (defn
  load-dataset
  [path]
  (println "Loading dataset from" path "...")
  (Thread/sleep 300)
  {:data [1 2 3 4 5], :source path}))


(def
 v9_l47
 (defn
  preprocess
  [data opts]
  (println "Preprocessing with options:" opts)
  (Thread/sleep 300)
  (update
   data
   :data
   (fn* [p1__30006#] (map (fn [x] (* x (:scale opts))) p1__30006#)))))


(def
 v10_l52
 (defn
  train-model
  [data params]
  (println "Training model with params:" params)
  (Thread/sleep 300)
  {:model :trained, :accuracy 0.95, :data data}))


(def v12_l59 (def load-dataset* (pocket/caching-fn #'load-dataset)))


(def v13_l60 (def preprocess* (pocket/caching-fn #'preprocess)))


(def v14_l61 (def train-model* (pocket/caching-fn #'train-model)))


(def
 v16_l68
 (time
  (->
   "data/raw.csv"
   (load-dataset*)
   (preprocess* {:scale 2})
   (train-model* {:epochs 100})
   deref
   (select-keys [:model :accuracy]))))


(deftest t17_l76 (is (= v16_l68 {:model :trained, :accuracy 0.95})))


(def
 v19_l81
 (time
  (->
   "data/raw.csv"
   (load-dataset*)
   (preprocess* {:scale 2})
   (train-model* {:epochs 100})
   deref
   (select-keys [:model :accuracy]))))


(deftest t20_l89 (is (= v19_l81 {:model :trained, :accuracy 0.95})))


(def v22_l103 (->> (pocket/cache-entries) (mapv :id)))


(def
 v23_l106
 (->> (pocket/cache-entries) (mapv :id) (str/join "\n") kind/code))


(def v25_l136 (def data-c (load-dataset* "data/experiment.csv")))


(def v26_l137 (def preprocessed-c (preprocess* data-c {:scale 2})))


(def v27_l138 (def model-c (train-model* preprocessed-c {:epochs 100})))


(def v29_l148 (pocket/origin-story model-c))


(def v31_l156 (pocket/origin-story model-c))


(def v33_l166 (pocket/origin-story-graph model-c))


(def v35_l173 (kind/mermaid (pocket/origin-story-mermaid model-c)))


(def v37_l177 (pocket/cleanup!))
