;; # Logging

(ns pocket-book.logging
  (:require [scicloj.kindly.v4.kind :as kind]))

;; Pocket uses [`clojure.tools.logging`](https://github.com/clojure/tools.logging)
;; for cache lifecycle messages. It does **not** bundle a logging backend —
;; you provide your own [SLF4J](https://www.slf4j.org/) implementation
;; (e.g., `slf4j-simple`, [Logback](https://logback.qos.ch/),
;; [Log4j2](https://logging.apache.org/log4j/2.x/)).
;; Without a backend, logging is silently disabled.
;;
;; The `:dev` alias in this project includes `slf4j-simple` for
;; notebook and REPL use.
;;
;; ## Log levels
;;
;; | Level | Messages |
;; |-------|----------|
;; | `:debug` | Cache hits (memory and disk), cache writes |
;; | `:info` | Cache misses (computation), invalidation, mem-cache reconfiguration, cleanup |
;;
;; ## Setup for notebooks
;;
;; The following configures `slf4j-simple` to log at debug level
;; to stdout, so all cache messages are visible in notebook output:

(do (System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "debug")
    (System/setProperty "org.slf4j.simpleLogger.logFile" "System.out"))

;; Other notebooks in this book require this namespace to
;; activate logging. In your own projects, configure your
;; preferred SLF4J backend instead.
