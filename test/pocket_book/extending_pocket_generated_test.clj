(ns
 pocket-book.extending-pocket-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tech.v3.dataset.modelling :as ds-mod]
  [taoensso.nippy :as nippy]
  [clojure.test :refer [deftest is]]))


(def v3_l21 (def cache-dir "/tmp/pocket-extending"))


(def v4_l23 (pocket/set-base-cache-dir! cache-dir))


(def v5_l25 (pocket/cleanup!))


(def v7_l33 (kind/doc #'pocket/->id))


(def v9_l41 (pocket/->id #'clojure.core/map))


(deftest t10_l43 (is (= v9_l41 'clojure.core/map)))


(def v12_l47 (pocket/->id {:b 2, :a 1}))


(def v14_l51 (defn add [x y] (+ x y)))


(def v15_l53 (pocket/->id (pocket/cached #'add 1 2)))


(deftest t16_l55 (is ((fn [id] (= (rest id) '(1 2))) v15_l53)))


(def v18_l61 (defn make-pair [a b] {:a a, :b b}))


(def
 v19_l63
 (let
  [c (pocket/cached #'make-pair 1 2)]
  (= (pocket/->id (deref c)) (pocket/->id c))))


(deftest t20_l66 (is (true? v19_l63)))


(def v22_l70 (pocket/->id nil))


(deftest t23_l72 (is (nil? v22_l70)))


(def
 v25_l85
 (def
  example-ds
  (->
   (tc/dataset {:x (range 30), :y (range 30)})
   (ds-mod/set-inference-target :y))))


(def v26_l89 (pocket/->id example-ds))


(def v28_l95 (def ds-a (tc/dataset {:x (range 30), :y (range 30)})))


(def v29_l96 (def ds-b (tc/dataset {:x (range 30), :y (range 30)})))


(def v30_l98 (= (pocket/->id ds-a) (pocket/->id ds-b)))


(deftest t31_l100 (is (true? v30_l98)))


(def
 v33_l105
 (def
  ds-c
  (tc/dataset
   {:x (range 30), :y (concat (range 15) [999] (range 16 30))})))


(def v34_l108 (= (pocket/->id ds-a) (pocket/->id ds-c)))


(deftest t35_l110 (is (false? v34_l108)))


(def v37_l125 (defrecord DatasetRef [source version]))


(def
 v39_l134
 (extend-protocol
  pocket/PIdentifiable
  DatasetRef
  (->id [this] (symbol (str (:source this) "-v" (:version this))))))


(def v41_l141 (pocket/->id (->DatasetRef "census" 3)))


(deftest t42_l143 (is (= v41_l141 'census-v3)))


(def
 v44_l147
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
 v46_l159
 (def
  analysis
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 3)
   {:method :regression})))


(def v47_l164 (pocket/->id analysis))


(def v49_l168 (deref analysis))


(deftest
 t50_l170
 (is
  ((fn
    [result]
    (and
     (= "census" (:source result))
     (= 3 (:version result))
     (= :regression (:method result))))
   v49_l168)))


(def v52_l176 (deref analysis))


(def
 v54_l180
 (deref
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 4)
   {:method :regression})))


(def v56_l188 (pocket/dir-tree))


(def v58_l248 (defrecord MyModel [weights bias]))


(def
 v59_l250
 (nippy/extend-freeze
  MyModel
  :my-model
  [x data-output]
  (nippy/freeze-to-out! data-output (:weights x))
  (nippy/freeze-to-out! data-output (:bias x))))


(def
 v60_l255
 (do
  (nippy/extend-thaw
   :my-model
   [data-input]
   (->MyModel
    (nippy/thaw-from-in! data-input)
    (nippy/thaw-from-in! data-input)))
  :done))


(def v62_l263 (def original (->MyModel [0.5 -0.3 1.2] 0.1)))


(def v63_l265 (= original (nippy/thaw (nippy/freeze original))))


(deftest t64_l267 (is (true? v63_l265)))


(def
 v66_l271
 (defn
  train-my-model
  [data]
  (->MyModel (mapv (fn* [p1__21232#] (* p1__21232# 0.01)) data) 0.42)))


(def
 v67_l274
 (let
  [result (deref (pocket/cached #'train-my-model [10 20 30]))]
  result))


(deftest
 t68_l277
 (is
  ((fn [m] (and (instance? MyModel m) (= 0.42 (:bias m)))) v67_l274)))


(def v70_l285 (pocket/cleanup!))
