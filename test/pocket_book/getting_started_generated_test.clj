(ns
 pocket-book.getting-started-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v3_l18 (def cache-dir "/tmp/pocket-demo"))


(def v4_l20 (pocket/set-base-cache-dir! cache-dir))


(def v6_l23 (pocket/cleanup!))


(def
 v7_l25
 (defn
  expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y)))


(def
 v9_l46
 (def cached-result (pocket/cached #'expensive-calculation 10 20)))


(def v10_l49 (type cached-result))


(deftest t11_l51 (is (= v10_l49 scicloj.pocket.impl.cache.Cached)))


(def v13_l54 (time @cached-result))


(deftest t14_l56 (is (= v13_l54 30)))


(def v16_l59 (time @cached-result))


(def
 v18_l66
 (def cached-expensive (pocket/caching-fn #'expensive-calculation)))


(def v20_l70 (time @(cached-expensive 5 15)))


(def v22_l73 (time @(cached-expensive 5 15)))


(def v24_l76 (time @(cached-expensive 7 8)))


(def v26_l84 (defn returns-nil [] nil))


(def v27_l86 (def nil-result (pocket/cached #'returns-nil)))


(def v29_l89 (deref nil-result))


(deftest t30_l91 (is (nil? v29_l89)))


(def v32_l94 (deref nil-result))


(def v34_l121 (pocket/cleanup!))
