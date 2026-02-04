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
 v5_l48
 (->
  (clojure.java.io/resource "pocket-defaults.edn")
  slurp
  clojure.edn/read-string))


(def v7_l68 (pocket/set-base-cache-dir! "/tmp/pocket-demo-config"))


(def v8_l70 (pocket/cleanup!))


(def v10_l74 (pocket/config))


(deftest
 t11_l76
 (is
  ((fn [cfg] (= "/tmp/pocket-demo-config" (:base-cache-dir cfg)))
   v10_l74)))


(def
 v13_l89
 (kind/mermaid
  "flowchart LR\n    D(deref) --> MC{In-memory\ncache?}\n    MC -->|hit| R[Return value]\n    MC -->|miss| DC{Disk\ncache?}\n    DC -->|hit| R\n    DC -->|miss| C[Compute] --> W[Write to disk] --> R"))


(def
 v15_l127
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(deftest
 t16_l129
 (is ((fn [result] (= :fifo (:policy result))) v15_l127)))


(def
 v18_l133
 (pocket/set-mem-cache-options! {:policy :ttl, :ttl 60000}))


(def
 v20_l137
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v22_l160 (pocket/cleanup!))
