(ns
 pocket-book.extending-pocket-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tech.v3.dataset.modelling :as ds-mod]
  [clojure.test :refer [deftest is]]))


(def v3_l17 (def cache-dir "/tmp/pocket-extending"))


(def v4_l19 (pocket/set-base-cache-dir! cache-dir))


(def v5_l21 (pocket/cleanup!))


(def v7_l29 (kind/doc #'pocket/->id))


(def v9_l37 (pocket/->id #'clojure.core/map))


(deftest t10_l39 (is (= v9_l37 'clojure.core/map)))


(def v12_l43 (pocket/->id {:b 2, :a 1}))


(def v14_l47 (defn add [x y] (+ x y)))


(def v15_l49 (pocket/->id (pocket/cached #'add 1 2)))


(deftest t16_l51 (is ((fn [id] (= (rest id) '(1 2))) v15_l49)))


(def v18_l55 (pocket/->id nil))


(deftest t19_l57 (is (nil? v18_l55)))


(def
 v21_l71
 (def
  example-ds
  (->
   (tc/dataset {:x (range 30), :y (range 30)})
   (ds-mod/set-inference-target :y))))


(def v22_l75 (pocket/->id example-ds))


(def v24_l81 (def ds-a (tc/dataset {:x (range 30), :y (range 30)})))


(def v25_l82 (def ds-b (tc/dataset {:x (range 30), :y (range 30)})))


(def v26_l84 (= (pocket/->id ds-a) (pocket/->id ds-b)))


(deftest t27_l86 (is (true? v26_l84)))


(def
 v29_l91
 (def
  ds-c
  (tc/dataset
   {:x (range 30), :y (concat (range 15) [999] (range 16 30))})))


(def v30_l94 (= (pocket/->id ds-a) (pocket/->id ds-c)))


(deftest t31_l96 (is (false? v30_l94)))


(def v33_l111 (defrecord DatasetRef [source version]))


(def
 v35_l120
 (extend-protocol
  pocket/PIdentifiable
  DatasetRef
  (->id [this] (symbol (str (:source this) "-v" (:version this))))))


(def v37_l127 (pocket/->id (->DatasetRef "census" 3)))


(deftest t38_l129 (is (= v37_l127 'census-v3)))


(def
 v40_l133
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
 v42_l145
 (def
  analysis
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 3)
   {:method :regression})))


(def v43_l150 (pocket/->id analysis))


(def v45_l154 (deref analysis))


(deftest
 t46_l156
 (is
  ((fn
    [result]
    (and
     (= "census" (:source result))
     (= 3 (:version result))
     (= :regression (:method result))))
   v45_l154)))


(def v48_l162 (deref analysis))


(def
 v50_l166
 (deref
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 4)
   {:method :regression})))


(def v52_l174 (pocket/dir-tree))


(def v54_l248 (pocket/cleanup!))
