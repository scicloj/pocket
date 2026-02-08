(ns
 pocket-book.api-reference-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v3_l20 (require '[scicloj.pocket :as pocket]))


(def v4_l22 (def cache-dir "/tmp/pocket-demo-reference"))


(def v5_l24 (pocket/set-base-cache-dir! cache-dir))


(def v6_l26 (pocket/cleanup!))


(def
 v7_l28
 (defn
  expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y)))


(def v9_l37 (kind/doc #'pocket/*base-cache-dir*))


(def v11_l41 pocket/*base-cache-dir*)


(def v12_l43 (kind/doc #'pocket/set-base-cache-dir!))


(def v13_l45 (pocket/set-base-cache-dir! "/tmp/pocket-demo-2"))


(def v14_l46 pocket/*base-cache-dir*)


(def v16_l50 (pocket/set-base-cache-dir! cache-dir))


(def v17_l52 (kind/doc #'pocket/config))


(def v19_l56 (pocket/config))


(def v20_l58 (kind/doc #'pocket/cached))


(def
 v22_l62
 (def my-result (pocket/cached #'expensive-calculation 100 200)))


(def v23_l63 (type my-result))


(deftest t24_l65 (is (= v23_l63 scicloj.pocket.impl.cache.Cached)))


(def v26_l69 (deref my-result))


(deftest t27_l71 (is (= v26_l69 300)))


(def v29_l75 (deref my-result))


(deftest t30_l77 (is (= v29_l75 300)))


(def v31_l79 (kind/doc #'pocket/caching-fn))


(def
 v33_l83
 (def my-caching-fn (pocket/caching-fn #'expensive-calculation)))


(def v34_l85 (deref (my-caching-fn 3 4)))


(def v36_l89 (deref (my-caching-fn 3 4)))


(def v38_l102 (kind/doc #'pocket/maybe-deref))


(def v40_l106 (pocket/maybe-deref 42))


(deftest t41_l108 (is (= v40_l106 42)))


(def
 v43_l112
 (pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200)))


(deftest t44_l114 (is (= v43_l112 300)))


(def v45_l116 (kind/doc #'pocket/->id))


(def v47_l120 (pocket/->id #'expensive-calculation))


(deftest
 t48_l122
 (is ((fn [id] (= (name id) "expensive-calculation")) v47_l120)))


(def v50_l126 (pocket/->id {:b 2, :a 1}))


(def
 v52_l131
 (pocket/->id (pocket/cached #'expensive-calculation 100 200)))


(def v54_l135 (pocket/->id nil))


(deftest t55_l137 (is (nil? v54_l135)))


(def v56_l139 (kind/doc #'pocket/set-mem-cache-options!))


(def
 v58_l143
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(def
 v60_l147
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v61_l149 (kind/doc #'pocket/reset-mem-cache-options!))


(def v63_l153 (pocket/reset-mem-cache-options!))


(def v64_l155 (kind/doc #'pocket/*storage*))


(def v65_l157 (kind/doc #'pocket/set-storage!))


(def v67_l161 (pocket/set-storage! :mem))


(def v69_l165 (pocket/set-storage! nil))


(deftest
 t70_l167
 (is ((fn [_] (= :mem+disk (:storage (pocket/config)))) v69_l165)))


(def v71_l169 (kind/doc #'pocket/cleanup!))


(def v72_l171 (pocket/cleanup!))


(def v73_l173 (kind/doc #'pocket/clear-mem-cache!))


(def v75_l177 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v76_l179 (pocket/clear-mem-cache!))


(def v77_l181 (kind/doc #'pocket/invalidate!))


(def v79_l185 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v80_l187 (pocket/invalidate! #'expensive-calculation 10 20))


(def v82_l191 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v83_l193 (kind/doc #'pocket/invalidate-fn!))


(def v85_l197 (deref (pocket/cached #'expensive-calculation 1 2)))


(def v86_l198 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v87_l200 (pocket/invalidate-fn! #'expensive-calculation))


(def v88_l202 (pocket/cleanup!))


(def v89_l204 (kind/doc #'pocket/cache-entries))


(def v91_l208 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v92_l209 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v93_l211 (pocket/cache-entries))


(deftest t94_l213 (is ((fn [entries] (= 2 (count entries))) v93_l211)))


(def
 v96_l217
 (pocket/cache-entries
  "pocket-book.api-reference/expensive-calculation"))


(def v97_l219 (kind/doc #'pocket/cache-stats))


(def v98_l221 (pocket/cache-stats))


(def v99_l223 (pocket/cleanup!))


(def v100_l225 (kind/doc #'pocket/origin-story))


(def v102_l231 (defn step-a [x] (+ x 10)))


(def v103_l232 (defn step-b [x y] (* x y)))


(def v104_l234 (def a-c (pocket/cached #'step-a 5)))


(def v105_l235 (def b-c (pocket/cached #'step-b a-c 3)))


(def v107_l239 (pocket/origin-story b-c))


(def v109_l242 (deref b-c))


(def v111_l246 (pocket/origin-story b-c))


(def v112_l248 (kind/doc #'pocket/origin-story-mermaid))


(def v114_l252 (pocket/origin-story-mermaid b-c))


(def v115_l254 (kind/doc #'pocket/origin-story-graph))


(def v117_l259 (pocket/origin-story-graph b-c))


(def v118_l261 (kind/doc #'pocket/compare-experiments))


(def v120_l267 (defn run-exp [config] {:rmse (* 0.1 (:lr config))}))


(def
 v121_l270
 (def exp1 (pocket/cached #'run-exp {:lr 0.01, :epochs 100})))


(def
 v122_l271
 (def exp2 (pocket/cached #'run-exp {:lr 0.001, :epochs 100})))


(def v123_l273 (pocket/compare-experiments [exp1 exp2]))


(deftest
 t124_l275
 (is
  ((fn
    [rows]
    (and
     (= 2 (count rows))
     (every? (fn* [p1__119393#] (contains? p1__119393# :lr)) rows)
     (not-any?
      (fn* [p1__119394#] (contains? p1__119394# :epochs))
      rows)))
   v123_l273)))


(def v126_l284 (pocket/cleanup!))
