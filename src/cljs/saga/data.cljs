(ns saga.data
  (:require
   [clojure.spec.impl.gen :as gen]
   [clojure.spec :as s]))

(s/def ::id uuid?)

(s/def ::passage (s/keys :req [::text
                               ::preconditions
                               ::consequences
                               ::links
                               ::choices
                               ::id]))

(s/def ::fact (s/keys :req [::id ::negated? ::description]))
(s/def ::facts (s/coll-of ::fact :kind set?))

(s/def ::link (s/keys :req [::id] :opt [::probability]))
(s/def ::links (s/coll-of ::link :kind set?))

(s/def ::negated? boolean?)

(s/def ::preconditions (s/coll-of ::fact :kind set?))
(s/def ::consequences (s/coll-of ::fact :kind set?))

(s/def ::choice (s/keys :req [::id ::description ::consequences]))
(s/def ::choices (s/coll-of ::choice))

(s/def ::key uuid?)
(s/def ::text string?)
(s/def ::description string?)

(s/def ::passages (s/coll-of ::passage :kind set?))
(s/def ::current-passage ::passage)
(s/def ::path (s/coll-of ::passage :kind vector?))

(s/def ::world (s/keys
                :req [::passages
                      ::current-passage
                      ::facts
                      ::path]))
