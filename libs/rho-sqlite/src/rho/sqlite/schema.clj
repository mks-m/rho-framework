(ns rho.sqlite.schema
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private schema-path
  "resources/schema.edn")

(defn- query
  [datasource sql]
  (jdbc/execute! datasource [sql] {:builder-fn rs/as-unqualified-lower-maps}))

(defn- quote-ident
  [name]
  (str "\"" (str/replace name "\"" "\"\"") "\""))

(defn- pragma
  [name table]
  (str "PRAGMA " name "(" (quote-ident table) ");"))

(defn- table-names
  [datasource]
  (->> (query datasource
              "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'schema_migrations' ORDER BY name;")
       (mapv :name)))

(defn- column-info
  [datasource table]
  (mapv (fn [row]
          (let [not-null? (pos? (long (or (:notnull row) 0)))
                primary-key? (pos? (long (or (:pk row) 0)))]
            (cond-> {:name (:name row)
                     :type (:type row)
                     :not-null? not-null?
                     :default (:dflt_value row)
                     :primary-key? primary-key?}
              primary-key? (assoc :primary-key-position (:pk row)))))
        (query datasource (pragma "table_info" table))))

(defn- index-info
  [datasource table]
  (mapv (fn [row]
          (let [index-name (:name row)
                unique? (pos? (long (or (:unique row) 0)))
                columns (mapv :name
                              (query datasource (pragma "index_info" index-name)))]
            {:name index-name
             :unique? unique?
             :columns columns}))
        (query datasource (pragma "index_list" table))))

(defn- foreign-key-info
  [datasource table]
  (mapv (fn [row]
          {:id (:id row)
           :seq (:seq row)
           :table (:table row)
           :from (:from row)
           :to (:to row)
           :on-update (:on_update row)
           :on-delete (:on_delete row)
           :match (:match row)})
        (query datasource (pragma "foreign_key_list" table))))

(defn db-schema
  [datasource]
  {:tables
   (mapv (fn [table]
           (let [columns (column-info datasource table)
                 indexes (index-info datasource table)
                 foreign-keys (foreign-key-info datasource table)]
             (cond-> {:name table
                      :columns columns}
               (seq indexes) (assoc :indexes indexes)
               (seq foreign-keys) (assoc :foreign-keys foreign-keys))))
         (table-names datasource))})

(defn write-schema!
  [datasource]
  (let [schema (db-schema datasource)
        path (io/file schema-path)]
    (io/make-parents path)
    (spit path (with-out-str (pprint/pprint schema)))
    path))
