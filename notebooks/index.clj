;; # Preface

^{:clay {:hide-code true}}
(ns index
  (:require [clojure.string :as str]
            [scicloj.kindly.v4.kind :as kind]))

(System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "debug")

^{:kindly/hide-code true
  :kind/md true}
(->> "README.md"
     slurp
     str/split-lines
     (drop 1)
     (str/join "\n"))

;; ## Chapters in this book

^:kindly/hide-code
(defn chapter->title [chapter]
  (or (some->> chapter
               (format "notebooks/pocket_book/%s.clj")
               slurp
               str/split-lines
               (filter #(re-matches #"^;; # .*" %))
               first
               (#(str/replace % #"^;; # " "")))
      chapter))

(->> "notebooks/chapters.edn"
     slurp
     clojure.edn/read-string
     (map (fn [chapter]
            (format "\n- [%s](pocket_book.%s.html)\n"
                    (chapter->title chapter)
                    chapter)))
     (str/join "\n")
     kind/md)
