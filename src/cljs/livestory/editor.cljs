(ns livestory.editor
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [livestory.data :as d]
            [livestory.syntax :as s]
            [om.util :as u]
            devtools.core
            [om.dom :as dom]
            [clojure.string :as str]
            [clojure.set :as set]))

(devtools.core/install! [:custom-formatters :sanity-hints])

(enable-console-print!)

(defn log [& xs]
  (js/console.log (apply str (interpose " - " (map pr-str xs)))))

(defmulti read om/dispatch)

(defn- all-facts [st]
  (->> (:d/passages st)
       (map (fn [path]
              (let [{:keys [d/assumptions d/consequences d/choices]} (get-in st path)]

                (apply set/union
                       (set assumptions)
                       (set consequences)
                       (map last (vals choices))))))
       (apply set/union)))

(defmethod read :all-facts
  [{:keys [state query] :as env} k params]
  (let [st @state]
    {:value (all-facts st)}))

(defmethod read :search/results
  [{:keys [state ast] :as env} k {:keys [query]}]
  (let [st @state]
    (if (empty? query)
      {:value #{}}
      {:value (into #{} (filter (fn [{:keys [d/description]}]
                                  (str/includes? (str/lower-case description)
                                                 (str/lower-case query)))
                                (all-facts st)))})))

(defmethod read :default
  [{:keys [state query] :as env} k params]
  (let [st @state]
    {:value (om/db->tree query (get st k) st)}))

(defmulti mutate om/dispatch)

(def init-data
  {:editing
   {:d/passage {:d/id :in-the-building}}
   :d/passages
   [(-> (s/passage :in-the-building
                 "It was raining outside. The street was soaking wet.")
        (s/entails (s/indeed "went out to the street"))
        (s/choices
         (s/when-chose "I went out without an umbrella"
           (s/not "I have an umbrella"))
         (s/when-chose "I took an umbrella"
           (s/indeed "I have an umbrella"))))

    (-> (s/passage :crossing-the-street
                   "I crossed the street and got in the library.")
        (s/assumes (s/indeed "went out to the street"))
        (s/entails (s/indeed "went into the library")))

    (-> (s/passage :in-the-library-without-umbrella
                   "As I was entering, the security guards turned me away. I guess they didn't want their books ruined...")
        (s/assumes (s/indeed "went into the library"))
        (s/assumes (s/not "I have an umbrella"))
        (s/entails (s/indeed "got rejected from the library")))

    (-> (s/passage :in-the-library
                   "As I was entering, the librarian greeted me: 'Hello John! Are you here to return the book? You've had it for a while.'")
        (s/assumes (s/indeed "went into the library"))
        (s/assumes (s/indeed "I have an umbrella"))
        (s/entails (s/indeed "got prompted to return the book"))
        (s/choices
         (s/when-chose "I approached the counter and took the book out of my bag. He seemed happy."
           (s/indeed "returned the book"))
         (s/when-chose "I made up an excuse to keep the book a bit longer and told him I just wanted to browse."
           (s/not "returned the book"))))

    (-> (s/passage :browsing-without-returning-the-book
                   "I spent a while browsing. As I was trying to reach onto the top shelf, my book came out of my bag. An old man looked at it with disapproval.")
        (s/assumes (s/not "returned the book"))
        (s/assumes (s/indeed "got prompted to return the book"))
        (s/entails (s/indeed "old man disapproved me")))

    (-> (s/passage :browsing-having-returned-the-book
                   "I spent a while browsing. I reached out for the second part of the series in a top shelf, found the gun inside and left.")
        (s/assumes (s/indeed "returned the book"))
        (s/assumes (s/indeed "got prompted to return the book"))
        (s/entails (s/indeed "the end")))

    (-> (s/passage :browsing-without-returning-the-book-2
                   "I tried to find an excuse he'd understand but the truth is I felt ashamed and left.")
        (s/assumes (s/indeed "old man disapproved me"))
        (s/entails (s/indeed "the end")))]})

(defui SidebarFact
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:fact/by-id id])

  static om/IQuery
  (query [this]
         [:d/id :d/negated? :d/description])

  Object
  (render [this]
          (let [{:keys [d/id d/description d/negated?]} (om/props this)]
            (dom/li nil
                    (str description)))))

(def sidebar-fact-view (om/factory SidebarFact {:keyfn (comp str (juxt :d/id :d/negated?))}))

