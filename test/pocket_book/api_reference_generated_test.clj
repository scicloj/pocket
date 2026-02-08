(ns
 pocket-book.api-reference-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v3_l17 (require '[scicloj.pocket :as pocket]))


(def v4_l19 (def cache-dir "/tmp/pocket-demo-reference"))


(def v5_l21 (pocket/set-base-cache-dir! cache-dir))


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


(def v9_l34 (kind/doc #'pocket/*base-cache-dir*))


(def v11_l38 pocket/*base-cache-dir*)


(def v12_l40 (kind/doc #'pocket/set-base-cache-dir!))


(def v13_l42 (pocket/set-base-cache-dir! "/tmp/pocket-demo-2"))


(def v14_l43 pocket/*base-cache-dir*)


(def v16_l47 (pocket/set-base-cache-dir! cache-dir))


(def v17_l49 (kind/doc #'pocket/config))


(def v19_l53 (pocket/config))


(def v20_l56 (kind/doc #'pocket/cached))


(def
 v22_l60
 (def my-result (pocket/cached #'expensive-calculation 100 200)))


(def v23_l61 (type my-result))


(deftest t24_l63 (is (= v23_l61 scicloj.pocket.impl.cache.Cached)))


(def v26_l67 (deref my-result))


(deftest t27_l69 (is (= v26_l67 300)))


(def v29_l73 (deref my-result))


(deftest t30_l75 (is (= v29_l73 300)))


(def v31_l77 (kind/doc #'pocket/caching-fn))


(def
 v33_l81
 (def my-caching-fn (pocket/caching-fn #'expensive-calculation)))


(def v34_l83 (deref (my-caching-fn 3 4)))


(def v36_l87 (deref (my-caching-fn 3 4)))


(def v38_l100 (kind/doc #'pocket/maybe-deref))


(def v40_l104 (pocket/maybe-deref 42))


(deftest t41_l106 (is (= v40_l104 42)))


(def
 v43_l110
 (pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200)))


(deftest t44_l112 (is (= v43_l110 300)))


(def v45_l114 (kind/doc #'pocket/->id))


(def v47_l118 (pocket/->id #'expensive-calculation))


(deftest
 t48_l120
 (is ((fn [id] (= (name id) "expensive-calculation")) v47_l118)))


(def v50_l124 (pocket/->id {:b 2, :a 1}))


(def
 v52_l129
 (pocket/->id (pocket/cached #'expensive-calculation 100 200)))


(def v54_l133 (pocket/->id nil))


(deftest t55_l135 (is (nil? v54_l133)))


(def v56_l137 (kind/doc #'pocket/set-mem-cache-options!))


(def
 v58_l141
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(def
 v60_l145
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v61_l147 (kind/doc #'pocket/reset-mem-cache-options!))


(def v63_l151 (pocket/reset-mem-cache-options!))


(def v64_l153 (kind/doc #'pocket/*storage*))


(def v65_l155 (kind/doc #'pocket/set-storage!))


(def v67_l159 (pocket/set-storage! :mem))


(def v69_l163 (pocket/set-storage! nil))


(deftest
 t70_l165
 (is ((fn [_] (= :mem+disk (:storage (pocket/config)))) v69_l163)))


(def v71_l167 (kind/doc #'pocket/cleanup!))


(def v72_l169 (pocket/cleanup!))


(def v73_l171 (kind/doc #'pocket/clear-mem-cache!))


(def v75_l175 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v76_l177 (pocket/clear-mem-cache!))


(def v77_l179 (kind/doc #'pocket/invalidate!))


(def v79_l183 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v80_l185 (pocket/invalidate! #'expensive-calculation 10 20))


(def v82_l189 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v83_l191 (kind/doc #'pocket/invalidate-fn!))


(def v85_l195 (deref (pocket/cached #'expensive-calculation 1 2)))


(def v86_l196 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v87_l198 (pocket/invalidate-fn! #'expensive-calculation))


(def v88_l200 (pocket/cleanup!))


(def v89_l202 (kind/doc #'pocket/cache-entries))


(def v91_l206 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v92_l207 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v93_l209 (pocket/cache-entries))


(deftest t94_l211 (is ((fn [entries] (= 2 (count entries))) v93_l209)))


(def
 v96_l215
 (pocket/cache-entries
  "pocket-book.api-reference/expensive-calculation"))


(def v97_l217 (kind/doc #'pocket/cache-stats))


(def v98_l219 (pocket/cache-stats))


(def v99_l221 (pocket/cleanup!))


(def v100_l223 (kind/doc #'pocket/origin-story))


(def v102_l229 (defn step-a [x] (+ x 10)))


(def v103_l230 (defn step-b [x y] (* x y)))


(def v104_l232 (def a-c (pocket/cached #'step-a 5)))


(def v105_l233 (def b-c (pocket/cached #'step-b a-c 3)))


(def v107_l237 (pocket/origin-story b-c))


(def v109_l240 (deref b-c))


(def v111_l244 (pocket/origin-story b-c))


(def v112_l246 (kind/doc #'pocket/origin-story-mermaid))


(def v114_l250 (pocket/origin-story-mermaid b-c))


(def v115_l252 (kind/doc #'pocket/origin-story-graph))


(def v117_l257 (pocket/origin-story-graph b-c))


(def v118_l259 (kind/doc #'pocket/compare-experiments))


(def v120_l265 (defn run-exp [config] {:rmse (* 0.1 (:lr config))}))


(def
 v121_l268
 (def exp1 (pocket/cached #'run-exp {:lr 0.01, :epochs 100})))


(def
 v122_l269
 (def exp2 (pocket/cached #'run-exp {:lr 0.001, :epochs 100})))


(def v123_l271 (pocket/compare-experiments [exp1 exp2]))


(deftest
 t124_l273
 (is
  ((fn
    [rows]
    (and
     (= 2 (count rows))
     (every? (fn* [p1__100877#] (contains? p1__100877# :lr)) rows)
     (not-any?
      (fn* [p1__100878#] (contains? p1__100878# :epochs))
      rows)))
   v123_l271)))


(def v126_l283 (pocket/cleanup!))
