(ns saga.engine
  (:require [clojure.set :as set]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.alpha :as s]
            [plumbing.core :refer [map-vals]]
            [saga.data :as d])
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

(defn- find-choice [choices choice-id]
  (first (filter (comp (partial = choice-id) :d/id) choices)))

(defn- choose [choice-id choices]
  (if-let [choice (-> choices (find-choice choice-id))]
    choice
    (throw (ex-info "Choice is not available."
                    {:available-choices choices
                     :chose choice-id}))))

(defn weighted [m]
  (let [m (map-vals (partial * 100) m)
        w (reductions + (vals m))
        r (rand-int (last w))]
    (nth (keys m) (count (take-while #( <= % r ) w)))))

(defn probabilistic-choice [links]
  (weighted
   (reduce
    (fn [acc {:keys [d/id d/probability]}]
      (assoc acc id probability))
    {}
    links)))

(defn format-links [links]
  (let [links-with-defined-probabilities (filter :d/probability links)
        links-without-defined-probabilities (remove :d/probability links)
        total-defined-probability (reduce + (map :d/probability links-with-defined-probabilities))]
    (cond
      (> total-defined-probability 1.0)
      (throw (ex-info "Defined probabilities add up to more than 100%:" {:probability total-defined-probability
                                                                         :links-with-defined-probabilities links-with-defined-probabilities}))

      (= total-defined-probability 1.0)
      links-with-defined-probabilities

      :else
      (let [remaining-probability (- 1.0 total-defined-probability)
            divided (/ remaining-probability (count links-without-defined-probabilities))]
        (if (zero? (count links-without-defined-probabilities))
          (conj links-with-defined-probabilities {:d/id ::nothing :d/probability remaining-probability})
          (concat
           links-with-defined-probabilities
           (map (fn [l] (assoc l :d/probability divided))
                links-without-defined-probabilities)))))))

(defn next-passage-from-links [links all-passages]
  (let [formatted-links (format-links links)
        choice (probabilistic-choice formatted-links)]
    (if (= choice ::nothing)
      nil
      (first (filter (comp (partial = choice) :d/id) all-passages)))))

(defn next-passage-from-constraints [current path all-passages facts]
  (let [possible-passages
        (filter
         (fn [{:keys [d/preconditions d/id] :as passage}]
           (and (apply not= (map :d/id [current passage]))
                (not (contains? (set (map :d/id path)) id))
                (set/subset? preconditions facts)))
         all-passages)]
    (->> possible-passages shuffle first)))

(defn next-passage* [current path all-passages facts]
  (if-let [links (seq (:d/links current))]
    (if-let [from-link (next-passage-from-links links all-passages)]
      from-link
      (next-passage-from-constraints current path all-passages facts))
    (next-passage-from-constraints current path all-passages facts)))

(defn determine-consequences [cs]
  (reduce
   (fn [acc {:keys [d/fact d/probability] :or {probability 1.0}}]
     (cond-> acc
       (< (rand) probability)
       (conj fact)))
   #{}
   cs))

(defn next
  ([world] (next world nil))
  ([{:keys [d/passages d/facts d/path] :as world} choice-id?]
   (let [current (last path)
         facts (set/union (set facts) (determine-consequences (:d/consequences current)))
         new-facts (if-not choice-id?
                     facts
                     (reconcile facts (-> (choose choice-id? (:d/choices current)) :d/consequences determine-consequences)))
         next-passage (next-passage* current path passages new-facts)]
     (if (or (nil? next-passage)
             (some (comp (partial = :the-end) :d/id) facts)
             (= next-passage current))
       world
       (let [next-world (assoc world
                               :d/facts new-facts
                               :d/path (-> path
                                           butlast
                                           vec
                                           (conj
                                            (cond-> (last path) choice-id? (assoc :d/chose choice-id?))
                                            next-passage)))]
         (if (empty? (:d/choices next-passage))
           (next next-world)
           next-world))))))
