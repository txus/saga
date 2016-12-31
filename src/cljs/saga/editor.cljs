(ns saga.editor
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [saga.data :as d]
            [saga.parser :as p :refer [read mutate]]
            [saga.syntax :as s]
            [om.util :as u]
            devtools.core
            [om.dom :as dom]
            [clojure.string :as str]
            [clojure.set :as set]))

(devtools.core/install! [:custom-formatters :sanity-hints])

(enable-console-print!)

(defn log [& xs]
  (js/console.log (apply str (interpose " - " (map pr-str xs)))))

(defn all-facts [passages]
  (->> passages
       (mapcat (fn [passage]
                 (let [{:keys [d/assumptions d/consequences d/choices]} passage
                       passage-facts (concat assumptions
                                             consequences
                                             (mapcat :d/consequences choices))]
                   passage-facts)))
       set))

(defmethod read :search/results
  [{:keys [state ast] :as env} k {:keys [query]}]
  (let [st @state]
    (if (empty? query)
      {:value #{}}
      {:value (into #{} (->> (get-in st (-> st :editor :story))
                             :d/passages
                             (keep (partial get-in st))
                             all-facts
                             (keep (partial get-in st))
                             (filter (fn [{:keys [d/description] :as f}]
                                       (when (seq f)
                                         (str/includes? (str/lower-case description)
                                                        (str/lower-case query)))))))})))

(defn mk-title [{:keys [d/description d/negated?]}]
  (str description (when negated? " (not)")))

(defn fact-id [{:keys [d/id d/negated?]}]
  [id negated?])

(defui Fact
  static om/Ident
  (ident [this fact]
         [:fact/by-id (fact-id fact)])

  static om/IQuery
  (query [this]
         [:d/id :d/negated? :d/description])

  Object
  (render [this]
          (let [{:keys [d/id d/description d/negated?] :as fact} (om/props this)
                {:keys [on-drag-start]} (om/get-computed this)]
            (dom/li #js {:draggable true
                         :className (str "mdl-list__item fact " (if negated? "negative" "positive"))
                         :onDragStart (fn [_] (on-drag-start (select-keys fact (om/get-query Fact))))}
                    (dom/span #js {:className "mdl-list__item-primary-content"}
                              (mk-title fact))))))

(def fact-view (om/factory Fact {:keyfn (juxt :d/id :d/negated?)}))

(defui PassageLink
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:passage/by-id id])

  static om/IQuery
  (query [this]
         [:d/id])

  Object
  (render [this]
          (let [{:keys [d/id]} (om/props this)
                {:keys [current-passage-id delete! edit!]} (om/get-computed this)]
            (dom/li #js {:className (str "mdl-list__item passage-link" (when (= id current-passage-id) " selected"))
                         :onClick #(edit! id)}
                    (dom/span #js {:className "mdl-list__item-primary-content edit-passage-link"}
                              (dom/i #js {:className "material-icons"}
                                     "short_text")
                              (name id))
                    (dom/span #js {:className "mdl-list__item-secondary-action"
                                   :onClick (fn [e]
                                              (.stopPropagation e)
                                              (.preventDefault e)
                                              (when (.confirm js/window "Are you sure?")
                                                (delete! id))
                                              false)}
                              (dom/label #js {:className "mdl-button mdl-button--icon mdl-js-button mdl-js-ripple-effect"}
                                         (dom/i #js {:className "material-icons"}
                                                "delete")))))))

(def passage-link-view (om/factory PassageLink {:keyfn :d/id}))

