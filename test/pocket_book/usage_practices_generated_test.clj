(ns
 pocket-book.usage-practices-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v2_l30 (def test-dir "/tmp/pocket-dev-practices"))


(def v3_l31 (pocket/set-base-cache-dir! test-dir))


(def v4_l33 (defn example-fn [x] (* x x)))


(def
 v5_l35
 (try (pocket/cached example-fn 5) (catch Exception e (ex-message e))))


(deftest
 t6_l40
 (is
  ((fn* [p1__36336#] (re-find #"requires a var" p1__36336#)) v5_l35)))


(def v8_l52 (pocket/cleanup!))


(def v9_l54 (defn transform [x] (* x 2)))


(def v11_l57 (deref (pocket/cached #'transform 10)))


(deftest t12_l59 (is (= v11_l57 20)))


(def v14_l62 (pocket/invalidate! #'transform 10))


(def
 v16_l69
 (defn
  process-data
  [{:keys [data version]}]
  {:result (reduce + data), :version version}))


(def
 v18_l74
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 1})))


(deftest t19_l76 (is (= v18_l74 {:result 6, :version 1})))


(def
 v21_l79
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 2})))


(deftest t22_l81 (is (= v21_l79 {:result 6, :version 2})))


(def v24_l89 (pocket/cleanup!))


(def v26_l121 (pocket/cleanup!))


(def v27_l123 (def call-count (atom 0)))


(def v28_l125 (defn tracked-fn [x] (swap! call-count inc) (* x x)))


(def v30_l130 (reset! call-count 0))


(def
 v31_l132
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t32_l136 (is (= v31_l132 {:result 25, :calls 1})))


(def
 v34_l139
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t35_l143 (is (= v34_l139 {:result 25, :calls 1})))


(def v37_l151 (pocket/cleanup!))


(def v38_l152 (deref (pocket/cached #'transform 1)))


(def v39_l153 (deref (pocket/cached #'transform 2)))


(def v40_l154 (deref (pocket/cached #'tracked-fn 3)))


(def v42_l157 (count (pocket/cache-entries)))


(deftest t43_l159 (is (= v42_l157 3)))


(def v45_l162 (:total-entries (pocket/cache-stats)))


(deftest t46_l164 (is (= v45_l162 3)))


(def v48_l167 (println (pocket/dir-tree)))


(def v50_l173 (pocket/cleanup!))


(def v51_l175 (def pending-value (pocket/cached #'transform 99)))


(def v52_l177 (pr-str pending-value))


(deftest
 t54_l179
 (is ((fn* [p1__36337#] (re-find #":pending" p1__36337#)) v52_l177)))


(def v55_l181 (deref pending-value))


(def v56_l183 (pr-str pending-value))


(deftest
 t58_l185
 (is ((fn* [p1__36338#] (re-find #":cached" p1__36338#)) v56_l183)))


(def v60_l220 (defn generate-data [n] (doall (range n))))


(def v62_l223 (deref (pocket/cached #'generate-data 5)))


(deftest t63_l225 (is (= v62_l223 [0 1 2 3 4])))


(def v65_l265 (pocket/cleanup!))
