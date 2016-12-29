(ns saga.upload
  (:require [clojure.string :as str]
            [clojure.reader :as r]
            [om.dom :as dom]
            [om.next :as om :refer-macros [defui]]
            [saga.parser :as parser :refer [mutate]]))

(defn handle-story-file [this result]
  (let [story (r/read-string result)]
    (om/transact! this `[(story/upload {:story ~story})])))

(defn upload-file [this {:keys [name type file] :as metadata}]
  (let [reader (js/FileReader.)]
    (aset reader "onload" (if (or (= type "application/edn")
                                  (re-find #"\.edn$" name))
                            #(handle-story-file this (aget reader "result"))

                            (js/alert "I don't know what this file is from the MIME type or the extension.")))
    (aset reader "onerror" (fn [e] (js/console.error "Error reading file:" e)))
    (.readAsText reader file)))

(defui UploadFormView
  Object
  (render [this]
          (let [{:keys [title]
                 :or {title "Upload story:"}}
                (om/props this)
                {:keys [upload-fn]} (om/get-computed this)]
            (dom/div #js {:id "upload"}
                     (dom/label #js {:name "csv"} title)
                     (dom/input #js {:type "file" :id "story-upload" :name "story" :accept ".edn"
                                     :onChange (fn [e]
                                                 (let [file (aget (.. e -target -files) 0)
                                                       name (.-name file)
                                                       type (.-type file)]
                                                   (upload-fn {:name name :type type :file file})))})))))

(def view (om/factory UploadFormView))
