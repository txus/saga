(ns livestory.persistence
  (:require [goog.crypt.base64 :as b64]
            [cemerick.url :as url]
            [cljs.reader :as r]))

(defn set-item!
  "Set `key' in browser's localStorage to `val`."
  [key val]
  (.setItem (.-localStorage js/window) key val))

(defn get-item
  "Returns value of `key' from browser's localStorage."
  [key]
  (.getItem (.-localStorage js/window) key))

(defn remove-item!
  "Remove the browser's localStorage value for the given `key`"
  [key]
  (.removeItem (.-localStorage js/window) key))

(defn get-state [key]
  (when-let [app-state-str (get-item key)]
    (r/read-string app-state-str)))

(defn save-state [key state]
  (set-item! key (pr-str state)))

(defn clear-state [key]
  (remove-item! key))

(defn story->download-url [story]
  (let [encoded (url/url-encode (pr-str story))]
    (str "data:application/edn;charset=utf-8," encoded)))

(defn read-story [b64-encoded-story]
  (r/read-string (b64/decodeString b64-encoded-story)))
