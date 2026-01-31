(ns scicloj.pocket
  "Filesystem-based caching for expensive computations.
   
   Pocket provides content-addressable caching with automatic serialization
   using Nippy. Cached values are stored in a filesystem cache directory
   and keyed by the hash of function + arguments.
   
   Features:
   - Automatic serialization/deserialization (Nippy)
   - Content-addressable storage (SHA-1 based paths)
   - Lazy evaluation (deref to compute)
   - Extensible identity protocol (`PIdentifiable`)
   - Nil value support
   - Thread-safe reads (writes have check-then-write race condition)"
  (:require [scicloj.pocket.impl.cache :as impl]
            [babashka.fs :as fs]))

(def ^:dynamic *base-cache-dir*
  "Base directory for cache storage.
   Can be set via the `POCKET_BASE_CACHE_DIR` environment variable
   or programmatically with `set-base-cache-dir!`."
  (System/getenv "POCKET_BASE_CACHE_DIR"))

(defn set-base-cache-dir!
  "Set the base cache directory by altering `*base-cache-dir*`."
  [dir]
  (alter-var-root #'*base-cache-dir* (constantly dir)))

(defprotocol PIdentifiable
  "Protocol for computing cache keys from values.
   Extend this protocol to customize how your types contribute to cache keys.
   Default implementations are provided for `Var`, `MapEntry`, `Object`, and `nil`."
  (->id [this] "Return a cache key representation of this value."))

;; Re-export protocol extensions
(extend-protocol PIdentifiable
  clojure.lang.MapEntry
  (->id [v] (impl/->id v))

  clojure.lang.Var
  (->id [this] (impl/->id this))

  Object
  (->id [this] (impl/->id this))

  nil
  (->id [_] (impl/->id nil)))

(defn cached
  "Create a cached computation (returns `IDeref`).
   
   The computation is executed on first `deref` and cached to disk.
   Subsequent derefs load from cache if available.
   
   `func` must be a var (e.g., `#'my-fn`) for stable cache keys."
  [func & args]
  (apply impl/cached *base-cache-dir* func args))

(defn cached-fn
  "Wrap a function to automatically cache its results.
   
   Returns a new function that wraps calls in `cached`.
   `f` must be a var (e.g., `#'my-fn`) for stable cache keys."
  [f]
  (impl/cached-fn *base-cache-dir* f))

(defn maybe-deref
  "Deref if `x` implements `IDeref`, otherwise return `x` as-is.
   
   Useful in pipeline functions that may receive either `Cached` or plain values."
  [x]
  (impl/maybe-deref x))

;; Internal functions (exposed for advanced usage)
(defn read-cached
  "Read a cached value from the given `path`. Returns `nil` if not found."
  [path]
  (impl/read-cached path))

(defn write-cached!
  "Write value `v` to the given cache `path`."
  [v path]
  (impl/write-cached! v path))

(defn cleanup!
  "Delete the cache directory at `*base-cache-dir*`, removing all cached values.
   Returns a map with `:dir` and `:existed` indicating what happened."
  []
  (let [dir *base-cache-dir*
        existed? (and dir (fs/exists? dir))]
    (when existed?
      (fs/delete-tree dir))
    {:dir dir
     :existed (boolean existed?)}))
