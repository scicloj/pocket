(ns scicloj.pocket
  "Filesystem-based caching for expensive computations.
   
   Primary API: `cached`, `caching-fn`, `maybe-deref`.
   Configuration: `*base-cache-dir*`, `*mem-cache-options*`, `*storage*`, `*filename-length-limit*`,
     `set-base-cache-dir!`, `set-mem-cache-options!`, `reset-mem-cache-options!`, `set-storage!`, `set-filename-length-limit!`.
   Invalidation: `invalidate!`, `invalidate-fn!`, `cleanup!`, `clear-mem-cache!`.
   Introspection: `cache-entries`, `cache-stats`, `dir-tree`, `origin-story`, `origin-story-mermaid`.
   
   See `*base-cache-dir*`, `*mem-cache-options*`, and `*storage*` for configuration precedence."
  (:require [scicloj.pocket.impl.cache :as impl]
            [scicloj.pocket.protocols :as protocols]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [scicloj.kindly.v4.kind :as kind]))

(def ^:dynamic *base-cache-dir*
  "Base directory for cache storage.
   
   Resolved with precedence: binding > `set-base-cache-dir!` > 
   `POCKET_BASE_CACHE_DIR` env var > `pocket.edn` (project root or classpath) `:base-cache-dir` > `pocket-defaults.edn` (library default: `.cache/pocket`)."
  nil)

(def ^:dynamic *mem-cache-options*
  "In-memory cache configuration options.
   
   Resolved with precedence: binding > `set-mem-cache-options!` >
   `POCKET_MEM_CACHE` env var > `pocket.edn` (project root or classpath) `:mem-cache` >
   `pocket-defaults.edn` (library defaults).
   
   **Caution**: binding this var reconfigures the shared global mem-cache,
   which affects all threads. Useful for test fixtures but not for
   concurrent production use with different policies."
  nil)

(def ^:dynamic *storage*
  "Storage policy for cached computations: `:mem+disk`, `:mem`, or `:none`.
   
   - `:mem+disk` (default) — in-memory cache backed by disk persistence
   - `:mem` — in-memory cache only, no disk I/O
   - `:none` — no shared cache; instance-local memoization only
   
   Resolved with precedence: binding > `set-storage!` >
   `POCKET_STORAGE` env var > `pocket.edn` (project root or classpath) `:storage` >
   `pocket-defaults.edn` (library default: `:mem+disk`)."
  nil)

(def ^:dynamic *filename-length-limit*
  "Maximum cache key filename length before switching to SHA-1 hash.
   
   Default 240 is safe for Linux/macOS (255-char filename limit).
   Windows has a 260-char full path limit, so users with deep base
   directories may need lower values (e.g., 60-100).
   
   Resolved with precedence: binding > `set-filename-length-limit!` >
   `POCKET_FILENAME_LENGTH_LIMIT` env var > `pocket.edn` (project root or classpath) `:filename-length-limit` >
   `pocket-defaults.edn` (library default: 240)."
  nil)

(defn- parse-env
  "Parse an environment variable with `parse-fn`, wrapping errors with a helpful message."
  [env-name parse-fn]
  (when-let [s (System/getenv env-name)]
    (try
      (parse-fn s)
      (catch Exception e
        (throw (ex-info (str "Invalid value in " env-name " environment variable: " (pr-str s))
                        {:env-name env-name :value s}
                        e))))))

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
      (parse-env "POCKET_MEM_CACHE" edn/read-string)
      (:mem-cache (impl/pocket-edn))
      (:mem-cache @impl/pocket-defaults-edn)))

(defn- resolve-storage
  "Resolve the storage policy using the precedence chain."
  []
  (or *storage*
      (parse-env "POCKET_STORAGE" keyword)
      (:storage (impl/pocket-edn))
      (:storage @impl/pocket-defaults-edn)))

(defn- resolve-filename-length-limit
  "Resolve the filename length limit using the precedence chain."
  []
  (or *filename-length-limit*
      (parse-env "POCKET_FILENAME_LENGTH_LIMIT" parse-long)
      (:filename-length-limit (impl/pocket-edn))
      (:filename-length-limit @impl/pocket-defaults-edn)))

