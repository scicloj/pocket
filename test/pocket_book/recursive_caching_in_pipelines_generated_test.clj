(ns
 pocket-book.recursive-caching-in-pipelines-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def
 v3_l20
 (kind/mermaid
  "flowchart LR\n    LD[load-dataset] --> PP[preprocess]\n    PP --> TM[train-model]"))


(def v4_l26 (def cache-dir "/tmp/pocket-demo-pipelines"))


(def v5_l28 (pocket/set-base-cache-dir! cache-dir))


(def v6_l30 (pocket/cleanup!))


(def
 v8_l33
 (defn
  load-dataset
  [path]
  (println "Loading dataset from" path "...")
  (Thread/sleep 300)
  {:data [1 2 3 4 5], :source path}))


(def
 v9_l38
 (defn
  preprocess
  [data opts]
  (println "Preprocessing with options:" opts)
  (Thread/sleep 300)
  (update
   data
   :data
   (fn* [p1__29707#] (map (fn [x] (* x (:scale opts))) p1__29707#)))))


(def
 v10_l43
 (defn
  train-model
  [data params]
  (println "Training model with params:" params)
  (Thread/sleep 300)
  {:model :trained, :accuracy 0.95, :data data}))


(def v12_l50 (def load-dataset* (pocket/caching-fn #'load-dataset)))


(def v13_l51 (def preprocess* (pocket/caching-fn #'preprocess)))


(def v14_l52 (def train-model* (pocket/caching-fn #'train-model)))


(def
 v16_l59
 (time
  (->
   "data/raw.csv"
   (load-dataset*)
   (preprocess* {:scale 2})
   (train-model* {:epochs 100})
   deref
   (select-keys [:model :accuracy]))))


(deftest t17_l67 (is (= v16_l59 {:model :trained, :accuracy 0.95})))


(def
 v19_l72
 (time
  (->
   "data/raw.csv"
   (load-dataset*)
   (preprocess* {:scale 2})
   (train-model* {:epochs 100})
   deref
   (select-keys [:model :accuracy]))))


(deftest t20_l80 (is (= v19_l72 {:model :trained, :accuracy 0.95})))


(def v22_l90 (pocket/cleanup!))
