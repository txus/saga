(ns saga.debug
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [saga.engine :as e]
            [saga.editor :as editor]
            [saga.parser :refer [mutate]]
            [saga.player :as player]))

(enable-console-print!)

(defn mk-title [{:keys [d/description d/negated?]}]
  (str description (when negated? " (not)")))

(defn fact-id [{:keys [d/id d/negated?]}]
  [id negated?])

(defui Fact
  Object
  (render [this]
          (let [{:keys [d/id d/description d/negated?] :as fact} (om/props this)
                {:keys [hovering]} (om/get-state this)
                {:keys [why]} (om/get-computed this)]
            (dom/li #js {:className (str "mdl-list__item fact " (if negated? "negative" "positive"))}
                    (dom/span #js {:className "mdl-list__item-primary-content"}
                              (mk-title fact)
                              (when hovering
                                (dom/div #js {:className "why-explanation"}
                                         why)))
                    (dom/span #js {:className "mdl-list__item-secondary-content"}
                              (dom/a #js {:className "mdl-list__item-secondary-action why-link"
                                          :onClick (fn [_]
                                                     (println "WHY?" id))}
                                     (dom/i #js {:className "material-icons"
                                                 :onMouseOver (fn []
                                                                (om/set-state! this {:hovering true}))
                                                 :onMouseOut (fn []
                                                                (om/set-state! this {}))}
                                            "help_outline")))))))
                                     

(def fact-view (om/factory Fact {:keyfn (juxt :d/id :d/negated?)}))

(defn why? [path fact]
  (reduce
   (fn [acc {:keys [d/id d/chose d/consequences d/choices]}]
     (let [fid (fact-id fact)
           chosen-consequences (when chose
                                 (map :d/fact
                                      (:d/consequences
                                       (first
                                        (filter (fn [c]
                                                  (= (:d/id c) chose))
                                                choices)))))]
       (cond
         ((set (map fact-id (map :d/fact consequences))) fid)
         (reduced (str "A consequence of " id))

         ((set (map fact-id chosen-consequences)) fid)
         (reduced (str "A consequence of choosing " chose " in " id))
         :else
         acc)))
   "No idea..."
   path))

(defui Facts
  Object
  (render [this]
          (let [{:keys [d/path d/facts]} (om/props this)
                facts (sort-by :d/description facts)]
            (dom/div #js {:className "sidebar"}
                     (dom/header nil
                                 (dom/h5 nil "Facts"))
                     (apply dom/ul #js {:className "mdl-list facts"}
                            (map #(fact-view (om/computed % {:why (why? path %)}))
                                 facts))))))

(def facts-view (om/factory Facts))

(defui Passage
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:passage/by-id id])

  static om/IQuery
  (query [this]
         (let [fact-query (om/get-query Fact)
               consequence-query (om/get-query editor/Consequence)
               link-query (om/get-query editor/Link)
               choice-query (om/get-query editor/Choice)]
           `[:d/id
             :d/text
             {:d/links ~link-query}
             {:d/consequences ~consequence-query}
             {:d/preconditions ~fact-query}
             {:d/choices ~choice-query}]))

  Object
  (render [this]))

(defui PassageLink
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:passage/by-id id])

  static om/IQuery
  (query [this]
         [:d/id :d/chose])

  Object
  (render [this]
          (let [{:keys [d/id d/chose]} (om/props this)
                {:keys [current-passage-id]} (om/get-computed this)]
            (dom/li #js {:className (str "mdl-list__item passage-link" (when (= id current-passage-id) " selected") (when chose " with-choice"))}
                    (dom/span #js {:className "mdl-list__item-primary-content edit-passage-link"}
                              (dom/i #js {:className "material-icons"}
                                     "short_text")
                              (name id))
                    (when chose
                      (dom/span #js {:className "choice"}
                                (str chose)))))))

(def passage-link-view (om/factory PassageLink {:keyfn :d/id}))

(defui Path
  Object
  (render [this]
          (let [{:keys [d/path]} (om/props this)
                computed (om/get-computed this)
                current-passage-id (:d/id (last path))]
            (dom/div #js {:className "passage-links-sidebar sidebar"}
                     (dom/header nil (dom/h5 nil "Passages"))
                     (apply dom/ul #js {:id "passage-links" :className "mdl-list"}
                            (map (comp passage-link-view
                                    #(om/computed % (assoc computed :current-passage-id current-passage-id)))
                                 path))))))

(def path-view (om/factory Path))

(defui Debugger
  static om/IQuery
  (query [this]
         (let [passage-query (om/get-query editor/EditingPassage)
               path-query (conj passage-query :d/chose)
               fact-query (om/get-query editor/Fact)]
           `[{:story [:story/id]}
             {:d/facts ~fact-query}
             {:d/path ~path-query}
             {:d/passages ~passage-query}]))

  Object
  (render [this]
          (let [{:keys [d/facts d/path d/passages] :as props} (om/props this)
                transact! #(om/transact! this %)]
            (dom/div #js {:className "mdl-grid"}
                     (dom/div #js {:className "mdl-cell mdl-cell--3-col"}
                              (path-view props))
                     (dom/div #js {:className "mdl-cell mdl-cell--6-col"}
                              (player/playing-story-view (om/computed {:d/path path}
                                                                      {:transact! transact!})))
                     (dom/div #js {:className "mdl-cell mdl-cell--3-col"}
                              (facts-view props))))))

(def view (om/factory Debugger))

(defmethod mutate 'world/make-choice
  [{:keys [state]} _ {:keys [choice]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (let [denormalized (om/db->tree (om/get-query Debugger) (-> st :debugger) st)
                    next-world (e/next denormalized choice)
                    {:keys [d/facts d/path passage/by-id]} (om/tree->db Debugger next-world true)]
                (-> st
                    (update :debugger assoc :d/facts (mapv (fn [f] [:fact/by-id (fact-id f)])
                                                           facts))
                    (update :debugger assoc :d/path path)
                    (update :passage/by-id merge by-id))))))})
