(ns
 pocket-book.getting-started-generated-test
 (:require
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [pocket-book.logging]
  [clojure.test :refer [deftest is]]))


(def v3_l22 (def cache-dir "/tmp/pocket-demo"))


(def v4_l24 (pocket/set-base-cache-dir! cache-dir))


(def v6_l27 (pocket/cleanup!))


(def
 v7_l29
 (defn
  expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y)))


(def
 v9_l50
 (def cached-result (pocket/cached #'expensive-calculation 10 20)))


(def v10_l53 (type cached-result))


(deftest t11_l55 (is (= v10_l53 scicloj.pocket.impl.cache.Cached)))


(def v13_l58 (time @cached-result))


(deftest t14_l60 (is (= v13_l58 30)))


(def v16_l63 (time @cached-result))


(def
 v18_l70
 (def cached-expensive (pocket/caching-fn #'expensive-calculation)))


(def v20_l74 (time @(cached-expensive 5 15)))


(def v22_l77 (time @(cached-expensive 5 15)))


(def v24_l80 (time @(cached-expensive 7 8)))


(def v26_l88 (defn returns-nil [] nil))


(def v27_l90 (def nil-result (pocket/cached #'returns-nil)))


(def v29_l93 (deref nil-result))


(deftest t30_l95 (is (nil? v29_l93)))


(def v32_l98 (deref nil-result))


(def v34_l127 (pocket/cleanup!))
