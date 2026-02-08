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


(def v18_l59 (pocket/->id nil))


(deftest t19_l61 (is (nil? v18_l59)))


(def
 v21_l74
 (def
  example-ds
  (->
   (tc/dataset {:x (range 30), :y (range 30)})
   (ds-mod/set-inference-target :y))))


(def v22_l78 (pocket/->id example-ds))


(def v24_l84 (def ds-a (tc/dataset {:x (range 30), :y (range 30)})))


(def v25_l85 (def ds-b (tc/dataset {:x (range 30), :y (range 30)})))


(def v26_l87 (= (pocket/->id ds-a) (pocket/->id ds-b)))


(deftest t27_l89 (is (true? v26_l87)))


(def
 v29_l94
 (def
  ds-c
  (tc/dataset
   {:x (range 30), :y (concat (range 15) [999] (range 16 30))})))


(def v30_l97 (= (pocket/->id ds-a) (pocket/->id ds-c)))


(deftest t31_l99 (is (false? v30_l97)))


(def v33_l114 (defrecord DatasetRef [source version]))


(def
 v35_l123
 (extend-protocol
  pocket/PIdentifiable
  DatasetRef
  (->id [this] (symbol (str (:source this) "-v" (:version this))))))


(def v37_l130 (pocket/->id (->DatasetRef "census" 3)))


(deftest t38_l132 (is (= v37_l130 'census-v3)))


(def
 v40_l136
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
 v42_l148
 (def
  analysis
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 3)
   {:method :regression})))


(def v43_l153 (pocket/->id analysis))


(def v45_l157 (deref analysis))


(deftest
 t46_l159
 (is
  ((fn
    [result]
    (and
     (= "census" (:source result))
     (= 3 (:version result))
     (= :regression (:method result))))
   v45_l157)))


(def v48_l165 (deref analysis))


(def
 v50_l169
 (deref
  (pocket/cached
   #'analyze-dataset
   (->DatasetRef "census" 4)
   {:method :regression})))


(def v52_l177 (pocket/dir-tree))


(def v54_l237 (defrecord MyModel [weights bias]))


(def
 v55_l239
 (nippy/extend-freeze
  MyModel
  :my-model
  [x data-output]
  (nippy/freeze-to-out! data-output (:weights x))
  (nippy/freeze-to-out! data-output (:bias x))))


(def
 v56_l244
 (do
  (nippy/extend-thaw
   :my-model
   [data-input]
   (->MyModel
    (nippy/thaw-from-in! data-input)
    (nippy/thaw-from-in! data-input)))
  :done))


(def v58_l252 (def original (->MyModel [0.5 -0.3 1.2] 0.1)))


(def v59_l254 (= original (nippy/thaw (nippy/freeze original))))


(deftest t60_l256 (is (true? v59_l254)))


(def
 v62_l260
 (defn
  train-my-model
  [data]
  (->MyModel
   (mapv (fn* [p1__114979#] (* p1__114979# 0.01)) data)
   0.42)))


(def
 v63_l263
 (let
  [result (deref (pocket/cached #'train-my-model [10 20 30]))]
  result))


(deftest
 t64_l266
 (is
  ((fn [m] (and (instance? MyModel m) (= 0.42 (:bias m)))) v63_l263)))


(def v66_l274 (pocket/cleanup!))
