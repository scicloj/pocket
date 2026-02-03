(ns scicloj.pocket
  "Filesystem-based caching for expensive computations.
   
   Primary API: `cached`, `caching-fn`, `maybe-deref`.
   Configuration: `*base-cache-dir*`, `*mem-cache-options*`, `set-base-cache-dir!`, `set-mem-cache-options!`.
   Invalidation: `invalidate!`, `invalidate-fn!`, `cleanup!`.
   Introspection: `cache-entries`, `cache-stats`, `dir-tree`.
   
   See `*base-cache-dir*` and `*mem-cache-options*` for configuration precedence."
  (:require [scicloj.pocket.impl.cache :as impl]
            [scicloj.pocket.protocols :as protocols]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]))

(def ^:dynamic *base-cache-dir*
  "Base directory for cache storage.
   
   Resolved with precedence: binding > `set-base-cache-dir!` > 
   `POCKET_BASE_CACHE_DIR` env var > `pocket.edn` `:base-cache-dir` > `pocket-defaults.edn` (library default: `.cache/pocket`)."
  nil)

(def ^:dynamic *mem-cache-options*
  "In-memory cache configuration options.
   
   Resolved with precedence: binding > `set-mem-cache-options!` >
   `POCKET_MEM_CACHE` env var > `pocket.edn` `:mem-cache` >
   `pocket-defaults.edn` (library defaults).
   
   **Caution**: binding this var reconfigures the shared global mem-cache,
   which affects all threads. Useful for test fixtures but not for
   concurrent production use with different policies."
  nil)

(defn- resolve-base-cache-dir
  "Resolve the base cache directory using the precedence chain."
  []
  (or *base-cache-dir*
      (System/getenv "POCKET_BASE_CACHE_DIR")
      (:base-cache-dir (impl/pocket-edn))
      (:base-cache-dir @impl/pocket-defaults-edn)))

(defn- resolve-mem-cache-options
  "Resolve mem-cache options using the precedence chain."
  []
  (or *mem-cache-options*
      (some-> (System/getenv "POCKET_MEM_CACHE") edn/read-string)
      (:mem-cache (impl/pocket-edn))
      (:mem-cache @impl/pocket-defaults-edn)))

(defn config
  "Return the effective resolved configuration as a map.
   Useful for inspecting which cache directory and mem-cache policy
   are in effect after applying the precedence chain."
  []
  {:base-cache-dir (resolve-base-cache-dir)
   :mem-cache (resolve-mem-cache-options)})

(defn set-base-cache-dir!
  "Set the base cache directory by altering `*base-cache-dir*`.
   Returns the directory path."
  [dir]
  (alter-var-root #'*base-cache-dir* (constantly dir))
  (log/info "Cache dir set to:" dir)
  dir)

(def PIdentifiable
  "Protocol for computing cache key identity from values.
   Extend this protocol to customize how your types contribute to cache keys.
   Default implementations are provided for `Var`, `MapEntry`, `Object`, and `nil`."
  protocols/PIdentifiable)

(defn ->id
  "Return a cache key representation of a value.
   Dispatches via the `PIdentifiable` protocol."
  [x]
  (protocols/->id x))

(defn cached
  "Create a cached computation (returns `IDeref`).
   
   The computation is executed on first `deref` and cached to disk.
   Subsequent derefs load from cache if available.
   
   `func` must be a var (e.g., `#'my-fn`) for stable cache keys."
  [func & args]
  (impl/ensure-mem-cache! (resolve-mem-cache-options))
  (apply impl/cached (resolve-base-cache-dir) func args))

(defn caching-fn
  "Wrap a function to automatically cache its results.
   
   Returns a new function where each call returns a `Cached` object (`IDeref`).
   Deref the result to trigger computation or load from cache.
   `f` must be a var (e.g., `#'my-fn`) for stable cache keys."
  [f]
  (fn [& args]
    (apply cached f args)))

(defn maybe-deref
  "Deref if `x` implements `IDeref`, otherwise return `x` as-is.
   
   Useful in pipeline functions that may receive either `Cached` or plain values."
  [x]
  (impl/maybe-deref x))

(defn cleanup!
  "Delete the cache directory, removing all cached values.
   Also clears the in-memory cache.
   Returns a map with `:dir` and `:existed` indicating what happened."
  []
  (let [dir (resolve-base-cache-dir)
        existed? (and dir (fs/exists? dir))]
    (when existed?
      (fs/delete-tree dir))
    (impl/clear-mem-cache!)
    (log/info "Cache cleanup:" dir)
    {:dir dir
     :existed (boolean existed?)}))

(defn invalidate!
  "Invalidate a specific cached computation, removing it from both disk and memory.
   Takes the same arguments as `cached`: a function var and its arguments.
   Returns a map with `:path` and `:existed`."
  [func & args]
  (impl/ensure-mem-cache! (resolve-mem-cache-options))
  (impl/invalidate! (resolve-base-cache-dir) func args))

(defn invalidate-fn!
  "Invalidate all cached entries for a given function var, regardless of arguments.
   Removes matching entries from both disk and memory.
   Returns a map with `:fn-name`, `:count`, and `:paths`."
  [func]
  (impl/ensure-mem-cache! (resolve-mem-cache-options))
  (impl/invalidate-fn! (resolve-base-cache-dir) func))

(defn set-mem-cache-options!
  "Configure the in-memory cache. Resets it, discarding any currently cached values.
   
   Supported keys:
   - `:policy` — `:lru` (default), `:fifo`, `:lu`, `:ttl`, `:lirs`, `:soft`, or `:basic`
   - `:threshold` — max entries for `:lru`, `:fifo`, `:lu` (default 256)
   - `:ttl` — time-to-live in ms for `:ttl` policy (default 30000)
   - `:s-history-limit` / `:q-history-limit` — for `:lirs` policy"
  [opts]
  (alter-var-root #'*mem-cache-options* (constantly opts))
  (log/info "Mem-cache options set:" opts)
  (impl/reset-mem-cache! opts))

(defn cache-entries
  "Scan the cache directory and return a sequence of metadata maps.
   Each entry contains `:path`, `:id`, `:fn-name`, `:args-str`, and `:created-at`
   (when metadata is available — entries cached before metadata support will
   only have `:path`).
   Optionally filter by function name."
  ([]
   (impl/cache-entries (resolve-base-cache-dir)))
  ([fn-name]
   (impl/cache-entries (resolve-base-cache-dir) fn-name)))

(defn cache-stats
  "Return aggregate statistics about the cache.
   Returns a map with `:total-entries`, `:total-size-bytes`,
   and `:entries-per-fn`."
  []
  (impl/cache-stats (resolve-base-cache-dir)))

(defn dir-tree
  "Render the cache directory as a tree string, like the Unix `tree` command.
   Shows the hierarchical structure of cached entries on disk."
  []
  (let [base-dir (resolve-base-cache-dir)
        dir (when base-dir (str base-dir "/.cache"))]
    (when (and dir (fs/exists? dir))
      (impl/dir-tree dir))))
