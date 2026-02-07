(ns rho.sqlite.db
  "Convenience functions for executing HoneySQL queries via next.jdbc."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def default-opts
  "Default next.jdbc options used by query helpers."
  {:builder-fn rs/as-unqualified-lower-maps})

(defn query
  "Execute a HoneySQL select map and return all rows."
  ([connectable sql-map]
   (query connectable sql-map nil))
  ([connectable sql-map opts]
   (jdbc/execute! connectable (sql/format sql-map) (merge default-opts opts))))

(defn query-one
  "Execute a HoneySQL select map and return the first row."
  ([connectable sql-map]
   (query-one connectable sql-map nil))
  ([connectable sql-map opts]
   (first (query connectable sql-map opts))))

(defn execute!
  "Execute a HoneySQL insert/update/delete map for side effects."
  ([connectable sql-map]
   (execute! connectable sql-map nil))
  ([connectable sql-map opts]
   (jdbc/execute! connectable (sql/format sql-map) (merge default-opts opts))))
