(ns livestory.persistence
  (:require [goog.crypt.base64 :as b64]
            [cemerick.url :as url]
            [cljs.reader :as r]))

(defn story->download-url [story]
  (let [encoded (url/url-encode (pr-str story))]
    (str "data:application/edn;charset=utf-8," encoded)))

(defn read-story [b64-encoded-story]
  (r/read-string (b64/decodeString b64-encoded-story)))
