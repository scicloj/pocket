(ns
 pocket-book.configuration-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def
 v3_l14
 (kind/mermaid
  "flowchart TD\n    B(binding) -->|if nil| S(set-*!)\n    S -->|if nil| E(Environment variable)\n    E -->|if nil| P(pocket.edn)\n    P -->|if nil| D(Hardcoded default)\n    style B fill:#4a9,color:#fff\n    style D fill:#888,color:#fff"))


(def v5_l49 (pocket/set-base-cache-dir! "/tmp/pocket-demo-config"))


(def v6_l51 (pocket/cleanup!))


(def v8_l55 (pocket/config))


(deftest
 t9_l57
 (is
  ((fn [cfg] (= "/tmp/pocket-demo-config" (:base-cache-dir cfg)))
   v8_l55)))


(def
 v11_l70
 (kind/mermaid
  "flowchart LR\n    D(deref) --> MC{In-memory\ncache?}\n    MC -->|hit| R[Return value]\n    MC -->|miss| DC{Disk\ncache?}\n    DC -->|hit| R\n    DC -->|miss| C[Compute] --> W[Write to disk] --> R"))


(def
 v13_l107
 (pocket/set-mem-cache-options! {:policy :fifo, :threshold 100}))


(deftest
 t14_l109
 (is ((fn [result] (= :fifo (:policy result))) v13_l107)))


(def
 v16_l113
 (pocket/set-mem-cache-options! {:policy :ttl, :ttl 60000}))


(def
 v18_l117
 (pocket/set-mem-cache-options! {:policy :lru, :threshold 256}))


(def v20_l140 (pocket/cleanup!))
