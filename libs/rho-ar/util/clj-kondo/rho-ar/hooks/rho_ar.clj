(ns hooks.rho-ar
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]))

(defn- kebab-case
  [value]
  (-> value
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"_" "-")
      str/lower-case))

(defn- model-symbol
  [type-sym]
  (symbol (str (kebab-case (name type-sym)) "-model")))

(defn- columns->fields
  [cols]
  (->> cols
       (filter some?)
       (map (fn [col] (symbol (name col))))
       vec))

(def ^:private default-columns
  [:id :title])

(defn- token-node
  [value]
  (api/token-node value))

(defn- vector-node
  [nodes]
  (api/vector-node nodes))

(defn- list-node
  [nodes]
  (api/list-node nodes))

(defn defmodel
  [{:keys [node]}]
  (let [[_ name opts] (api/sexpr node)
        opts (if (map? opts) opts {})]
    (if (symbol? name)
      (let [cols (or (not-empty (:columns opts)) default-columns)
            fields (columns->fields cols)
            model-sym (model-symbol name)
            record-node (list-node [(token-node 'defrecord)
                                    (token-node name)
                                    (vector-node (mapv token-node fields))])
            model-node (list-node [(token-node 'def)
                                   (token-node model-sym)
                                   (token-node nil)])
            form-node (list-node [(token-node 'do)
                                  record-node
                                  model-node])]
        {:node form-node})
      {:node node})))
