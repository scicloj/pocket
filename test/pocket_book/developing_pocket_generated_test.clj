(ns
 pocket-book.developing-pocket-generated-test
 (:require
  [scicloj.kindly.v4.kind :as kind]
  [clojure.string :as str]
  [clojure.test :refer [deftest is]]))


(def
 v3_l25
 (->> "notebooks/chapters.edn" slurp clojure.edn/read-string))


(def v5_l86 (def demo-value (+ 10 20)))


(def v6_l88 demo-value)


(deftest t7_l90 (is (= v6_l88 30)))


(def v9_l98 (type demo-value))


(deftest t10_l100 (is (= v9_l98 Long)))


(def v11_l102 (str "result is " demo-value))


(deftest
 t12_l104
 (is
  ((fn* [p1__108438#] (str/starts-with? p1__108438# "result"))
   v11_l102)))


(def v14_l111 (kind/doc #'clojure.core/map))
