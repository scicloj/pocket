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


(def v20_l53 (kind/doc #'pocket/cached))


(def
 v22_l57
 (def my-result (pocket/cached #'expensive-calculation 100 200)))


(def v23_l58 (type my-result))


(deftest t24_l60 (is (= v23_l58 scicloj.pocket.impl.cache.Cached)))


(def v26_l64 (deref my-result))


(deftest t27_l66 (is (= v26_l64 300)))


(def v29_l70 (deref my-result))


(deftest t30_l72 (is (= v29_l70 300)))


(def v31_l74 (kind/doc #'pocket/caching-fn))


(def
 v33_l78
 (def my-caching-fn (pocket/caching-fn #'expensive-calculation)))


(def v34_l80 (deref (my-caching-fn 3 4)))


(def v36_l84 (deref (my-caching-fn 3 4)))


(def v37_l86 (kind/doc #'pocket/maybe-deref))


(def v39_l90 (pocket/maybe-deref 42))


(deftest t40_l92 (is (= v39_l90 42)))


(def
 v42_l96
 (pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200)))


(deftest t43_l98 (is (= v42_l96 300)))


(def v44_l100 (kind/doc #'pocket/->id))


(def v46_l104 (pocket/->id #'expensive-calculation))


(deftest
 t47_l106
 (is ((fn [id] (= (name id) "expensive-calculation")) v46_l104)))


(def v49_l110 (pocket/->id {:b 2, :a 1}))


(def
 v51_l115
 (pocket/->id (pocket/cached #'expensive-calculation 100 200)))


(def v53_l119 (pocket/->id nil))


(deftest t54_l121 (is (nil? v53_l119)))


(def v55_l123 (kind/doc #'pocket/set-mem-cache-options!))


(def
 v57_l127
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(def
 v59_l131
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v60_l133 (kind/doc #'pocket/cleanup!))


(def v61_l135 (pocket/cleanup!))


(def v62_l137 (kind/doc #'pocket/invalidate!))


(def v64_l141 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v65_l143 (pocket/invalidate! #'expensive-calculation 10 20))


(def v67_l147 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v68_l149 (kind/doc #'pocket/invalidate-fn!))


(def v70_l153 (deref (pocket/cached #'expensive-calculation 1 2)))


(def v71_l154 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v72_l156 (pocket/invalidate-fn! #'expensive-calculation))


(def v73_l158 (pocket/cleanup!))


(def v74_l160 (kind/doc #'pocket/cache-entries))


(def v76_l164 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v77_l165 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v78_l167 (pocket/cache-entries))


(deftest t79_l169 (is ((fn [entries] (= 2 (count entries))) v78_l167)))


(def
 v81_l173
 (pocket/cache-entries
  "pocket-book.api-reference/expensive-calculation"))


(def v82_l175 (kind/doc #'pocket/cache-stats))


(def v83_l177 (pocket/cache-stats))


(def v84_l179 (pocket/cleanup!))
