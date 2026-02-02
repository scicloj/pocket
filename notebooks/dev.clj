(ns dev
  (:require [scicloj.clay.v2.api :as clay]))

(comment
  ;; render book HTML through Quarto
  (clay/make! {:format [:quarto :html]
               :base-source-path "notebooks"
               :source-path (->> "notebooks/chapters.edn"
                                 slurp
                                 clojure.edn/read-string
                                 (map #(format "pocket_book/%s.clj" %))
                                 (cons "index.clj"))
               :base-target-path "docs"
               :book {:title "Pocket"}
               :clean-up-target-dir true})

  ;; render all as github flavored markdown
  (clay/make! {:format [:gfm]
               :base-source-path "notebooks"
               :source-path (->> "notebooks/chapters.edn"
                                 slurp
                                 clojure.edn/read-string
                                 (map #(format "pocket_book/%s.clj" %))
                                 (cons "index.clj"))
               :base-target-path "gfm"
               :show false}))
