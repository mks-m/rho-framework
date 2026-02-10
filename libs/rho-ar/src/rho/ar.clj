(ns rho.ar
  "ActiveRecord-style helpers for Rho.

  Usage:
    (ns my.app
      (:require [rho.ar :as ar]))

    (ar/defmodel Todo {})

    (defn handler [{{:keys [db]} :components}]
      (todos-all db))"
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private default-jdbc-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defprotocol Adapter
  (query [adapter sql-map jdbc-opts])
  (query-one [adapter sql-map jdbc-opts])
  (execute! [adapter sql-map jdbc-opts]))

(defprotocol ModelSpec
  (table [model])
  (primary-key [model])
  (columns [model])
  (record-fn [model]))

(defprotocol RecordEntity
  (model-of [entity]))

(defrecord Model [table primary-key columns record-fn]
  ModelSpec
  (table [_] table)
  (primary-key [_] (or primary-key :id))
  (columns [_] (or columns []))
  (record-fn [_] (or record-fn identity)))

(defrecord NextJdbcAdapter [connectable sql-opts jdbc-opts]
  Adapter
  (query [_ sql-map opts]
    (let [sql-vec (sql/format sql-map sql-opts)]
      (log/info "rho-ar query" {:sql (first sql-vec)
                                :params (vec (rest sql-vec))})
      (jdbc/execute! connectable
                     sql-vec
                     (merge default-jdbc-opts (or jdbc-opts {}) (or opts {})))))
  (query-one [this sql-map opts]
    (first (query this sql-map opts)))
  (execute! [_ sql-map opts]
    (let [sql-vec (sql/format sql-map sql-opts)]
      (log/info "rho-ar execute" {:sql (first sql-vec)
                                  :params (vec (rest sql-vec))})
      (jdbc/execute! connectable
                     sql-vec
                     (merge default-jdbc-opts (or jdbc-opts {}) (or opts {}))))))

(defn next-jdbc-adapter
  ([connectable]
   (next-jdbc-adapter connectable nil))
  ([connectable {:keys [sql-opts jdbc-opts]}]
   (->NextJdbcAdapter connectable (or sql-opts {}) (or jdbc-opts {}))))

(defn- kebab-case
  [value]
  (-> value
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"_" "-")
      str/lower-case))

(defn- model-symbol
  [type-sym]
  (symbol (str (kebab-case (name type-sym)) "-model")))

(defn- normalize-column
  [col]
  (keyword (name col)))

(defn- normalize-columns
  [cols]
  (mapv normalize-column cols))

(defn- schema-column
  [col]
  (-> col name (str/replace "_" "-") keyword))

(defn- schema-columns
  [cols]
  (mapv schema-column cols))

(defn normalize-row
  "Convert underscored keys in a row map to dash-separated keywords."
  [row]
  (if (map? row)
    (reduce-kv (fn [acc k v]
                 (let [k-name (name k)
                       k (if (str/includes? k-name "_")
                           (keyword (str/replace k-name "_" "-"))
                           k)]
                   (assoc acc k v)))
               {}
               row)
    row))

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

(defn- schema-resource
  []
  (io/resource "schema.edn"))

(defn- read-schema
  []
  (when-let [resource (schema-resource)]
    (edn/read-string (slurp resource))))

(defn- table-name->string
  [table]
  (cond
    (keyword? table) (name table)
    (string? table) table
    (symbol? table) (name table)
    :else nil))

(defn- schema-table
  [schema table]
  (when-let [table-name (table-name->string table)]
    (some (fn [entry]
            (when (= (table-name->string (:name entry)) table-name)
              entry))
          (:tables schema))))

(defn- model-table-candidates
  [model]
  (let [base (-> model name kebab-case (str/replace "-" "_"))]
    [base (str base "s")]))

(defn- schema-table-for-model
  [schema model]
  (some (fn [candidate] (schema-table schema candidate))
        (model-table-candidates model)))

