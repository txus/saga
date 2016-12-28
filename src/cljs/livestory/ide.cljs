(ns livestory.ide
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [livestory.syntax :as s]
            [livestory.data :as d]
            [livestory.parser :as p :refer [read mutate]]
            [livestory.editor :as editor]
            [om.util :as u]
            devtools.core
            [secretary.core :as secretary :refer-macros [defroute]]
            [om.dom :as dom]
            [clojure.string :as str]
            [clojure.set :as set]))

(devtools.core/install! [:custom-formatters :sanity-hints])

(defn log [& xs]
  (js/console.log (apply str (interpose " - " (map pr-str xs)))))

(enable-console-print!)

(declare reconciler)

(def example-story
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

(def init-data
  {:screen :main
   :stories [{:story/id "story-1" :story/title "The first story" :d/passages (:d/passages example-story)}]
   :editor nil
   :player nil})


(defmethod mutate 'routes/navigate
  [{:keys [state]} _ {:keys [screen id]}]
  {:action
   (fn []
     (swap! state
            (fn [st]
              (condp = screen
                :main (assoc st :screen screen)
                :editor (let [story (get-in st [:story/by-id id])]
                          (assoc st
                                 :screen screen
                                 :editor {:story [:story/by-id id]
                                          :editing {:d/passage (-> story :d/passages first)}}))
                :player (assoc st
                               :player {:story [:story/by-id id]}
                               :screen screen)
                st))))})

(defmethod mutate 'app/delete-story
  [{:keys [state]} _ {:keys [id]}]
  {:action
   (fn []
     (swap! state (fn [st]
                    (-> st
                        (update :story/by-id dissoc id)
                        (update :stories (fn [stories]
                                           (vec (remove (partial = [:story/by-id id]) stories))))))))})

(defui Story
  static om/Ident
  (ident [this {:keys [story/id]}]
         [:story/by-id id])

  static om/IQuery
  (query [this]
         (let [p (om/get-query editor/EditingPassage)]
           `[:story/id :story/title {:d/passages ~p}]))
  Object
  (render [this]
          (let [{:keys [story/id story/title d/passages]} (om/props this)
                {:keys [play-story! edit-story! delete-story!]} (om/get-computed this)]
            (dom/li #js {:className "story"
                         :key id}
                     (dom/h2 nil title)
                     (dom/ul #js {:className "story-actions"}
                             (dom/li #js {:key (str "play-" id)}  (dom/a #js {:onClick #(play-story! id)} "Play"))
                             (dom/li #js {:key (str "edit-" id)} (dom/a #js {:onClick #(edit-story! id)} "Edit"))
                             (dom/li #js {:key (str "delete-" id)}
                                     (dom/a #js {:onClick #(when (.confirm js/window "Are you sure?")
                                                             (delete-story! id))}
                                                "Delete")))))))

(def story-view (om/factory Story {:keyfn :story/id}))

(defui StoryList
  Object
  (render [this]
          (let [{:keys [stories]} (om/props this)
                computed (om/get-computed this)]
            (apply dom/ul #js {:className "stories"}
                   (map
                    #(story-view (om/computed % computed))
                    stories)))))

(def story-list-view (om/factory StoryList))

(defui App
  static om/IQuery
  (query [this]
         (let [story-query (om/get-query Story)
               editor-subquery (om/get-query editor/Editor)]
           `[:screen
             {:stories ~story-query}
             {:editor ~editor-subquery}]))

  Object
  (render [this]
          (let [{:keys [screen stories editor player]} (om/props this)]
            (condp = screen
              :main
              (dom/div nil
                       (dom/h3 nil "Your stories")
                       (story-list-view (om/computed {:stories stories}
                                                     {:play-story! (fn [id] (om/transact! this `[(routes/navigate {:screen :player :id ~id})]))
                                                      :edit-story! (fn [id] (om/transact! this `[(routes/navigate {:screen :editor :id ~id})]))
                                                      :delete-story! (fn [id] (om/transact! this `[(app/delete-story {:id ~id})]))})))
              :editor
              (dom/div nil
                       (dom/h3 nil (:title editor))
                       (dom/a #js {:onClick (fn [_] (om/transact! this `[(routes/navigate {:screen :main})]))}
                              "Back")
                       (editor/view editor))
              (dom/div nil "Oops, you're lost!")))))

(def reconciler
  (om/reconciler {:state init-data
                  :normalize true
                  :parser (om/parser {:read read :mutate mutate})}))

;; Routes

(defroute "/" []
  (om/transact! reconciler `[(routes/navigate {:screen :main})]))

(defroute "/edit/:id" [id]
  (om/transact! reconciler `[(routes/navigate {:screen :editor :id ~id})]))

(defroute "/play/:id" [id]
  (om/transact! reconciler `[(routes/navigate {:screen :player :id ~id})]))

(defn ^:export foo []
  (keys @reconciler))

(defn init []
  (om/add-root! reconciler
                App
                (gdom/getElement "container")))
