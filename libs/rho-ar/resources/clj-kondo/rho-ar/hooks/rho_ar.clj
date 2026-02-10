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

(defn- namespace-segment
  [ns-sym]
  (let [ns-name (name ns-sym)]
    (last (str/split ns-name #"\."))))

(defn- prefix->string
  [prefix]
  (cond
    (nil? prefix) nil
    (string? prefix) prefix
    (keyword? prefix) (name prefix)
    (symbol? prefix) (name prefix)
    :else (str prefix)))

(defn- normalize-prefix
  [prefix]
  (when (seq prefix)
    (if (str/ends-with? prefix "-")
      prefix
      (str prefix "-"))))

(defn- table-name->string
  [table]
  (cond
    (keyword? table) (name table)
    (string? table) table
    (symbol? table) (name table)
    :else nil))

(defn- default-prefix
  [ns-name table model]
  (let [table-name (or (table-name->string table)
                       (str (kebab-case (name model)) "s"))
        table-kebab (when table-name (kebab-case table-name))
        ns-segment (namespace-segment ns-name)]
    (when (and table-kebab (not= ns-segment table-kebab))
      (str table-kebab "-"))))

(defn- columns->fields
  [cols]
  (->> cols
       (filter some?)
       (map (fn [col] (symbol (name col))))
       vec))

(defn- token-node
  [value]
  (api/token-node value))

(defn- list-node
  [nodes]
  (api/list-node nodes))

(defn- vector-node
  [nodes]
  (api/vector-node nodes))

(defn- ignored-arg
  [arg]
  (let [arg-name (name arg)]
    (if (str/starts-with? arg-name "_")
      arg
      (symbol (str "_" arg-name)))))

(defn- defn-node
  [sym arities]
  (list-node
   (into [(token-node 'defn)
          (token-node sym)]
         (map (fn [args]
                (list-node [(vector-node (mapv (comp token-node ignored-arg) args))
                            (token-node nil)]))
              arities))))

(defn defmodel
  [{:keys [node]}]
  (let [[_ name opts] (api/sexpr node)]
    (if (symbol? name)
      (let [opts (if (map? opts) opts {})
            explicit-prefix? (contains? opts :prefix)
            prefix (when explicit-prefix? (:prefix opts))
            table (:table opts)
            cols (:columns opts)
            fields (columns->fields cols)
            model-sym (model-symbol name)
            ctx-var (resolve 'clj-kondo.hooks-api/*ctx*)
            ctx (when ctx-var (var-get ctx-var))
            ns-name (or (some-> ctx :ns :name) 'user)
            auto-prefix (default-prefix ns-name table name)
            prefix-str (if explicit-prefix?
                         (normalize-prefix (prefix->string prefix))
                         (normalize-prefix auto-prefix))
            prefix-str (or prefix-str "")
            all-sym (symbol (str prefix-str "all"))
            where-sym (symbol (str prefix-str "where"))
            find-sym (symbol (str prefix-str "find"))
            create-sym (symbol (str prefix-str "create!"))
            update-sym (symbol (str prefix-str "update!"))
            delete-sym (symbol (str prefix-str "delete!"))
            save-sym (symbol (str prefix-str "save!"))
            record-node (list-node [(token-node 'defrecord)
                                    (token-node name)
                                    (vector-node (mapv token-node fields))])
            model-node (list-node [(token-node 'def)
                                   (token-node model-sym)
                                   (token-node nil)])
            form-node (list-node
                       [(token-node 'do)
                        record-node
                        model-node
                        (defn-node all-sym [['db#] ['db# 'opts#]])
                        (defn-node where-sym [['db# 'where#] ['db# 'where# 'opts#]])
                        (defn-node find-sym [['db# 'id#] ['db# 'id# 'opts#]])
                        (defn-node create-sym [['db# 'attrs#] ['db# 'attrs# 'opts#]])
                        (defn-node update-sym [['db# 'attrs#] ['db# 'attrs# 'opts#]])
                        (defn-node delete-sym [['db# 'id#] ['db# 'id# 'opts#]])
                        (defn-node save-sym [['db# 'entity#] ['db# 'entity# 'opts#]])])]
        {:node form-node})
      {:node node})))
