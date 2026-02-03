(ns scicloj.pocket.protocols
  "Shared protocol for cache key identity.
   
   Extend `PIdentifiable` to customize how your types contribute to
   Pocket's content-addressable cache keys.")

(defprotocol PIdentifiable
  (->id [this] "Return a cache key representation of this value."))

