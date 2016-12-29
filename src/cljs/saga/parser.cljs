(ns saga.parser
  (:require [om.next :as om]))

(defmulti read om/dispatch)
(defmulti mutate om/dispatch)

(defmethod read :default
  [{:keys [state query] :as env} k params]
  (let [st @state]
    (if query
      {:value (om/db->tree query (get st k) st)}
      {:value (get st k)})))
