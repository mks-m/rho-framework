(ns rho.core.components
  "Integrant component aggregation for Rho modules."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :rho/components
  [_ {:keys [modules]}]
  (reduce (fn [acc {components :rho/components}] (merge acc components))
          {}
          (:modules modules)))
