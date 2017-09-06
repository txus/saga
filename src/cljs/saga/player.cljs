(ns saga.player
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [saga.data :as d]
            [saga.persistence :as p]
            [saga.engine :as e]
            [saga.upload :as upload]
            [saga.persistence :as persistence]
            [saga.syntax :as s]
            [om.util :as u]
            [plumbing.core :refer [map-vals]]
            [om.dom :as dom]
            [clojure.string :as str]))

(enable-console-print!)

(defn log [& xs]
  (js/console.log (apply str (interpose " - " (map pr-str xs)))))

(defmulti read om/dispatch)

(defmethod read :story/title
  [{:keys [state query] :as env} k params]
  (let [st @state]
    {:value (get st :story/title)}))

(defmethod read :default
  [{:keys [state query] :as env} k params]
  (let [st @state]
    (if (u/ident? (k st))
      {:value (get-in st (k st))}
      {:value (om/db->tree query (k st) st)})))

(declare save-state!)

(defmulti mutate om/dispatch)

(def init-data
  {:story/title "No story loaded"
   :d/facts #{}
   :d/path [{:d/id :no-story}]
   :d/passages
   [(s/passage :no-story "Load a story to begin.")]})

(defui Choices
  Object
  (render [this]
          (let [{:keys [choices]} (om/props this)
                {:keys [choose!]} (om/get-computed this)]
            (dom/ul
             #js {:className "choices"}
             (map
              (fn [{:keys [d/id d/description]}]
                (dom/li #js {:key id :className "choice"}
                        (dom/a #js {:onClick (fn [_] (choose! id))} description)))
              choices)))))

(def choices-view (om/factory Choices))

(defui Passage
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:passage/by-id id])
  static om/IQuery
  (query [this]
         [:d/id :d/choices :d/links :d/chose :d/text :d/preconditions :d/consequences])
  Object
  (render [this]
          (let [{:keys [d/choices d/links d/text d/id last? d/chose]} (om/props this)
                {:keys [choose! last?]} (om/get-computed this)]
            (dom/div
             nil
             (dom/p nil text)
             (if last?
               (choices-view (om/computed {:choices choices}
                                          {:choose! choose!}))
               (let [historical-choice
                     (first (filter (fn [{:keys [d/id]}]
                                      (= id chose))
                                    choices))]
                 (dom/p nil (:d/description historical-choice))))))))

(def passage-view (om/factory Passage))

(defui PlayingStory
  Object
  (render [this]
          (let [{:keys [d/path]} (om/props this)
                {:keys [transact!]} (om/get-computed this)
                current-passage (last path)
                choose! #(transact! `[(world/make-choice {:choice ~%})])]
            (dom/div
             nil
             (concat
              (for [passage (butlast path)]
                (passage-view passage))
              [(passage-view (om/computed
                              current-passage
                              {:last? true
                               :choose! choose!}))])))))

(def playing-story-view (om/factory PlayingStory))

(defui Player
  static om/IQuery
  (query [this]
         (let [subquery (om/get-query Passage)]
           `[:story/title
             {:d/path ~subquery}
             {:d/passages ~subquery}]))

  Object
  (componentWillMount [this]
                      (om/update-state! this assoc :auto-save (js/setInterval #(save-state!) 5000)))
  (componentWillUnmount [this]
                        (js/clearInterval (-> this om/get-state :auto-save)))
  (render [this]
          (let [{:keys [story/title d/path d/passages] :as props} (om/props this)
                transact! (partial om/transact! this)
                story? (seq passages)]
            (dom/div #js {:className "mdl-layout mdl-js-layout player mdl-layout--fixed-header"}
                     (dom/header #js {:className "mdl-layout__header mdl-layout__header--transparent"}
                                 (dom/div #js {:className "mdl-layout__header-row"}
                                          (dom/span #js {:className "mdl-layout-title"} title)
                                          (dom/div #js {:className "mdl-layout-spacer"})
                                          (dom/nav #js {:className "mdl-navigation"}
                                                   (when story?
                                                     (dom/a #js {:className "mdl-navigation__link"
                                                                 :onClick #(om/transact! this `[(world/restart)])}
                                                            (dom/i #js {:className "material-icons"} "restore")
                                                            " Restart"))
                                                   (upload/view (om/computed {:title "Load story"} {:upload-fn (partial upload/upload-file this)})))))
                     (dom/main #js {:className "mdl-layout__content"}
                               (when story?
                                 (playing-story-view (om/computed props {:transact! transact!}))))))))

(defmethod mutate 'world/restart
  [{:keys [state] :as env} _ {:keys [story]}]
  {:action (fn []
             (swap! state
                    (fn [st]
                      (-> st
                          (assoc :d/facts #{})
                          (update :d/path (comp vec (partial take 1)))
                          (update :passage/by-id (fn [pbid]
                                                   (map-vals
                                                    (fn [v]
                                                      (dissoc v :d/chose))
                                                    pbid)))))))})

(defmethod mutate 'story/upload
  [{:keys [state] :as env} _ {:keys [story]}]
  {:action (fn []
             (swap! state
                    (fn [st]
                      (let [{:keys [d/passages story/title]} story]
                        {:story/title title
                         :d/facts #{}
                         :d/path [[:passage/by-id (:d/id (first passages))]]
                         :d/passages (mapv (fn [{:keys [d/id]}] [:passage/by-id id]) passages)
                         :passage/by-id (zipmap (map :d/id passages) passages)}))))})


(def app-state (atom
                (om/tree->db Player
                             (or (persistence/get-state "player") init-data)
                             true)))

(defn save-state! []
  (let [st @app-state
        denormalized (om/db->tree (om/get-query Player) st st)]
    (persistence/save-state "player" denormalized)))

(def reconciler
  (om/reconciler {:state app-state
                  :parser (om/parser {:read read :mutate mutate})}))

(defn init []
  (om/add-root! reconciler
                Player
                (gdom/getElement "container")))

(defmethod mutate 'world/make-choice
  [{:keys [state]} _ {:keys [choice]}]
  {:action
   (fn []
     (let [st @state
           denormalized (om/db->tree (om/get-query Player) st st)
           next-world (e/next denormalized choice)
           next-state (om/tree->db Player next-world true)]
       (reset! state next-state)))})
