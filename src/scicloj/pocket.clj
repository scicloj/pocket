(ns scicloj.pocket
  "Filesystem-based caching for expensive computations.
   
   Pocket provides content-addressable caching with automatic serialization
   using Nippy. Cached values are stored in a filesystem cache directory
   and keyed by the hash of function + arguments.
   
   Features:
   - Automatic serialization/deserialization (Nippy)
   - Content-addressable storage (SHA-1 based paths)
   - Lazy evaluation (deref to compute)
   - Extensible identity protocol (PIdentifiable)
   - Nil value support
   - Thread-safe reads (writes have check-then-write race condition)
   
   Example:
   
     (require '[scicloj.pocket :as pocket])
     
     ;; Set cache directory (or use POCKET_BASE_CACHE_DIR env var)
     (alter-var-root #'pocket/*base-cache-dir* (constantly \"/tmp/my-cache\"))
     
     ;; Create cached computation (lazy)
     (def expensive-result
       (pocket/cached expensive-function arg1 arg2))
     
     ;; Force computation (or load from cache)
     @expensive-result
     
     ;; Or use cached-fn wrapper
     (def cached-expensive (pocket/cached-fn expensive-function))
     @(cached-expensive arg1 arg2)"
  (:require [scicloj.pocket.impl.cache :as impl]))

(def ^:dynamic *base-cache-dir*
  "Base directory for cache storage.
   Can be set via POCKET_BASE_CACHE_DIR environment variable
   or altered with alter-var-root."
  (System/getenv "POCKET_BASE_CACHE_DIR"))

(defprotocol PIdentifiable
  "Protocol for computing cache keys from values.
   Extend this protocol to customize how your types are cached."
  (->id [this] "Return a cache key representation of this value"))

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
  "Create a cached computation (returns IDeref).
   
   The computation is executed on first deref and cached to disk.
   Subsequent derefs load from cache if available.
   
   Args:
     func - Function to cache (must be a Var for identity)
     args - Arguments to pass to func
   
   Returns:
     Cached object implementing IDeref
   
   Example:
     (def result (cached #'expensive-fn arg1 arg2))
     @result  ; computes or loads from cache"
  [func & args]
  (apply impl/cached *base-cache-dir* func args))

(defn cached-fn
  "Wrap a function to automatically cache its results.
   
   Returns a new function that wraps calls in cached + deref.
   
   Args:
     f - Function to wrap (must be a Var)
   
   Returns:
     Wrapped function that returns cached IDeref values
   
   Example:
     (def cached-expensive (cached-fn #'expensive-function))
     @(cached-expensive arg1 arg2)"
  [f]
  (impl/cached-fn *base-cache-dir* f))

(defn maybe-deref
  "Deref if x is IDeref, otherwise return x as-is.
   
   Useful for code that may receive cached or uncached values."
  [x]
  (impl/maybe-deref x))

;; Internal functions (exposed for advanced usage)
(defn read-cached
  "Read value from cache path. Returns nil if not found."
  [path]
  (impl/read-cached path))

(defn write-cached!
  "Write value to cache path."
  [v path]
  (impl/write-cached! v path))
