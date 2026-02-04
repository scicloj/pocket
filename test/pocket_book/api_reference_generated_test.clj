(ns
 pocket-book.api-reference-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v3_l15 (require '[scicloj.pocket :as pocket]))


(def v4_l17 (def cache-dir "/tmp/pocket-demo-reference"))


(def v5_l19 (pocket/set-base-cache-dir! cache-dir))


(def v6_l21 (pocket/cleanup!))


(def
 v7_l23
 (defn
  expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y)))


(def v9_l32 (kind/doc #'pocket/*base-cache-dir*))


(def v11_l36 pocket/*base-cache-dir*)


(def v12_l38 (kind/doc #'pocket/set-base-cache-dir!))


(def v13_l40 (pocket/set-base-cache-dir! "/tmp/pocket-demo-2"))


(def v14_l41 pocket/*base-cache-dir*)


(def v16_l45 (pocket/set-base-cache-dir! cache-dir))


(def v17_l47 (kind/doc #'pocket/config))


(def v19_l51 (pocket/config))


(def v20_l54 (kind/doc #'pocket/cached))


(def
 v22_l58
 (def my-result (pocket/cached #'expensive-calculation 100 200)))


(def v23_l59 (type my-result))


(deftest t24_l61 (is (= v23_l59 scicloj.pocket.impl.cache.Cached)))


(def v26_l65 (deref my-result))


(deftest t27_l67 (is (= v26_l65 300)))


(def v29_l71 (deref my-result))


(deftest t30_l73 (is (= v29_l71 300)))


(def v31_l75 (kind/doc #'pocket/caching-fn))


(def
 v33_l79
 (def my-caching-fn (pocket/caching-fn #'expensive-calculation)))


(def v34_l81 (deref (my-caching-fn 3 4)))


(def v36_l85 (deref (my-caching-fn 3 4)))


(def v37_l87 (kind/doc #'pocket/maybe-deref))


(def v39_l91 (pocket/maybe-deref 42))


(deftest t40_l93 (is (= v39_l91 42)))


(def
 v42_l97
 (pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200)))


(deftest t43_l99 (is (= v42_l97 300)))


(def v44_l101 (kind/doc #'pocket/->id))


(def v46_l105 (pocket/->id #'expensive-calculation))


(deftest
 t47_l107
 (is ((fn [id] (= (name id) "expensive-calculation")) v46_l105)))


(def v49_l111 (pocket/->id {:b 2, :a 1}))


(def
 v51_l116
 (pocket/->id (pocket/cached #'expensive-calculation 100 200)))


(def v53_l120 (pocket/->id nil))


(deftest t54_l122 (is (nil? v53_l120)))


(def v55_l124 (kind/doc #'pocket/set-mem-cache-options!))


(def
 v57_l128
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(def
 v59_l132
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v60_l134 (kind/doc #'pocket/reset-mem-cache-options!))


(def v62_l138 (pocket/reset-mem-cache-options!))


(def v63_l140 (kind/doc #'pocket/cleanup!))


(def v64_l142 (pocket/cleanup!))


(def v65_l144 (kind/doc #'pocket/clear-mem-cache!))


(def v67_l148 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v68_l150 (pocket/clear-mem-cache!))


(def v69_l152 (kind/doc #'pocket/invalidate!))


(def v71_l156 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v72_l158 (pocket/invalidate! #'expensive-calculation 10 20))


(def v74_l162 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v75_l164 (kind/doc #'pocket/invalidate-fn!))


(def v77_l168 (deref (pocket/cached #'expensive-calculation 1 2)))


(def v78_l169 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v79_l171 (pocket/invalidate-fn! #'expensive-calculation))


(def v80_l173 (pocket/cleanup!))


(def v81_l175 (kind/doc #'pocket/cache-entries))


(def v83_l179 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v84_l180 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v85_l182 (pocket/cache-entries))


(deftest t86_l184 (is ((fn [entries] (= 2 (count entries))) v85_l182)))


(def
 v88_l188
 (pocket/cache-entries
  "pocket-book.api-reference/expensive-calculation"))


(def v89_l190 (kind/doc #'pocket/cache-stats))


(def v90_l192 (pocket/cache-stats))


(def v91_l194 (pocket/cleanup!))
