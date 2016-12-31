(ns saga.ide
  (:require [goog.dom :as gdom]
            [goog.crypt.base64 :as b64]
            [om.next :as om :refer-macros [defui]]
            [cljs.reader :as r]
            [saga.upload :as upload]
            [saga.syntax :as s]
            [saga.data :as d]
            [saga.persistence :as persistence]
            [saga.parser :as p :refer [read mutate]]
            [saga.editor :as editor]
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

(defn get-story [story-id]
  (let [raw-data @reconciler
        story (get-in raw-data [:story/by-id story-id])
        story (assoc story :d/passages (:d/passages
                                        (om/db->tree
                                         [{:d/passages (om/get-query editor/EditingPassage)}]
                                         story
                                         raw-data)))]
    story))

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
   :stories [{:story/id "example-story" :story/title "Example story" :d/passages (:d/passages example-story)}]
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
            (dom/div #js {:className "story-card mdl-card mdl-shadow--2dp mdl-cell mdl-cell--3-col"
                          :key id}
                     (dom/div #js {:className "mdl-card__title"}
                              (dom/h2 #js {:className "mdl-card__title-text"}
                                      title))
                     (dom/div #js {:className "mdl-card__actions story-actions"}
                              (dom/button #js {:className "story-action mdl-button mdl-js-button mdl-button--fab"
                                               :onClick #(play-story! id)}
                                          (dom/i #js {:className "material-icons"} "play_arrow"))
                              (dom/button #js {:className "story-action mdl-button mdl-js-button mdl-button--fab"
                                               :onClick #(edit-story! id)}
                                          (dom/i #js {:className "material-icons"} "edit"))
                              (dom/a #js {:className "story-action mdl-button mdl-js-button mdl-button--fab"
                                          :download (str title ".edn")
                                          :href (persistence/story->download-url (get-story id))
                                          :target "_blank"}
                                     (dom/i #js {:className "material-icons"} "file_download")))
                     (dom/div #js {:className "mdl-card__menu"}
                              (dom/button #js {:className "story-action mdl-button mdl-js-button mdl-button-icon"
                                               :onClick #(when (.confirm js/window "Are you sure?")
                                                           (delete-story! id))}
                                          (dom/i #js {:className "material-icons"} "delete")))))))

(def story-view (om/factory Story {:keyfn :story/id}))

(defui StoryList
  Object
  (render [this]
          (let [{:keys [stories]} (om/props this)
                computed (om/get-computed this)]
            (apply dom/div #js {:className "stories mdl-grid"}
                   (map
                    #(story-view (om/computed % computed))
                    stories)))))

(def story-list-view (om/factory StoryList))

(defn navigation-for [this screen]
  (when (= screen :editor)
    (dom/nav #js {:className "mdl-navigation"}
             (dom/a #js {:className "mdl-navigation__link"
                         :href ""
                         :onClick (fn [e]
                                    (.preventDefault e)
                                    (om/transact! this `[(routes/navigate {:screen :main})])
                                    false)}
                    "Back"))))

(defn title-for [this screen]
  (if (= screen :editor)
    (let [{:keys [editor]} (om/props this)]
      (str
       (-> editor :story :story/title)
       (when-let [p (-> editor :editing :d/passage :d/id)]
         (str " - " (name p)))))
    "Stories"))


(defn main-header-view [this]
  (dom/header #js {:className "ide-actions"}
              (dom/button #js {:className "ide-action mdl-button mdl-js-button mdl-button--fab"
                               :onClick (fn [_]
                                          (when-let [title (.prompt js/window "Enter a title for your story")]
                                            (om/transact! this `[(story/add! {:title ~title})])))}
                          (dom/i #js {:className "material-icons"} "add"))
              (upload/view (om/computed {:className "ide-action"} {:upload-fn (partial upload/upload-file this)}))))

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
            (dom/div #js {:className "mdl-layout mdl-js-layout mdl-layout--fixed-header"}
                     (dom/header #js {:className "mdl-layout__header"}
                                 (dom/div #js {:className "mdl-layout__header-row"}
                                          (dom/span #js {:className "mdl-layout-title"} (title-for this screen))
                                          (dom/div #js {:className "mdl-layout-spacer"})
                                          (navigation-for this screen)))
                     (dom/main #js {:className "mdl-layout__content"}
                               (dom/div #js {:className "page-content"}
                                        (condp = screen
                                          :main
                                          (dom/div nil
                                                   (main-header-view this)
                                                   (story-list-view (om/computed {:stories stories}
                                                                                 {:play-story! (fn [id] (om/transact! this `[(routes/navigate {:screen :player :id ~id})]))
                                                                                  :download-story! (fn [id] (om/transact! this `[(routes/download {:id ~id})]))
                                                                                  :edit-story! (fn [id] (om/transact! this `[(routes/navigate {:screen :editor :id ~id})]))
                                                                                  :delete-story! (fn [id] (om/transact! this `[(app/delete-story {:id ~id})]))})))
                                          :editor
                                          (editor/view (om/computed editor {:parent this}))
                                          (dom/div nil "Oops, you're lost!"))))))))

(def reconciler
  (om/reconciler {:state (or (persistence/get-state "ide") init-data)
                  :normalize true
                  :parser (om/parser {:read read :mutate mutate})}))

(defmethod mutate 'app/save-state
  [{:keys [state] :as env} _ _]
  {:action (fn []
             (let [st @state
                   denormalized (om/db->tree (om/get-query App) st st)]
               (persistence/save-state "ide" denormalized)))})

(defmethod mutate 'story/upload
  [{:keys [state] :as env} _ {:keys [story]}]
  {:value {:keys [:stories]}
   :action (fn []
             (swap! state
                    (fn [st]
                      (let [{:keys [story/id]} story
                            normalized (om/tree->db Story story true)
                            story (dissoc normalized :fact/by-id :passage/by-id ::om/tables)
                            new-st
                            (-> st
                                (update :fact/by-id merge (:fact/by-id normalized))
                                (update :passage/by-id merge (:passage/by-id normalized))
                                (update :stories conj [:story/by-id id])
                                (assoc-in [:story/by-id id] story))]
                        new-st))))})

;; Routes

(defroute "/" []
  (om/transact! reconciler `[(routes/navigate {:screen :main})]))

(defroute "/edit/:id" [id]
  (om/transact! reconciler `[(routes/navigate {:screen :editor :id ~id})]))

(defroute "/play/:id" [id]
  (om/transact! reconciler `[(routes/navigate {:screen :player :id ~id})]))

(defroute "/download/:id" [id]
  (om/transact! reconciler `[(routes/download {:id ~id})]))

(def auto-save (atom nil))

(defn init []
  (swap! auto-save
         (fn [interval?]
           (when interval?
             (println "there is an interval")
             (.clearInterval js/window interval?))
           (.setInterval js/window
            #(om/transact! reconciler `[(app/save-state)])
            5000)))
  (om/add-root! reconciler
                App
                (gdom/getElement "container")))
