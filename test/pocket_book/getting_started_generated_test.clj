(ns
 pocket-book.getting-started-generated-test
 (:require
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [pocket-book.logging]
  [clojure.test :refer [deftest is]]))


(def v3_l24 (def cache-dir "/tmp/pocket-demo"))


(def v4_l26 (pocket/set-base-cache-dir! cache-dir))


(def v6_l29 (pocket/cleanup!))


(def
 v7_l31
 (defn
  expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y)))


(def
 v9_l52
 (def cached-result (pocket/cached #'expensive-calculation 10 20)))


(def v10_l55 (type cached-result))


(deftest t11_l57 (is (= v10_l55 scicloj.pocket.impl.cache.Cached)))


(def v13_l60 (time @cached-result))


(deftest t14_l62 (is (= v13_l60 30)))


(def v16_l65 (time @cached-result))


(def
 v18_l72
 (def cached-expensive (pocket/caching-fn #'expensive-calculation)))


(def v20_l76 (time @(cached-expensive 5 15)))


(def v22_l79 (time @(cached-expensive 5 15)))


(def v24_l82 (time @(cached-expensive 7 8)))


(def v26_l90 (defn returns-nil [] nil))


(def v27_l92 (def nil-result (pocket/cached #'returns-nil)))


(def v29_l95 (deref nil-result))


(deftest t30_l97 (is (nil? v29_l95)))


(def v32_l100 (deref nil-result))


(def v34_l129 (pocket/cleanup!))
