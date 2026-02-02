(ns
 pocket-book.extending-pocket-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v3_l10 (def cache-dir "/tmp/pocket-extending"))


(def v4_l12 (pocket/set-base-cache-dir! cache-dir))


(def v5_l14 (pocket/cleanup!))


(def v7_l22 (kind/doc #'pocket/->id))


(def v9_l30 (pocket/->id #'clojure.core/map))


(deftest t10_l32 (is (= v9_l30 'clojure.core/map)))


(def v12_l36 (pocket/->id {:b 2, :a 1}))


(def v14_l40 (defn add [x y] (+ x y)))


(def v15_l42 (pocket/->id (pocket/cached #'add 1 2)))


(deftest t16_l44 (is ((fn [id] (= (rest id) '(1 2))) v15_l42)))


(def v18_l48 (pocket/->id nil))


(deftest t19_l50 (is (nil? v18_l48)))


(def v21_l62 (defrecord DatasetRef [source version]))


(def
 v23_l70
 (extend-protocol
  pocket/PIdentifiable
  DatasetRef
  (->id [this] (symbol (str (:source this) "-v" (:version this))))))


(def v25_l77 (pocket/->id (->DatasetRef "census" 3)))


(deftest t26_l79 (is (= v25_l77 'census-v3)))


(def
 v28_l83
 (defn
  analyze-dataset
  "Simulate analyzing a dataset."
  [dataset-ref opts]
  (println
   "  Analyzing"
   (:source dataset-ref)
   "v"
   (:version dataset-ref)
   "...")
  (Thread/sleep 200)
  {:source (:source dataset-ref),
   :version (:version dataset-ref),
   :rows 1000,
   :method (:method opts)}))


(def
 v30_l95
 (def
  analysis
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 3)
   {:method :regression})))


(def v31_l100 (pocket/->id analysis))


(def v33_l104 (deref analysis))


(deftest
 t34_l106
 (is
  ((fn
    [result]
    (and
     (= "census" (:source result))
     (= 3 (:version result))
     (= :regression (:method result))))
   v33_l104)))


(def v36_l112 (deref analysis))


(def
 v38_l116
 (deref
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 4)
   {:method :regression})))


(def v40_l124 (kind/code (pocket/dir-tree)))


(def v42_l153 (pocket/cleanup!))
