(ns
 pocket-book.cache-keys-generated-test
 (:require
  [scicloj.pocket :as pocket]
  [scicloj.pocket.impl.cache :as cache]
  [scicloj.pocket.protocols :as proto]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tech.v3.dataset.modelling :as ds-mod]
  [clojure.test :refer [deftest is]]))


(def
 v3_l50
 (let
  [ds
   (->
    (tc/dataset
     {:x (vec (range 50000)),
      :y (vec (range 50000)),
      :z (repeatedly 50000 rand)})
    (ds-mod/set-inference-target :y))
   t0
   (System/nanoTime)
   id
   (proto/->id ds)
   t1
   (System/nanoTime)
   cid
   (cache/canonical-id id)
   t2
   (System/nanoTime)
   s
   (str cid)
   t3
   (System/nanoTime)
   _
   (cache/sha s)
   t4
   (System/nanoTime)]
  {:rows 50000,
   :string-length (count s),
   :->id-ms (/ (- t1 t0) 1000000.0),
   :canonical-id-ms (/ (- t2 t1) 1000000.0),
   :str-ms (/ (- t3 t2) 1000000.0),
   :sha-ms (/ (- t4 t3) 1000000.0)}))


(def v5_l85 (pocket/set-base-cache-dir! "/tmp/pocket-cache-keys"))


(def v6_l86 (pocket/cleanup!))


(def
 v7_l88
 (defn
  make-data
  [n]
  (tc/dataset {:x (vec (range n)), :y (vec (range n))})))


(def
 v9_l93
 (let
  [ds
   (make-data 50000)
   t0
   (System/nanoTime)
   _
   (str (cache/canonical-id (proto/->id ds)))
   t1
   (System/nanoTime)]
  {:direct-ms (/ (- t1 t0) 1000000.0)}))


(def
 v11_l100
 (let
  [data-c
   (pocket/cached #'make-data 50000)
   t0
   (System/nanoTime)
   _
   (str (cache/canonical-id (proto/->id data-c)))
   t1
   (System/nanoTime)]
  {:cached-reference-ms (/ (- t1 t0) 1000000.0)}))


(def
 v13_l132
 (let
  [data-c (pocket/cached #'make-data 50000) data (deref data-c)]
  (= (proto/->id data) (proto/->id data-c))))


(deftest t14_l136 (is (true? v13_l132)))


(def
 v16_l142
 (let
  [data-c
   (pocket/cached #'make-data 50000)
   data
   (deref data-c)
   t0
   (System/nanoTime)
   _
   (str (cache/canonical-id (proto/->id data)))
   t1
   (System/nanoTime)]
  {:derefed-with-origin-ms (/ (- t1 t0) 1000000.0)}))


(def
 v18_l157
 (let
  [data-c
   (pocket/cached #'make-data 100)
   data
   (deref data-c)
   transformed
   (tc/add-column data :z (repeat 100 0))]
  {:original-has-origin (= (proto/->id data) (proto/->id data-c)),
   :transformed-has-origin
   (= (proto/->id transformed) (proto/->id data-c))}))


(deftest
 t19_l163
 (is
  ((fn
    [{:keys [original-has-origin transformed-has-origin]}]
    (and original-has-origin (not transformed-has-origin)))
   v18_l157)))


(def v21_l179 (pocket/cleanup!))