(defn config
  "Return the effective resolved configuration as a map.
   Useful for inspecting which cache directory, mem-cache policy,
   storage policy, and filename length limit are in effect after applying the precedence chain."
  []
  {:base-cache-dir (resolve-base-cache-dir)
   :mem-cache (resolve-mem-cache-options)
   :storage (resolve-storage)
   :filename-length-limit (resolve-filename-length-limit)})

(defn set-base-cache-dir!
  "Set the base cache directory by altering `*base-cache-dir*`.
   Returns the directory path."
  [dir]
  (alter-var-root #'*base-cache-dir* (constantly dir))
  (log/info "Cache dir set to:" dir)
  dir)

(defn set-storage!
  "Set the storage policy by altering `*storage*`.
   Valid values: `:mem+disk`, `:mem`, `:none`.
   Returns the storage policy."
  [storage]
  (alter-var-root #'*storage* (constantly storage))
  (log/info "Storage policy set to:" storage)
  storage)

(defn set-filename-length-limit!
  "Set the filename length limit by altering `*filename-length-limit*`.
   Values around 60-100 are recommended for Windows with deep base directories.
   Returns the limit."
  [limit]
  (alter-var-root #'*filename-length-limit* (constantly limit))
  (log/info "Filename length limit set to:" limit)
  limit)
(def PIdentifiable
  "Protocol for computing cache key identity from values.
   Extend this protocol to customize how your types contribute to cache keys.
   Default implementations are provided for `Var`, `MapEntry`, `Object`, and `nil`."
  protocols/PIdentifiable)

(defn ->id
  "Return a cache key representation of a value.
   Dispatches via the `PIdentifiable` protocol.
   
   For derefed `Cached` values, returns the same lightweight identity
   as the original `Cached` reference — the origin registry preserves
   the link automatically (see `cache_keys` notebook for details)."
  [x]
  (protocols/->id x))

(defn cached
  "Create a cached computation (returns `IDeref`).
   
   The computation is executed on first `deref` and cached to disk.
   Subsequent derefs load from cache if available.
   
   `func` must be a var (e.g., `#'my-fn`) or keyword (e.g., `:train`)
   for stable cache keys. Keywords are useful for extracting from
   cached maps: `(cached :train split-c)`.
   
   Storage policy is controlled by `*storage*` (see `set-storage!`).
   Use `caching-fn` with an opts map for per-function overrides."
  [func & args]
  (impl/ensure-mem-cache! (resolve-mem-cache-options))
  (apply impl/cached (resolve-base-cache-dir) (resolve-storage) (resolve-filename-length-limit) func args))

(defn caching-fn
  "Wrap a function to automatically cache its results.
   
   Returns a new function where each call returns a `Cached` object (`IDeref`).
   Deref the result to trigger computation or load from cache.
   `f` must be a var (e.g., `#'my-fn`) or keyword for stable cache keys.
   
   Optionally accepts an options map to override configuration per-function:
   - `:storage`   — `:mem+disk`, `:mem`, or `:none` (overrides `*storage*`)
   - `:cache-dir` — base cache directory (overrides `*base-cache-dir*`)
   - `:mem-cache` — in-memory cache options (overrides `*mem-cache-options*`)
   - `:filename-length-limit` — max filename length before SHA-1 fallback (overrides `*filename-length-limit*`)"
  ([f] (caching-fn f nil))
  ([f opts]
   (fn [& args]
     (binding [*storage* (or (:storage opts) *storage*)
               *base-cache-dir* (or (:cache-dir opts) *base-cache-dir*)
               *mem-cache-options* (or (:mem-cache opts) *mem-cache-options*)
               *filename-length-limit* (or (:filename-length-limit opts) *filename-length-limit*)]
       (apply cached f args)))))

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

(defn clear-mem-cache!
  "Clear all entries from the in-memory cache and the origin registry,
   without deleting the disk cache.
   The next deref of a cached value will reload from disk if available
   and re-register origin identity.
   Useful for testing scenarios that need to simulate memory eviction."
  []
  (impl/clear-mem-cache!))

(defn invalidate!
  "Invalidate a specific cached computation, removing it from both disk and memory.
   Takes the same arguments as `cached`: a function var (or keyword) and its arguments.
   Returns a map with `:path` and `:existed`."
  [func & args]
  (impl/ensure-mem-cache! (resolve-mem-cache-options))
  (impl/invalidate! (resolve-base-cache-dir) (resolve-filename-length-limit) func args))

(defn invalidate-fn!
  "Invalidate all cached entries for a given function var (or keyword), regardless of arguments.
   Removes matching entries from both disk and memory.
   Returns a map with `:fn-name`, `:count`, and `:paths`."
  [func]
  (impl/ensure-mem-cache! (resolve-mem-cache-options))
  (impl/invalidate-fn! (resolve-base-cache-dir) func))

(defn set-mem-cache-options!
  "Configure the in-memory cache. Resets it, discarding any currently cached values.
   
   Supported keys:
   - `:policy` — `:lru`, `:fifo`, `:lu`, `:ttl`, `:lirs`, `:soft`, or `:basic`
   - `:threshold` — max entries for `:lru`, `:fifo`, `:lu`
   - `:ttl` — time-to-live in ms for `:ttl` policy
   - `:s-history-limit` / `:q-history-limit` — for `:lirs` policy
   
   Defaults come from `pocket-defaults.edn`."
  [opts]
  (alter-var-root #'*mem-cache-options* (constantly opts))
  (log/info "Mem-cache options set:" opts)
  (impl/reset-mem-cache! opts))

(defn reset-mem-cache-options!
  "Reset the in-memory cache configuration to library defaults.
   Clears any options set by `set-mem-cache-options!` and reconfigures
   the mem-cache with the default policy from `pocket-defaults.edn`.
   Returns the default options."
  []
  (let [defaults (:mem-cache @impl/pocket-defaults-edn)]
    (alter-var-root #'*mem-cache-options* (constantly nil))
    (log/info "Mem-cache options reset to defaults:" defaults)
    (impl/reset-mem-cache! defaults)))

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
  "Display the cache directory structure as a tree.
   Shows the hierarchical structure of cached entries on disk.
   Returns a kindly-wrapped value (`kind/code`) for notebook rendering."
  []
  (let [base-dir (resolve-base-cache-dir)]
    (when (and base-dir (fs/exists? base-dir))
      (kind/code (impl/dir-tree base-dir)))))

(defn origin-story
  "Given a value, return its computation DAG as a nested map.

   For a `Cached` value, each node is `{:fn <var> :args [<nodes>] :id <string>}`,
   with `:value` included if the computation has been realized.
   Plain (non-Cached) arguments become `{:value <val>}` leaf nodes.
   
   When the same Cached instance appears multiple times in the tree,
   subsequent occurrences are represented as `{:ref <id>}` pointing
   to the first occurrence's `:id`. This enables proper DAG representation
   for diamond dependencies.

   Does not trigger computation — only peeks at already-realized values.
   Works with all storage policies (`:mem+disk`, `:mem`, `:none`)."
  [x]
  (impl/origin-story x))

(defn origin-story-graph
  "Given a value, return its computation DAG as a normalized graph.
   
   Returns `{:nodes {<id> <node-map>} :edges [[<from> <to>] ...]}`.
   
   Node maps contain `:fn` (for cached steps) or `:value` (for leaves),
   plus `:value` if the cached computation has been realized.
   
   This is the fully normalized (Format B) representation of the DAG.
   Use `origin-story` for the tree-with-refs representation (Format A)."
  [x]
  (impl/origin-story-graph x))

(defn origin-story-mermaid
  "Given a value, return a Mermaid flowchart string of its computation DAG.

   Accepts a `Cached` value (walks it via `origin-story`) or a tree map
   previously returned by `origin-story`.

   Returns a plain string. Wrap with `(kind/mermaid ...)` for Kindly rendering."
  [x]
  (let [tree (if (and (map? x) (or (:fn x) (contains? x :value)))
               x
               (impl/origin-story x))]
    (impl/origin-story-mermaid tree)))

(defn compare-experiments
  "Compare multiple cached experiment results.
   
   Takes a seq of `Cached` values (typically final metrics from different
   hyperparameter configurations). Walks each experiment's `origin-story`
   to extract parameter maps, identifies which parameters vary across
   experiments, and returns a seq of maps containing the varying params
   plus the experiment result.
   
   Only parameters that differ across experiments are included.
   The `:result` key contains the derefed value of each Cached."
  [cached-values]
  (impl/compare-experiments cached-values))
