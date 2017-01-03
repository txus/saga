(ns saga.syntax
  (:require [saga.data :as d]
            [clojure.string :as str]
            [cuerdas.core :as c])
  (:refer-clojure :exclude [not]))

;; UTIL

(defn mk-id [description]
  (keyword (c/kebab description)))

(defn- display [description]
  (if (str/ends-with? description ".")
    description
    (str description ".")))

(defn- mk-fact [description]
  {:d/id (mk-id description)
   :d/description description})

;; FACTS

(defn indeed [description]
  (assoc (mk-fact description)
         :d/negated? false))

(defn not [description]
  (assoc (mk-fact description)
         :d/negated? true))

;; LINKS

(defn leading-to [passage passage-id &{:keys [p]}]
  (update passage :d/links conj
          (merge
           {:d/id passage-id}
           (when p {:d/probability p}))))

;; CHOICES

(defn when-chose [description & consequences]
  {:d/id (mk-id description)
   :d/description (display description)
   :d/consequences (into [] consequences)})

;; PASSAGES

(defn passage [id text]
  {:d/id (mk-id id)
   :d/text text
   :d/links []
   :d/preconditions []
   :d/consequences []
   :d/choices []})

(defn requires [passage fact]
  (update passage :d/preconditions conj fact))

(defn entails [passage consequence]
  (update passage :d/consequences conj consequence))

(defn choices [passage & choices]
  (update passage :d/choices #(into % choices)))
