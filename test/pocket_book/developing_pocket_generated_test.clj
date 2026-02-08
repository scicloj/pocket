(ns
 pocket-book.developing-pocket-generated-test
 (:require
  [scicloj.kindly.v4.kind :as kind]
  [clojure.string :as str]
  [clojure.test :refer [deftest is]]))


(def
 v3_l21
 (->> "notebooks/chapters.edn" slurp clojure.edn/read-string))


(def v5_l82 (def demo-value (+ 10 20)))


(def v6_l84 demo-value)


(deftest t7_l86 (is (= v6_l84 30)))


(def v9_l94 (type demo-value))


(deftest t10_l96 (is (= v9_l94 Long)))


(def v11_l98 (str "result is " demo-value))


(deftest
 t12_l100
 (is
  ((fn* [p1__107660#] (str/starts-with? p1__107660# "result"))
   v11_l98)))


(def v14_l107 (kind/doc #'clojure.core/map))
