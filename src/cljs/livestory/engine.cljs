(ns livestory.engine
  (:require [clojure.set :as set]
            [clojure.spec.impl.gen :as gen]
            [clojure.spec :as s]
            [livestory.data :as d])
  (:refer-clojure :exclude [next]))

(defn log [& xs]
  (js/console.log (apply str (interpose " - " (map pr-str xs)))))

(defn negate [{:keys [d/negated?] :as fact}]
  (update fact :d/negated? not))

(defn reconcile [facts new-facts]
  (try
    (let [all (set/union facts new-facts)
          contradictory (filter (comp #(> (count %) 1) val) (group-by :d/id all))]
      (if-not (empty? contradictory)
        (throw (ex-info "Cannot reconcile facts, found contradictory facts:" {:contradictory-facts contradictory}))
        all))
    (catch js/Error e
      (js/alert (pr-str e)))))

(defn next
  ([world] (next world nil))
  ([{:keys [d/passages d/facts d/path] :as world} choice?]
   (let [current (last path)
         facts (set/union facts (:d/consequences current))
         new-facts (if-not choice?
                     facts
                     (reconcile facts (-> current :d/choices (get choice?) last)))
         possible-passages (filter
                            (fn [{:keys [d/assumptions d/id] :as passage}]
                              (and (apply not= (map :d/id [current passage]))
                                   (not (contains? (set (map :d/id path)) id))
                                   (set/subset? assumptions new-facts)))
                            passages)
         next-passage (->> possible-passages shuffle first)]
     (if (or (nil? next-passage)
             (= next-passage current))
       world
       (let [next-world (assoc world
                               :d/facts new-facts
                               :d/path (-> path
                                           butlast
                                           vec
                                           (conj
                                            (cond-> (last path) choice? (assoc :d/chose choice?))
                                            next-passage)))]
         (if (empty? (:d/choices next-passage))
           (next next-world)
           next-world))))))
