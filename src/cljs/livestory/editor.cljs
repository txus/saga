(ns livestory.editor
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [livestory.data :as d]
            [livestory.syntax :as s]
            [om.util :as u]
            devtools.core
            [om.dom :as dom]
            [clojure.string :as str]))

(devtools.core/install! [:custom-formatters :sanity-hints])

(enable-console-print!)

(defn log [& xs]
  (js/console.log (apply str (interpose " - " (map pr-str xs)))))

(defmulti read om/dispatch)

(defmethod read :default
  [{:keys [state query] :as env} k params]
  (let [st @state]
    (if (u/ident? (k st))
      {:value (get-in st (k st))}
      {:value (om/db->tree query (k st) st)})))

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
            (apply dom/ul #js {:id "sidebar-passages"}
                   (map (comp sidebar-passage-view
                           #(om/computed % computed))
                        passages)))))

(def sidebar-passages-view (om/factory SidebarPassages))

(defui EditingPassage
  static om/Ident
  (ident [this {:keys [d/id]}]
         [:passage/by-id id])

  static om/IQuery
  (query [this]
         [:d/id :d/text]))

(defui Editing
  static om/IQuery
  (query [this]
         (let [sub (om/get-query EditingPassage)]
           `[{:d/passage ~sub}]))

  Object
  (render [this]
          (let [{:keys [d/passage]} (om/props this)
                {:keys [update-passage!]} (om/get-computed this)
                {:keys [d/text d/id]} passage]
            (dom/div nil
                     (dom/textarea #js {:id "text"
                                        :rows 10
                                        :cols 10
                                        :onChange (fn [e]
                                                    (let [new-text (.. e -target -value)]
                                                      (update-passage! id {:d/text new-text})))
                                        :value text})))))

(def editing-view (om/factory Editing))

(defui Editor
  static om/IQuery
  (query [this]
         (let [subquery (om/get-query SidebarPassage)
               editing-subquery (om/get-query Editing)]
           `[{:d/passages ~subquery}
             {:editing ~editing-subquery}]))

  Object
  (render [this]
          (let [{:keys [editing] :as props} (om/props this)
                edit! (fn [id]
                        (om/transact! this `[(editor/edit! {:id ~id})]))
                update-passage! (fn [id props]
                                  (om/transact! this `[(editor/update-passage {:id ~id :props ~props})]))]
            (dom/div nil
                     (dom/div #js {:id "sidebar"}
                              (sidebar-passages-view (om/computed props
                                                                  {:edit! edit!})))
                     (dom/div #js {:id "editing"}
                              (editing-view (om/computed editing
                                                         {:update-passage! update-passage!})))))))

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
     (swap! state
            (fn [st]
              (update-in st [:passage/by-id id]
                         #(merge % props)))))})

(comment


  (require '[cljs.pprint :as pp])

  (def norm-data (om/tree->db Editor init-data true))

  (def parser (om/parser {:read read}))

  (om/get-query Editor)


  norm-data

  (u/ident? (-> norm-data :d/current-passage))

  (-> norm-data :passage/by-id (get (uuid "in-the-building")))


  (parser {:state (atom norm-data)} '[{:d/current-passage [:d/text]}])


  )
