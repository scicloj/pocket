;; # Logging

(ns pocket-book.logging
  (:require
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind]))

;; Pocket uses [`clojure.tools.logging`](https://github.com/clojure/tools.logging)
;; for cache lifecycle messages. It does **not** bundle a logging backend —
;; you provide your own (e.g., [SLF4J](https://www.slf4j.org/) with
;; `slf4j-simple` or [Logback](https://logback.qos.ch/), or
;; [Log4j2](https://logging.apache.org/log4j/2.x/) directly).
;; `tools.logging` auto-discovers a backend from the classpath;
;; without one, logging is silently disabled.
;;
;; This documentation was rendered using `slf4j-simple` as a dev dependency
;; of the Pocket project, so the log output shown here is real.

;; ## Log levels
;;
;; | Level | Messages |
;; |-------|----------|
;; | `:debug` | Cache hits (memory and disk), cache writes |
;; | `:info` | Cache misses (computation), invalidation, mem-cache reconfiguration, cleanup |
;;
;; ## Setup for notebooks
;;
;; The following configures `slf4j-simple` for notebook use.
;; These properties must be set before any logging occurs.

;; Show debug-level messages (cache hits and writes):
(System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "debug")

;; Hide thread names to reduce clutter:
(System/setProperty "org.slf4j.simpleLogger.showThreadName" "false")

;; Show timestamps for each log message:
(System/setProperty "org.slf4j.simpleLogger.showDateTime" "true")

;; Use hours:minutes:seconds.milliseconds format:
(System/setProperty "org.slf4j.simpleLogger.dateTimeFormat" "HH:mm:ss.SSS")

;; Write to stdout so messages appear as `OUT` rather than `ERR`:
(System/setProperty "org.slf4j.simpleLogger.logFile" "System.out")

;; If `slf4j-simple` was already initialized (e.g., in a long-running REPL),
;; the properties above won't take effect because `SimpleLogger` reads them
;; only once at class-load time. The following forces the output to stdout
;; on the already-initialized configuration singleton:

(try
  (let [cp-field (.getDeclaredField org.slf4j.simple.SimpleLogger "CONFIG_PARAMS")]
    (.setAccessible cp-field true)
    (let [config (.get cp-field nil)
          oc-field (.getDeclaredField (class config) "outputChoice")]
      (.setAccessible oc-field true)
      (let [sys-out (java.lang.Enum/valueOf
                     org.slf4j.simple.OutputChoice$OutputChoiceType "SYS_OUT")
            ctor (.getDeclaredConstructor
                  org.slf4j.simple.OutputChoice
                  (into-array Class [org.slf4j.simple.OutputChoice$OutputChoiceType]))]
        (.setAccessible ctor true)
        (.set oc-field config (.newInstance ctor (object-array [sys-out]))))))
  (catch Exception _))

;; Other notebooks in this book require this namespace to
;; activate logging. In your own projects, configure your
;; preferred SLF4J backend instead.