(defui SidebarPassage
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:passage/by-id id])

  static om/IQuery
  (query [this]
         [:d/id])

  Object
  (render [this]
          (let [{:keys [d/id]} (om/props this)
                {:keys [edit!]} (om/get-computed this)]
            (dom/li #js {:onClick #(edit! id)} (str id)))))

(def sidebar-passage-view (om/factory SidebarPassage {:keyfn :d/id}))

(defui SidebarPassages
  Object
  (render [this]
          (let [{:keys [d/passages]} (om/props this)
                computed (om/get-computed this)]
            (dom/div nil
                     (dom/h3 nil "Passages")
                     (apply dom/ul #js {:id "sidebar-passages"}
                            (map (comp sidebar-passage-view
                                    #(om/computed % computed))
                                 passages))))))

(def sidebar-passages-view (om/factory SidebarPassages))

(defui SidebarFacts
  Object
  (render [this]
          (let [{:keys [all-facts]} (om/props this)
                all-facts (sort-by :d/description all-facts)]
            (dom/div nil
                     (dom/h3 nil "Facts")
                     (apply dom/ul #js {:id "sidebar-facts"}
                            (map sidebar-fact-view all-facts))))))

(def sidebar-facts-view (om/factory SidebarFacts))

(defui EditingPassage
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:passage/by-id id])

  static om/IQuery
  (query [this]
         (let [fact-query (om/get-query SidebarFact)]
           `[:d/id
             :d/text
             {:d/consequences ~fact-query}
             {:d/assumptions ~fact-query}])))

(defn result-list [ac type results on-select]
  (dom/ul #js {:key (str type "-result-list")
               :className "result-list"
               :id (str type "-result-list")}
          (map #(let [{:keys [d/description d/id] :as fact} %]
                  (dom/li #js {:onClick (fn [_]
                                          (om/set-query! ac {:params {:query ""}})
                                          (on-select fact))}
                          description))
               results)))

(defn search-field [ac type query on-select]
  (dom/input
   #js {:key (str type "-search-field")
        :className "search-field"
        :value query
        :placeholder "add..."
        :onKeyPress (fn [e]
                      (when (= 13 (.-charCode e))
                        (om/set-query! ac {:params {:query ""}})
                        (on-select (s/indeed (.. e -target -value)))))
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

(defui Editing
  static om/IQuery
  (query [this]
         (let [sub (om/get-query EditingPassage)]
           `[{:d/passage ~sub}]))

  Object
  (render [this]
          (let [{:keys [d/passage]} (om/props this)
                {:keys [update-passage! all-facts]} (om/get-computed this)
                {:keys [d/text d/id d/assumptions d/consequences]} passage]
            (dom/div nil
                     (dom/textarea #js {:id "text"
                                        :rows 10
                                        :cols 10
                                        :onChange (fn [e]
                                                    (let [new-text (.. e -target -value)]
                                                      (update-passage! id {:d/text new-text})))
                                        :value text})
                     (dom/div #js {:className "assumptions"}
                              (dom/h3 nil "Assumptions")
                              (auto-completer (om/computed {} {:type :assumptions
                                                               :on-select (fn [fact]
                                                                            (update-passage! id {:alter {:add-assumption fact}}))}))
                              (apply dom/ul nil
                                     (map #(dom/li nil (:d/description %)) assumptions)))

                     (dom/div #js {:className "consequences"}
                              (dom/h3 nil "Consequences")
                              (auto-completer (om/computed {} {:type :consequences
                                                               :on-select (fn [fact]
                                                                            (update-passage! id {:alter {:add-consequence fact}}))}))
                              (apply dom/ul nil
                                     (map #(dom/li nil (:d/description %)) consequences)))

                              ))))

(def editing-view (om/factory Editing))

(defui Editor
  static om/IQuery
  (query [this]
         (let [subquery (om/get-query SidebarPassage)
               editing-subquery (om/get-query Editing)
               fact-subquery (om/get-query SidebarFact)]
           `[{:d/passages ~subquery}
             {:editing ~editing-subquery}
             {:all-facts ~fact-subquery}]))

  Object
  (render [this]
          (let [{:keys [editing all-facts] :as props} (om/props this)
                edit! (fn [id]
                        (om/transact! this `[(editor/edit! {:id ~id})]))
                update-passage! (fn [id props]
                                  (om/transact! this `[(editor/update-passage {:id ~id :props ~props})]))]
            (dom/div nil
                     (dom/div #js {:id "left-sidebar" :className "sidebar"}
                              (sidebar-passages-view (om/computed props
                                                                  {:edit! edit!})))
                     (dom/div #js {:id "editing"}
                              (editing-view (om/computed editing
                                                         {:update-passage! update-passage!
                                                          :all-facts all-facts})))
                     (dom/div #js {:id "right-sidebar" :className "sidebar"}
                              (sidebar-facts-view props))))))

(def reconciler
  (om/reconciler {:state init-data
                  :normalize true
                  :parser (om/parser {:read read :mutate mutate})}))

(defn init []
  (om/add-root! reconciler
                Editor
                (gdom/getElement "container")))

(defmethod mutate 'editor/edit!
  [{:keys [state]} _ {:keys [id]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (assoc-in st [:editing :d/passage]
                        [:passage/by-id id]))))})

(defmethod mutate 'editor/update-passage
  [{:keys [state]} _ {:keys [id props]}]
  {:action
   (fn []
     (if-let [alter (:alter props)]
       (cond
         (:add-assumption alter)
         (swap! state
                (fn [st]
                  (update-in st [:passage/by-id id :d/assumptions]
                             conj (:add-assumption alter))))

         (:add-consequence alter)
         (swap! state
                (fn [st]
                  (update-in st [:passage/by-id id :d/consequences]
                             conj (:add-consequence alter)))))
       (swap! state
              (fn [st]
                (update-in st [:passage/by-id id]
                           #(merge % props))))))})

(comment


  (require '[cljs.pprint :as pp])

  (def norm-data (om/tree->db Editor init-data true))

  (om/get-query Editor)


  (get-in norm-data [:passage/by-id :in-the-building :d/consequences])

  (:om.next/tables norm-data)

  (def parser (om/parser {:read read}))

  (om/get-query Editor)


  norm-data

  (u/ident? (-> norm-data :d/current-passage))

  (-> norm-data :passage/by-id (get (uuid "in-the-building")))


  (parser {:state (atom norm-data)} '[{:d/current-passage [:d/text]}])


  )