(defmacro defmodel
  [model & [opts]]
  (when (and (some? opts) (not (map? opts)))
    (throw (ex-info "defmodel options must be a map." {:model model :opts opts})))
  (let [opts (or opts {})
        {:keys [table primary-key columns prefix]} opts
        explicit-prefix? (contains? opts :prefix)
        schema (when (or (nil? table) (not (seq columns)))
                 (read-schema))
        table-entry (when schema
                      (if table
                        (schema-table schema table)
                        (schema-table-for-model schema model)))
        table (or table (some-> table-entry :name keyword))
        columns (or columns (some->> table-entry
                                     :columns
                                     (mapv :name)
                                     schema-columns))
        _ (when (and (or (nil? table) (not (seq columns)))
                     (nil? schema))
            (throw (ex-info "schema.edn not found on classpath; provide :table and :columns."
                            {:model model})))
        _ (when (and (nil? table) (nil? table-entry))
            (throw (ex-info "defmodel requires :table or a matching table in schema.edn."
                            {:model model
                             :candidates (model-table-candidates model)})))
        _ (when-not (seq columns)
            (throw (ex-info "defmodel requires non-empty :columns or schema.edn entry."
                            {:model model :table table})))
        pk (or primary-key :id)
        cols (normalize-columns columns)
        field-syms (mapv (comp symbol name) cols)
        model-sym (model-symbol model)
        map->sym (symbol (str "map->" model))
        table-kebab (some-> table table-name->string kebab-case)
        ns-segment (namespace-segment (ns-name *ns*))
        auto-prefix (when (and table-kebab (not= ns-segment table-kebab))
                      (str table-kebab "-"))
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
        save-sym (symbol (str prefix-str "save!"))]
    `(do
       (defrecord ~model ~field-syms)
       (def ~model-sym
         (->Model ~table ~pk ~cols
                  (fn [row#]
                    (~map->sym (select-keys (rho.ar/normalize-row row#) ~cols)))))
       (extend-type ~model
         RecordEntity
         (model-of [_#] ~model-sym))
       (defn ~all-sym
         ([db#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/all adapter# ~model-sym)))
         ([db# opts#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/all adapter# ~model-sym opts#))))
       (defn ~where-sym
         ([db# where#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/where adapter# ~model-sym where#)))
         ([db# where# opts#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/where adapter# ~model-sym where# opts#))))
       (defn ~find-sym
         ([db# id#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/find adapter# ~model-sym id#)))
         ([db# id# opts#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/find adapter# ~model-sym id# opts#))))
       (defn ~create-sym
         ([db# attrs#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/create! adapter# ~model-sym attrs#)))
         ([db# attrs# opts#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/create! adapter# ~model-sym attrs# opts#))))
       (defn ~update-sym
         ([db# attrs#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/update! adapter# ~model-sym attrs#)))
         ([db# attrs# opts#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/update! adapter# ~model-sym attrs# opts#))))
       (defn ~delete-sym
         ([db# id#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/delete! adapter# ~model-sym id#)))
         ([db# id# opts#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/delete! adapter# ~model-sym id# opts#))))
       (defn ~save-sym
         ([db# entity#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/save! adapter# entity#)))
         ([db# entity# opts#]
          (let [adapter# (if (satisfies? rho.ar/Adapter db#)
                           db#
                           (rho.ar/next-jdbc-adapter db#))]
            (rho.ar/save! adapter# entity# opts#))))
       ~model-sym)))

(defn- normalize-where
  [clause]
  (cond
    (nil? clause) nil
    (map? clause)
    (let [pairs (mapv (fn [[k v]] [:= k v]) clause)]
      (cond
        (empty? pairs) nil
        (= 1 (count pairs)) (first pairs)
        :else (into [:and] pairs)))
    (sequential? clause) clause
    :else
    (throw (ex-info "Invalid where clause" {:clause clause}))))

(defn- select-map
  [model where-clause opts]
  (let [opts (or opts {})]
    (cond-> {:select (or (:select opts) (columns model))
             :from [(table model)]}
      where-clause (assoc :where where-clause)
      (some? (:order-by opts)) (assoc :order-by (:order-by opts))
      (some? (:limit opts)) (assoc :limit (:limit opts))
      (some? (:offset opts)) (assoc :offset (:offset opts)))))

(defn- jdbc-opts
  [opts]
  (:jdbc-opts (or opts {})))

(defn- rows->records
  [model rows]
  (let [record (record-fn model)]
    (mapv record (or rows []))))

(defn- attrs->map
  [attrs]
  (if (map? attrs)
    attrs
    (into {} attrs)))

(defn- filtered-attrs
  [model attrs]
  (select-keys attrs (columns model)))

(def ^:private created-at-keys
  [:created-at])

(def ^:private updated-at-keys
  [:updated-at])

(defn- timestamp-key
  [model candidates]
  (let [cols (set (columns model))]
    (some (fn [k] (when (contains? cols k) k)) candidates)))

(defn- inject-timestamp
  [attrs key]
  (if (and key (not (contains? attrs key)))
    (assoc attrs key [:raw "CURRENT_TIMESTAMP"])
    attrs))

(defn- generated-keys
  [result]
  (cond
    (map? result) result
    (and (sequential? result) (map? (first result))) (first result)
    :else nil))

(defn- update-count
  [result]
  (cond
    (nil? result) nil
    (number? result) result
    (map? result) (or (:next.jdbc/update-count result)
                      (:update-count result))
    (and (sequential? result) (map? (first result))) (update-count (first result))
    :else nil))

(defn where
  ([adapter model where-clause]
   (where adapter model where-clause nil))
  ([adapter model where-clause opts]
   (let [sql-map (select-map model (normalize-where where-clause) opts)
         rows (query adapter sql-map (jdbc-opts opts))]
     (rows->records model rows))))

(defn all
  ([adapter model]
   (all adapter model nil))
  ([adapter model opts]
   (where adapter model nil opts)))

(defn find
  ([adapter model id]
   (find adapter model id nil))
  ([adapter model id opts]
   (let [sql-map (select-map model [:= (primary-key model) id] opts)
         row (query-one adapter sql-map (jdbc-opts opts))]
     (when row
       ((record-fn model) row)))))

(defn create!
  ([adapter model attrs]
   (create! adapter model attrs nil))
  ([adapter model attrs opts]
   (let [attrs (attrs->map attrs)
         attrs (inject-timestamp attrs (timestamp-key model created-at-keys))
         attrs (filtered-attrs model attrs)
         sql-map {:insert-into (table model)
                  :values [attrs]}
         result (execute! adapter sql-map (jdbc-opts opts))
         merged (merge attrs (or (generated-keys result) {}))]
    ((record-fn model) merged))))

(defn update!
  ([adapter model attrs]
   (update! adapter model attrs nil))
  ([adapter model attrs opts]
   (let [attrs (attrs->map attrs)
         attrs (inject-timestamp attrs (timestamp-key model updated-at-keys))
         attrs (filtered-attrs model attrs)
         pk (primary-key model)
         id (get attrs pk)
         set-map (dissoc attrs pk)]
     (when (and (some? id) (seq set-map))
       (let [sql-map {:update (table model)
                      :set set-map
                      :where [:= pk id]}
             result (execute! adapter sql-map (jdbc-opts opts))
             count (update-count result)]
         (when (and (some? count) (pos? count))
           (find adapter model id opts)))))))

(defn delete!
  ([adapter model id]
   (delete! adapter model id nil))
  ([adapter model id opts]
   (let [sql-map {:delete-from (table model)
                  :where [:= (primary-key model) id]}
         result (execute! adapter sql-map (jdbc-opts opts))]
     (update-count result))))

(defn save!
  ([adapter entity]
   (save! adapter entity nil))
  ([adapter entity opts]
   (let [model (model-of entity)
         attrs (attrs->map entity)
         pk (primary-key model)
         id (get attrs pk)]
     (if (some? id)
       (update! adapter model attrs opts)
       (create! adapter model (dissoc attrs pk) opts)))))
