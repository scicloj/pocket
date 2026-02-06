(ns
 pocket-book.configuration-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def
 v3_l19
 (kind/mermaid
  "flowchart TD\n    B(binding) -->|if nil| S(set-*!)\n    S -->|if nil| E(Environment variable)\n    E -->|if nil| P(pocket.edn)\n    P -->|if nil| D(Hardcoded default)\n    style B fill:#4a9,color:#fff\n    style D fill:#888,color:#fff"))


(def
 v5_l49
 (->
  (clojure.java.io/resource "pocket-defaults.edn")
  slurp
  clojure.edn/read-string))


(def v7_l69 (pocket/set-base-cache-dir! "/tmp/pocket-demo-config"))


(def v8_l71 (pocket/cleanup!))


(def v10_l75 (pocket/config))


(deftest
 t11_l77
 (is
  ((fn [cfg] (= "/tmp/pocket-demo-config" (:base-cache-dir cfg)))
   v10_l75)))


(def
 v13_l90
 (kind/mermaid
  "flowchart LR\n    D(deref) --> MC{In-memory\ncache?}\n    MC -->|hit| R[Return value]\n    MC -->|miss| DC{Disk\ncache?}\n    DC -->|hit| R\n    DC -->|miss| C[Compute] --> W[Write to disk] --> R"))


(def
 v15_l128
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(deftest
 t16_l130
 (is ((fn [result] (= :fifo (:policy result))) v15_l128)))


(def
 v18_l134
 (pocket/set-mem-cache-options! {:policy :ttl, :ttl 60000}))


(def
 v20_l138
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v22_l179 (pocket/set-storage! :mem))


(def v23_l181 (pocket/config))


(deftest t24_l183 (is ((fn [cfg] (= :mem (:storage cfg))) v23_l181)))


(def v26_l219 (pocket/set-storage! nil))


(deftest
 t27_l221
 (is ((fn [_] (= :mem+disk (:storage (pocket/config)))) v26_l219)))


(def v29_l244 (pocket/set-filename-length-limit! 80))


(def v31_l254 (:filename-length-limit (pocket/config)))


(def v33_l258 (pocket/set-filename-length-limit! nil))


(def v35_l289 (defn load-data [path] (slurp path)))


(def
 v36_l290
 (defn
  compute-stats
  [data]
  {:lines (count (clojure.string/split-lines data))}))


(def
 v37_l291
 (defn train-model [data stats] {:model "trained", :stats stats}))


(def v39_l294 (def c-load (pocket/caching-fn #'load-data)))


(def
 v41_l297
 (def c-stats (pocket/caching-fn #'compute-stats {:storage :mem})))


(def
 v43_l300
 (def
  c-train
  (pocket/caching-fn #'train-model {:filename-length-limit 80})))


(def v45_l306 (pocket/cleanup!))
