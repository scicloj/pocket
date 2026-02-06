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


(def v38_l98 (kind/doc #'pocket/maybe-deref))


(def v40_l102 (pocket/maybe-deref 42))


(deftest t41_l104 (is (= v40_l102 42)))


(def
 v43_l108
 (pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200)))


(deftest t44_l110 (is (= v43_l108 300)))


(def v45_l112 (kind/doc #'pocket/->id))


(def v47_l116 (pocket/->id #'expensive-calculation))


(deftest
 t48_l118
 (is ((fn [id] (= (name id) "expensive-calculation")) v47_l116)))


(def v50_l122 (pocket/->id {:b 2, :a 1}))


(def
 v52_l127
 (pocket/->id (pocket/cached #'expensive-calculation 100 200)))


(def v54_l131 (pocket/->id nil))


(deftest t55_l133 (is (nil? v54_l131)))


(def v56_l135 (kind/doc #'pocket/set-mem-cache-options!))


(def
 v58_l139
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(def
 v60_l143
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v61_l145 (kind/doc #'pocket/reset-mem-cache-options!))


(def v63_l149 (pocket/reset-mem-cache-options!))


(def v64_l151 (kind/doc #'pocket/*storage*))


(def v65_l153 (kind/doc #'pocket/set-storage!))


(def v67_l157 (pocket/set-storage! :mem))


(def v69_l161 (pocket/set-storage! nil))


(deftest
 t70_l163
 (is ((fn [_] (= :mem+disk (:storage (pocket/config)))) v69_l161)))


(def v71_l165 (kind/doc #'pocket/cleanup!))


(def v72_l167 (pocket/cleanup!))


(def v73_l169 (kind/doc #'pocket/clear-mem-cache!))


(def v75_l173 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v76_l175 (pocket/clear-mem-cache!))


(def v77_l177 (kind/doc #'pocket/invalidate!))


(def v79_l181 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v80_l183 (pocket/invalidate! #'expensive-calculation 10 20))


(def v82_l187 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v83_l189 (kind/doc #'pocket/invalidate-fn!))


(def v85_l193 (deref (pocket/cached #'expensive-calculation 1 2)))


(def v86_l194 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v87_l196 (pocket/invalidate-fn! #'expensive-calculation))


(def v88_l198 (pocket/cleanup!))


(def v89_l200 (kind/doc #'pocket/cache-entries))


(def v91_l204 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v92_l205 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v93_l207 (pocket/cache-entries))


(deftest t94_l209 (is ((fn [entries] (= 2 (count entries))) v93_l207)))


(def
 v96_l213
 (pocket/cache-entries
  "pocket-book.api-reference/expensive-calculation"))


(def v97_l215 (kind/doc #'pocket/cache-stats))


(def v98_l217 (pocket/cache-stats))


(def v99_l219 (pocket/cleanup!))


(def v100_l221 (kind/doc #'pocket/origin-story))


(def v102_l227 (defn step-a [x] (+ x 10)))


(def v103_l228 (defn step-b [x y] (* x y)))


(def v104_l230 (def a-c (pocket/cached #'step-a 5)))


(def v105_l231 (def b-c (pocket/cached #'step-b a-c 3)))


(def v107_l235 (pocket/origin-story b-c))


(def v109_l238 (deref b-c))


(def v111_l242 (pocket/origin-story b-c))


(def v112_l244 (kind/doc #'pocket/origin-story-mermaid))


(def v114_l248 (pocket/origin-story-mermaid b-c))


(def v115_l250 (kind/doc #'pocket/origin-story-graph))


(def v117_l255 (pocket/origin-story-graph b-c))


(def v118_l257 (kind/doc #'pocket/compare-experiments))


(def v120_l263 (defn run-exp [config] {:rmse (* 0.1 (:lr config))}))


(def
 v121_l266
 (def exp1 (pocket/cached #'run-exp {:lr 0.01, :epochs 100})))


(def
 v122_l267
 (def exp2 (pocket/cached #'run-exp {:lr 0.001, :epochs 100})))


(def v123_l269 (pocket/compare-experiments [exp1 exp2]))


(deftest
 t124_l271
 (is
  ((fn
    [rows]
    (and
     (= 2 (count rows))
     (every? (fn* [p1__70165#] (contains? p1__70165# :lr)) rows)
     (not-any?
      (fn* [p1__70166#] (contains? p1__70166# :epochs))
      rows)))
   v123_l269)))


(def v126_l281 (pocket/cleanup!))
