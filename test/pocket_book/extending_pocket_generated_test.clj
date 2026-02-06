(ns
 pocket-book.extending-pocket-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v3_l14 (def cache-dir "/tmp/pocket-extending"))


(def v4_l16 (pocket/set-base-cache-dir! cache-dir))


(def v5_l18 (pocket/cleanup!))


(def v7_l26 (kind/doc #'pocket/->id))


(def v9_l34 (pocket/->id #'clojure.core/map))


(deftest t10_l36 (is (= v9_l34 'clojure.core/map)))


(def v12_l40 (pocket/->id {:b 2, :a 1}))


(def v14_l44 (defn add [x y] (+ x y)))


(def v15_l46 (pocket/->id (pocket/cached #'add 1 2)))


(deftest t16_l48 (is ((fn [id] (= (rest id) '(1 2))) v15_l46)))


(def v18_l52 (pocket/->id nil))


(deftest t19_l54 (is (nil? v18_l52)))


(def v21_l66 (defrecord DatasetRef [source version]))


(def
 v23_l75
 (extend-protocol
  pocket/PIdentifiable
  DatasetRef
  (->id [this] (symbol (str (:source this) "-v" (:version this))))))


(def v25_l82 (pocket/->id (->DatasetRef "census" 3)))


(deftest t26_l84 (is (= v25_l82 'census-v3)))


(def
 v28_l88
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
 v30_l100
 (def
  analysis
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 3)
   {:method :regression})))


(def v31_l105 (pocket/->id analysis))


(def v33_l109 (deref analysis))


(deftest
 t34_l111
 (is
  ((fn
    [result]
    (and
     (= "census" (:source result))
     (= 3 (:version result))
     (= :regression (:method result))))
   v33_l109)))


(def v36_l117 (deref analysis))


(def
 v38_l121
 (deref
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 4)
   {:method :regression})))


(def v40_l129 (kind/code (pocket/dir-tree)))


(def v42_l203 (pocket/cleanup!))
