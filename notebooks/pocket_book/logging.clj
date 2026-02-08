;; # Logging
;;
;; **Last modified: 2026-02-08**

(ns pocket-book.logging
  (:require
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind])
  (:import
   ;; SLF4J classes for runtime log-level control:
   [org.slf4j LoggerFactory]
   [org.slf4j.simple SimpleLogger]))

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

;; ## Internals
;;
;; `slf4j-simple` reads system properties only at class-load time.
;; In a warm REPL (or when notebooks are evaluated after loggers already
;; exist), we need reflection to change settings on the already-initialized
;; `CONFIG_PARAMS` singleton and on existing logger instances.

(defn- get-config
  "Return the slf4j-simple CONFIG_PARAMS singleton via reflection."
  []
  (let [f (.getDeclaredField SimpleLogger "CONFIG_PARAMS")]
    (.setAccessible f true)
    (.get f nil)))

(def ^:private slf4j-levels
  {:error 40 :warn 30 :info 20 :debug 10 :trace 0})

(defn set-slf4j-level!
  "Set the slf4j-simple log level via reflection.
  `level` is a keyword: :error, :warn, :info, :debug, or :trace.
  Sets both the default for future loggers and the level on
  existing Pocket logger instances."
  [level]
  (try
    (let [int-level (get slf4j-levels level)
          config (get-config)
          dl (.getDeclaredField (class config) "defaultLogLevel")
          level-field (.getDeclaredField SimpleLogger "currentLogLevel")]
      (.setAccessible dl true)
      (.setInt dl config int-level)
      (.setAccessible level-field true)
      (doseq [name ["scicloj.pocket" "scicloj.pocket.impl.cache"]]
        (.setInt level-field (LoggerFactory/getLogger name) int-level)))
    (catch Exception _)))

(defn- set-slf4j-output-stdout!
  "Force slf4j-simple to write to stdout via reflection."
  []
  (try
    (let [config (get-config)
          oc-field (.getDeclaredField (class config) "outputChoice")]
      (.setAccessible oc-field true)
      (let [sys-out (java.lang.Enum/valueOf
                     org.slf4j.simple.OutputChoice$OutputChoiceType "SYS_OUT")
            ctor (.getDeclaredConstructor
                  org.slf4j.simple.OutputChoice
                  (into-array Class [org.slf4j.simple.OutputChoice$OutputChoiceType]))]
        (.setAccessible ctor true)
        (.set oc-field config (.newInstance ctor (object-array [sys-out])))))
    (catch Exception _)))

(defn- redirect-jul-to-stdout!
  "Redirect java.util.logging (JUL) output to stdout.
  Some libraries (e.g., Tribuo) use JUL directly instead of SLF4J.
  JUL defaults to stderr, which Clay renders as `## ERR` sections."
  []
  (let [root (java.util.logging.Logger/getLogger "")
        handler (proxy [java.util.logging.StreamHandler]
                       [System/out (java.util.logging.SimpleFormatter.)]
                  (publish [record]
                    (proxy-super publish record)
                    (.flush this))
                  (close [] (.flush this)))]
    (doseq [h (.getHandlers root)]
      (.removeHandler root h))
    (.addHandler root handler)))

;; ## Setup for notebooks
;;
;; The following configures `slf4j-simple` for notebook use.
;; These properties must be set before any logging occurs.

;; Hide thread names to reduce clutter:
(System/setProperty "org.slf4j.simpleLogger.showThreadName" "false")

;; Show timestamps for each log message:
(System/setProperty "org.slf4j.simpleLogger.showDateTime" "true")

;; Use hours:minutes:seconds.milliseconds format:
(System/setProperty "org.slf4j.simpleLogger.dateTimeFormat" "HH:mm:ss.SSS")

;; Write to stdout so messages appear as `OUT` rather than `ERR`:
(System/setProperty "org.slf4j.simpleLogger.logFile" "System.out")

;; Apply stdout and debug level via reflection (works even in a warm REPL):
(set-slf4j-output-stdout!)
(set-slf4j-level! :debug)
(redirect-jul-to-stdout!)

;; Other notebooks in this book require this namespace to
;; activate logging. In your own projects, configure your
;; preferred SLF4J backend instead.
