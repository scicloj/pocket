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


(def v57_l142 (defn make-config [x y] {:x x, :y y}))


(def
 v58_l144
 (let
  [c (pocket/cached #'make-config 100 200)]
  (= (pocket/->id (deref c)) (pocket/->id c))))


(deftest t59_l147 (is (true? v58_l144)))


(def v60_l149 (kind/doc #'pocket/set-mem-cache-options!))


(def
 v62_l153
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(def
 v64_l157
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v65_l159 (kind/doc #'pocket/reset-mem-cache-options!))


(def v67_l163 (pocket/reset-mem-cache-options!))


(def v68_l165 (kind/doc #'pocket/*storage*))


(def v69_l167 (kind/doc #'pocket/set-storage!))


(def v71_l171 (pocket/set-storage! :mem))


(def v73_l175 (pocket/set-storage! nil))


(deftest
 t74_l177
 (is ((fn [_] (= :mem+disk (:storage (pocket/config)))) v73_l175)))


(def v75_l179 (kind/doc #'pocket/cleanup!))


(def v76_l181 (pocket/cleanup!))


(def v77_l183 (kind/doc #'pocket/clear-mem-cache!))


(def v79_l187 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v80_l189 (pocket/clear-mem-cache!))


(def v81_l191 (kind/doc #'pocket/invalidate!))


(def v83_l195 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v84_l197 (pocket/invalidate! #'expensive-calculation 10 20))


(def v86_l201 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v87_l203 (kind/doc #'pocket/invalidate-fn!))


(def v89_l207 (deref (pocket/cached #'expensive-calculation 1 2)))


(def v90_l208 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v91_l210 (pocket/invalidate-fn! #'expensive-calculation))


(def v92_l212 (pocket/cleanup!))


(def v93_l214 (kind/doc #'pocket/cache-entries))


(def v95_l218 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v96_l219 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v97_l221 (pocket/cache-entries))


(deftest t98_l223 (is ((fn [entries] (= 2 (count entries))) v97_l221)))


(def
 v100_l227
 (pocket/cache-entries
  "pocket-book.api-reference/expensive-calculation"))


(def v101_l229 (kind/doc #'pocket/cache-stats))


(def v102_l231 (pocket/cache-stats))


(def v103_l233 (pocket/cleanup!))


(def v104_l235 (kind/doc #'pocket/origin-story))


(def v106_l241 (defn step-a [x] (+ x 10)))


(def v107_l242 (defn step-b [x y] (* x y)))


(def v108_l244 (def a-c (pocket/cached #'step-a 5)))


(def v109_l245 (def b-c (pocket/cached #'step-b a-c 3)))


(def v111_l249 (pocket/origin-story b-c))


(def v113_l252 (deref b-c))


(def v115_l256 (pocket/origin-story b-c))


(def v116_l258 (kind/doc #'pocket/origin-story-mermaid))


(def v118_l262 (pocket/origin-story-mermaid b-c))


(def v119_l264 (kind/doc #'pocket/origin-story-graph))


(def v121_l269 (pocket/origin-story-graph b-c))


(def v122_l271 (kind/doc #'pocket/compare-experiments))


(def v124_l277 (defn run-exp [config] {:rmse (* 0.1 (:lr config))}))


(def
 v125_l280
 (def exp1 (pocket/cached #'run-exp {:lr 0.01, :epochs 100})))


(def
 v126_l281
 (def exp2 (pocket/cached #'run-exp {:lr 0.001, :epochs 100})))


(def v127_l283 (pocket/compare-experiments [exp1 exp2]))


(deftest
 t128_l285
 (is
  ((fn
    [rows]
    (and
     (= 2 (count rows))
     (every? (fn* [p1__62284#] (contains? p1__62284# :lr)) rows)
     (not-any?
      (fn* [p1__62285#] (contains? p1__62285# :epochs))
      rows)))
   v127_l283)))


(def v130_l294 (pocket/cleanup!))
