(ns
 pocket-book.developing-pocket-generated-test
 (:require
  [scicloj.kindly.v4.kind :as kind]
  [clojure.string :as str]
  [clojure.test :refer [deftest is]]))


(def
 v3_l23
 (->> "notebooks/chapters.edn" slurp clojure.edn/read-string))


(def v5_l84 (def demo-value (+ 10 20)))


(def v6_l86 demo-value)


(deftest t7_l88 (is (= v6_l86 30)))


(def v9_l96 (type demo-value))


(deftest t10_l98 (is (= v9_l96 Long)))


(def v11_l100 (str "result is " demo-value))


(deftest
 t12_l102
 (is
  ((fn* [p1__100848#] (str/starts-with? p1__100848# "result"))
   v11_l100)))


(def v14_l109 (kind/doc #'clojure.core/map))
