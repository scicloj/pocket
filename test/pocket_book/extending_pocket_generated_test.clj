(ns
 pocket-book.extending-pocket-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tech.v3.dataset.modelling :as ds-mod]
  [clojure.test :refer [deftest is]]))


(def v3_l19 (def cache-dir "/tmp/pocket-extending"))


(def v4_l21 (pocket/set-base-cache-dir! cache-dir))


(def v5_l23 (pocket/cleanup!))


(def v7_l31 (kind/doc #'pocket/->id))


(def v9_l39 (pocket/->id #'clojure.core/map))


(deftest t10_l41 (is (= v9_l39 'clojure.core/map)))


(def v12_l45 (pocket/->id {:b 2, :a 1}))


(def v14_l49 (defn add [x y] (+ x y)))


(def v15_l51 (pocket/->id (pocket/cached #'add 1 2)))


(deftest t16_l53 (is ((fn [id] (= (rest id) '(1 2))) v15_l51)))


(def v18_l57 (pocket/->id nil))


(deftest t19_l59 (is (nil? v18_l57)))


(def
 v21_l73
 (def
  example-ds
  (->
   (tc/dataset {:x (range 30), :y (range 30)})
   (ds-mod/set-inference-target :y))))


(def v22_l77 (pocket/->id example-ds))


(def v24_l83 (def ds-a (tc/dataset {:x (range 30), :y (range 30)})))


(def v25_l84 (def ds-b (tc/dataset {:x (range 30), :y (range 30)})))


(def v26_l86 (= (pocket/->id ds-a) (pocket/->id ds-b)))


(deftest t27_l88 (is (true? v26_l86)))


(def
 v29_l93
 (def
  ds-c
  (tc/dataset
   {:x (range 30), :y (concat (range 15) [999] (range 16 30))})))


(def v30_l96 (= (pocket/->id ds-a) (pocket/->id ds-c)))


(deftest t31_l98 (is (false? v30_l96)))


(def v33_l113 (defrecord DatasetRef [source version]))


(def
 v35_l122
 (extend-protocol
  pocket/PIdentifiable
  DatasetRef
  (->id [this] (symbol (str (:source this) "-v" (:version this))))))


(def v37_l129 (pocket/->id (->DatasetRef "census" 3)))


(deftest t38_l131 (is (= v37_l129 'census-v3)))


(def
 v40_l135
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
 v42_l147
 (def
  analysis
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 3)
   {:method :regression})))


(def v43_l152 (pocket/->id analysis))


(def v45_l156 (deref analysis))


(deftest
 t46_l158
 (is
  ((fn
    [result]
    (and
     (= "census" (:source result))
     (= 3 (:version result))
     (= :regression (:method result))))
   v45_l156)))


(def v48_l164 (deref analysis))


(def
 v50_l168
 (deref
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 4)
   {:method :regression})))


(def v52_l176 (pocket/dir-tree))


(def v54_l250 (pocket/cleanup!))