(defui PassageLinks
  Object
  (render [this]
          (let [{:keys [d/passages]} (om/props this)
                {:keys [new-passage] :as computed} (om/get-computed this)]
            (dom/div #js {:className "passage-links-sidebar sidebar"}
                     (dom/header nil
                                 (dom/h5 nil "Passages")
                                 (dom/button #js {:className "mdl-button mdl-js-button mdl-button--fab mdl-button--mini-fab"
                                                  :onClick (fn [_]
                                                             (when-let [id (.prompt js/window "Enter an id for your passage")]
                                                               (new-passage (s/passage id "write something cool!"))))}
                                             (dom/i #js {:className "material-icons"} "add")))
                     (apply dom/ul #js {:id "passage-links" :className "mdl-list"}
                            (map (comp passage-link-view
                                    #(om/computed % computed))
                                 passages))))))

(def passage-links-view (om/factory PassageLinks))

(defui AllFacts
  Object
  (render [this]
          (let [{:keys [facts]} (om/props this)
                computed (om/get-computed this)
                facts (sort-by :d/description facts)]
            (dom/div #js {:className "sidebar"}
                     (dom/header nil
                      (dom/h5 nil "All facts"))
                     (apply dom/ul #js {:className "mdl-list"}
                            (map #(fact-view (om/computed % computed)) facts))))))

(def all-facts-view (om/factory AllFacts))

(defui Choice
  static om/IQuery
  (query [this]
         (let [fact-query (om/get-query Fact)]
           `[:d/id :d/description {:d/consequences ~fact-query}])))

(defui EditingPassage
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:passage/by-id id])

  static om/IQuery
  (query [this]
         (let [fact-query (om/get-query Fact)
               choice-query (om/get-query Choice)]
           `[:d/id
             :d/text
             {:d/consequences ~fact-query}
             {:d/assumptions ~fact-query}
             {:d/choices ~choice-query}])))

(defn result-list [ac type results on-select]
  (dom/ul #js {:key (str type "-result-list")
               :className "result-list mdl-list"
               :id (str type "-result-list")}
          (map #(let [{:keys [d/description d/id d/negated?] :as fact} %]
                  (dom/li #js {:key (str "result-" (fact-id fact))
                               :className (str "fact " (if negated? "negative" "positive") " mdl-list__item")
                               :onClick (fn [_]
                                          (om/set-query! ac {:params {:query ""}})
                                          (on-select fact))}
                          (dom/span #js {:className "mdl-list__item-primary-content"}
                                    (mk-title fact))))
               results)))

(defui NewChoiceField
  Object
  (render [this]
          (let [{:keys [on-enter]} (om/get-computed this)
                {:keys [text]} (om/get-state this)]
            (dom/input
             #js {:key "new-choice-input"
                  :className "search-field"
                  :value (or text "")
                  :placeholder "add choice..."
                  :onKeyPress (fn [e]
                                (when (= 13 (.-charCode e))
                                  (let [desc (.. e -target -value)]
                                    (on-enter desc)
                                    (om/set-state! this {:text ""}))))
                  :onChange
                  (fn [e]
                    (om/set-state! this {:text (.. e -target -value)}))}))))

(def new-choice-field (om/factory NewChoiceField))

(defn search-field [ac type query on-select]
  (dom/input
   #js {:key (str type "-search-field")
        :className "search-field"
        :value query
        :placeholder (condp = type
                       :consequences "add consequence..."
                       :assumptions "add assumption..."
                       "add...")
        :onKeyPress (fn [e]
                      (when (= 13 (.-charCode e))
                        (let [desc (.. e -target -value)
                              fact (if (str/ends-with? desc " (not)")
                                     (s/not (str/replace desc #" \(not\)$" ""))
                                     (s/indeed desc))]
                          (om/set-query! ac {:params {:query ""}})
                          (on-select fact))))
        :onChange
        (fn [e]
          (om/set-query! ac
                         {:params {:query (.. e -target -value)}}))}))

(defui AutoCompleter
  static om/IQueryParams
  (params [_]
          {:query ""})
  static om/IQuery
  (query [_]
         '[(:search/results {:query ?query})])
  Object
  (render [this]
          (let [{:keys [search/results]} (om/props this)
                {:keys [type on-select]} (om/get-computed this)
                sorted (->> results
                            (sort-by :d/description))]
            (dom/div nil
                     (cond->
                         [(search-field this type (:query (om/get-params this)) on-select)]
                       (not (empty? sorted)) (conj (result-list this type sorted on-select)))))))

(def auto-completer (om/factory AutoCompleter))

(defui FactsList
  Object
  (render [this]
          (let [{:keys [kind title facts dragging props]} (om/props this)
                {:keys [className add! remove! delete-self!]} (om/get-computed this)]
            (dom/div (clj->js
                      (merge
                       {:className (str (or className "") " " (name kind))
                        :onDragOver (fn [e] (.preventDefault e))
                        :onDrop (fn [_]
                                  (when dragging
                                    (add! dragging)))}
                       props))
                     (auto-completer (om/computed {} {:type kind
                                                      :on-select add!}))
                     (apply dom/ul #js {:className "mdl-list"}
                            (map (fn [{:keys [d/negated?] :as fact}]
                                   (dom/li #js {:className (str "fact " (if negated? "negative" "positive") " mdl-list__item")
                                                :onClick (fn [_] (remove! (fact-id fact)))}
                                           (dom/span #js {:className "mdl-list__item-primary-content"}
                                                     (mk-title fact))))
                                 facts))))))

(def facts-list (om/factory FactsList))

(defui CurrentStory
  static om/Ident
  (ident [this {:keys [story/id]}]
         [:story/by-id id])
  static om/IQuery
  (query [this]
         (let [passages-query (om/get-query EditingPassage)]
           `[:story/id :story/title {:d/passages ~passages-query} {:story/facts ~(om/get-query Fact)}])))

(defui ChoicesView
  Object
  (render [this]
          (let [{:keys [choices passage-id dragging]} (om/props this)
                id passage-id
                {:keys [transact!]} (om/get-computed this)]
            (dom/div #js {:className "choices mdl-cell mdl-cell--12-col"}
                     (dom/h3 nil "Choices")
                     (new-choice-field (om/computed {} {:on-enter (fn [txt]
                                                                    (transact! `[(editor/add-choice {:passage-id ~id :choice ~(s/when-chose txt)})]))}))
                     (apply dom/div #js {:className "mdl-grid"}
                            (map (fn [{:keys [d/description d/consequences] :as choice}]
                                   (let [choice-id (:d/id choice)]
                                     (dom/div #js {:className "choice mdl-cell mdl-cell--6-col mdl-card mdl-shadow--2dp choice-card"
                                                   :onDragOver (fn [e] (.preventDefault e))
                                                   :onDrop (fn [_]
                                                             (when dragging
                                                               (transact! `[(editor/add-consequence-to-choice {:passage-id ~id :choice-id ~choice-id :fact ~dragging})])))}
                                              (dom/div #js {:className "mdl-card__title"}
                                                       (dom/h5 #js {:className "mdl-card__title-text"})
                                                       description)
                                              (dom/div #js {:className "mdl-card__menu"}
                                                       (dom/button #js {:className "mdl-button mdl-js-button mdl-button--icon"
                                                                        :onClick #(when (.confirm js/window "Are you sure?")
                                                                                    (transact! `[(editor/remove-choice {:passage-id ~id :choice-id ~choice-id})]))}
                                                                   (dom/i #js {:className "material-icons"} "delete")))
                                              (facts-list (om/computed
                                                           {:kind :consequences
                                                            :title ""
                                                            :dragging dragging
                                                            :facts consequences}
                                                           {:add! #(transact! `[(editor/add-consequence-to-choice {:passage-id ~id :choice-id ~choice-id :fact ~%})])
                                                            :remove! #(transact! `[(editor/remove-consequence-from-choice {:passage-id ~id :choice-id ~choice-id :fact-id ~%})])})))))
                                 (sort-by :d/id choices)))))))

(def choices-view (om/factory ChoicesView))

(defui Editing
  static om/IQuery
  (query [this]
         (let [sub (om/get-query EditingPassage)
               fact-sub (om/get-query Fact)]
           `[{:d/passage ~sub}
             {:dragging ~fact-sub}]))

  Object
  (render [this]
          (let [{:keys [d/passage dragging]} (om/props this)
                {:keys [transact! all-facts]} (om/get-computed this)
                {:keys [d/text d/id d/assumptions d/consequences d/choices]} passage]
            (if (empty? passage)
              (dom/div nil "Select an existing passage or create a new one.")
              (dom/div #js {:className "editing-view mdl-grid"}
                       (dom/textarea #js {:id "text"
                                          :className "mdl-cell mdl-cell--6-col"
                                          :rows 10
                                          :cols 10
                                          :onChange (fn [e]
                                                      (let [new-text (.. e -target -value)]
                                                        (transact! `[(editor/update-passage {:passage-id ~id :props {:d/text ~new-text}})])))
                                          :value text})
                       (dom/div #js {:className "mdl-card mdl-cell--6-col mdl-cell"}
                                (dom/div #js {:className "mdl-card__title"}
                                         (dom/h3 #js {:className "mdl-card__title-text"})
                                         "Assumptions")
                                (facts-list (om/computed
                                             {:kind :assumptions
                                              :dragging dragging
                                              :facts assumptions}
                                             {:add! #(transact! `[(editor/add-assumption {:passage-id ~id :fact ~%})])
                                              :remove! #(transact! `[(editor/remove-assumption {:passage-id ~id :fact-id ~%})])})))

                       (dom/div #js {:className "mdl-card mdl-cell--6-col mdl-cell"}
                                (dom/div #js {:className "mdl-card__title"}
                                         (dom/h3 #js {:className "mdl-card__title-text"})
                                         "Consequences")
                                (facts-list (om/computed
                                             {:kind :consequences
                                              :dragging dragging
                                              :facts consequences}
                                             {:add! #(transact! `[(editor/add-consequence {:passage-id ~id :fact ~%})])
                                              :remove! #(transact! `[(editor/remove-consequence {:passage-id ~id :fact-id ~%})])})))

                       (choices-view (om/computed {:passage-id id :choices choices :dragging dragging}
                                                  {:transact! transact!})))))))

(def editing-view (om/factory Editing))

(defui Editor
  static om/IQuery
  (query [this]
         `[{:editing ~(om/get-query Editing)}
           {:story ~(om/get-query CurrentStory)}])

  Object
  (render [this]
          (let [{:keys [editing story] :as props} (om/props this)
                {:keys [parent]} (om/get-computed this)
                facts (all-facts (:d/passages story))
                edit! (fn [id]
                        (om/transact! parent `[(editor/edit! {:id ~id})]))
                new-passage (fn [p]
                              (om/transact! this `[(editor/new-passage {:passage ~p})]))
                delete! (fn [id]
                          (om/transact! parent `[(editor/delete! {:id ~id})]))
                on-drag-start (fn [fact]
                                (om/transact! this `[(editor/dragging {:fact ~fact})]))
                transact! (partial om/transact! this)]
            (dom/div #js {:className "mdl-grid"}
                     (dom/div #js {:className "mdl-cell mdl-cell--3-col"}
                              (passage-links-view (om/computed story
                                                               {:current-passage-id (-> editing :d/passage :d/id)
                                                                :edit! edit!
                                                                :new-passage new-passage
                                                                :delete! delete!})))
                     (dom/div #js {:className "mdl-cell mdl-cell--6-col"}
                              (editing-view (om/computed editing
                                                         {:transact! transact!
                                                          :all-facts facts})))
                     (dom/div #js {:className "mdl-cell mdl-cell--3-col"}
                              (all-facts-view (om/computed {:facts facts}
                                                           {:on-drag-start on-drag-start})))))))

(def view (om/factory Editor))

(defmethod mutate 'editor/edit!
  [{:keys [state]} _ {:keys [id]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (if (-> st :passage/by-id (get id))
                (assoc-in st [:editor :editing :d/passage]
                          [:passage/by-id id])
                st))))})

(defmethod mutate 'editor/new-passage
  [{:keys [state]} _ {:keys [passage]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (let [id (:d/id passage)
                    story-id (-> st :editor :story second)]
                (-> st
                    (update :passage/by-id assoc id passage)
                    (update-in [:story/by-id story-id :d/passages] conj [:passage/by-id id])
                    (assoc-in [:editor :editing :d/passage] [:passage/by-id id]))))))})

(defmethod mutate 'editor/delete!
  [{:keys [state]} _ {:keys [id]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (println (-> st :editor :story second))
              (let [st (if (= id (-> st :editor :editing :d/passage second))
                         (update-in st [:editor :editing] dissoc :d/passage)
                         st)
                    story-id (-> st :editor :story second)
                    remove-passage (fn [passages]
                                     (vec (remove (fn [[_ pid]] (= id pid)) passages)))]
                (-> st
                    (update-in [:story/by-id story-id :d/passages] remove-passage))))))})

(defmethod mutate 'editor/dragging
  [{:keys [state]} _ {:keys [fact]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (assoc-in st [:editor :editing :dragging] fact))))})

(defn format-fact [fact]
  (select-keys fact (om/get-query Fact)))

(defn update-when [coll pred update-fn]
  (into
   (remove pred coll)
   (map update-fn (filter pred coll))))

(defn add-fact* [new-fact-id facts]
  (-> facts
      set
      (disj [:fact/by-id (update new-fact-id 1 not)])
      (conj [:fact/by-id new-fact-id])
      vec))

(defn add-fact [st passage-id ty fact]
  (let [new-fact (format-fact fact)
        new-fact-id (fact-id new-fact)]
    (-> st
        (assoc-in [:fact/by-id new-fact-id] new-fact)
        (update-in [:passage/by-id passage-id ty]
                   (partial add-fact* new-fact-id)))))

(defn remove-fact [st passage-id ty fact-id]
  (-> st
      (update :fact/by-id dissoc fact-id)
      (update-in [:passage/by-id passage-id ty]
                 (fn [facts]
                   (vec (remove (comp (partial = fact-id) second) facts))))))

(defn remove-choice [st passage-id choice-id]
  (update-in st [:passage/by-id passage-id :d/choices]
             (fn [choices]
               (vec (remove (comp (partial = choice-id) :d/id) choices)))))

(defmethod mutate 'editor/add-assumption
  [{:keys [state]} _ {:keys [passage-id fact]}]
  {:action
   (fn []
     (swap! state add-fact passage-id :d/assumptions fact))})

(defmethod mutate 'editor/remove-assumption
  [{:keys [state]} _ {:keys [passage-id fact-id]}]
  {:action
   (fn []
     (swap! state remove-fact passage-id :d/assumptions fact-id))})

(defmethod mutate 'editor/add-consequence
  [{:keys [state]} _ {:keys [passage-id fact]}]
  {:action
   (fn []
     (swap! state add-fact passage-id :d/consequences fact))})

(defmethod mutate 'editor/remove-consequence
  [{:keys [state]} _ {:keys [passage-id fact-id]}]
  {:action
   (fn []
     (swap! state remove-fact passage-id :d/consequences fact-id))})

(defmethod mutate 'editor/add-choice
  [{:keys [state]} _ {:keys [passage-id choice]}]
  {:action
   (fn []
     (swap! state update-in [:passage/by-id passage-id :d/choices] conj choice))})

(defmethod mutate 'editor/remove-choice
  [{:keys [state]} _ {:keys [passage-id choice-id]}]
  {:action
   (fn []
     (swap! state remove-choice passage-id choice-id))})

(defn update-choice [st passage-id choice-id f]
  (update-in st [:passage/by-id passage-id :d/choices]
             (fn [choices]
               (into []
                     (update-when choices #(= (:d/id %) choice-id) f)))))

(defmethod mutate 'editor/add-consequence-to-choice
  [{:keys [state]} _ {:keys [passage-id choice-id fact]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (-> st
                  (assoc-in [:fact/by-id (fact-id fact)] (format-fact fact))
                  (update-choice passage-id choice-id
                                 (fn [choice]
                                   (update choice :d/consequences
                                           (partial add-fact* (fact-id fact)))))))))})

(defmethod mutate 'editor/remove-consequence-from-choice
  [{:keys [state] :as env} _ {:keys [passage-id choice-id fact-id]}]
  {:action
   (fn []
     (swap! state update-choice passage-id choice-id
            (fn [choice]
              (update choice :d/consequences (fn [conseqs]
                                               (into [] (remove (comp (partial = fact-id) second) conseqs)))))))})

(defmethod mutate 'editor/update-passage
  [{:keys [state] :as env} _ {:keys [passage-id props]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (update-in st [:passage/by-id passage-id]
                         #(merge % props)))))})
