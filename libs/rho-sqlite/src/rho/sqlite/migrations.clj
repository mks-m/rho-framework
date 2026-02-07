(ns rho.sqlite.migrations
  "CLI utilities for running sqlite migrations."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [migratus.core :as migratus]
            [rho.core.config :as config])
  (:import [com.zaxxer.hikari HikariDataSource]))

(defn- sqlite-opts
  [config]
  (or (:rho/sqlite-opts config) {}))

(defn- require-jdbc-url
  [config]
  (let [jdbc-url (:rho/sqlite-url config)]
    (when-not (string? jdbc-url)
      (throw (ex-info "Missing required :rho/sqlite-url configuration."
                      {:key :rho/sqlite-url})))
    jdbc-url))

(defn- set-if
  [value setter]
  (when (some? value)
    (setter value)))

(defn- configure-pool!
  [^HikariDataSource datasource pool]
  (let [pool (or pool {})]
    (set-if (:pool-name pool) #(.setPoolName datasource %))
    (set-if (:auto-commit pool) #(.setAutoCommit datasource (boolean %)))
    (set-if (:maximum-pool-size pool) #(.setMaximumPoolSize datasource (int %)))
    (set-if (:minimum-idle pool) #(.setMinimumIdle datasource (int %)))
    (set-if (:connection-timeout-ms pool) #(.setConnectionTimeout datasource (long %)))
    (set-if (:idle-timeout-ms pool) #(.setIdleTimeout datasource (long %)))
    (set-if (:max-lifetime-ms pool) #(.setMaxLifetime datasource (long %)))))

(defn- build-datasource
  [config]
  (let [opts (sqlite-opts config)
        jdbc-url (require-jdbc-url config)
        datasource (doto (HikariDataSource.)
                     (.setJdbcUrl jdbc-url))]
    (configure-pool! datasource (:pool opts))
    (log/info "SQLite datasource connected." {:jdbc-url jdbc-url})
    datasource))

(defn- migratus-config
  [sqlite-opts datasource]
  (let [base {:store :database
              :migration-dir "migrations"}
        user (or (:migratus sqlite-opts) {})]
    (cond-> (merge base user)
      datasource (assoc :db {:datasource datasource}))))

(defn- migration-dirs
  [config]
  (let [dir (:migration-dir config)]
    (cond
      (string? dir) [dir]
      (sequential? dir) (vec (filter string? dir))
      :else [])))

(defn- available-migration-dirs
  [config]
  (->> (migration-dirs config)
       (filter io/resource)
       vec))

(defn- update-migration-dirs
  [config dirs]
  (assoc config :migration-dir (if (= 1 (count dirs))
                                 (first dirs)
                                 (vec dirs))))

(defn- migration-dir-fs
  [config]
  (let [dir (or (first (migration-dirs config)) "migrations")
        dir-file (io/file dir)
        dir-str (str dir)
        cwd-name (.getName (.getCanonicalFile (io/file ".")))
        resources-dir (io/file "resources")
        resources-file (io/file "resources" dir)]
    (cond
      (.isAbsolute dir-file) (.getPath dir-file)
      (str/starts-with? dir-str "resources/") dir-str
      (.exists dir-file) (.getPath dir-file)
      (= "resources" cwd-name) (.getPath dir-file)
      (.isDirectory resources-dir) (.getPath resources-file)
      :else (.getPath dir-file))))

(defn- load-config
  [profile]
  (config/load-config (cond-> {}
                        (some? profile) (assoc :rho/profile profile))))

(defn- migrate!
  [profile]
  (let [config (load-config profile)
        opts (sqlite-opts config)
        datasource (build-datasource config)]
    (try
      (let [m-config (migratus-config opts datasource)
            dirs (available-migration-dirs m-config)]
        (if (empty? dirs)
          (log/info "SQLite migrations skipped (no migration resources found).")
          (do
            (log/info "SQLite migrations starting.")
            (migratus/migrate (update-migration-dirs m-config dirs)))))
      (finally
        (.close ^HikariDataSource datasource)))))

(defn- rollback!
  [profile steps]
  (let [config (load-config profile)
        opts (sqlite-opts config)
        datasource (build-datasource config)]
    (try
      (let [m-config (migratus-config opts datasource)
            dirs (available-migration-dirs m-config)
            m-config (update-migration-dirs m-config dirs)]
        (if (empty? dirs)
          (log/info "SQLite rollback skipped (no migration resources found).")
          (do
            (log/info "SQLite rollback starting.")
            (if steps
              (migratus/rollback m-config steps)
              (migratus/rollback m-config)))))
      (finally
        (.close ^HikariDataSource datasource)))))

(defn- create!
  [name]
  (when-not (seq name)
    (throw (ex-info "Migration name is required for create." {})))
  (let [config (load-config nil)
        opts (sqlite-opts config)
        m-config (migratus-config opts nil)
        dir (migration-dir-fs m-config)
        m-config (assoc m-config :migration-dir dir)]
    (log/info "Creating sqlite migration." {:name name :dir dir})
    (migratus/create m-config name)))

(defn- usage!
  []
  (println "Usage:")
  (println "  clj -M -m rho.sqlite.migrations migrate [profile]")
  (println "  clj -M -m rho.sqlite.migrations rollback [profile] [steps]")
  (println "  clj -M -m rho.sqlite.migrations create <name>")
  (System/exit 1))

(defn -main
  [& args]
  (let [[command arg1 arg2] args]
    (case command
      "migrate"
      (migrate! arg1)

      "rollback"
      (let [steps (parse-long arg2)
            [profile steps] (if (and (nil? arg2) (parse-long arg1))
                              [nil (parse-long arg1)]
                              [arg1 steps])]
        (rollback! profile steps))

      "create"
      (do
        (when (seq arg2)
          (log/warn "SQLite migration create ignores profile argument."))
        (create! arg1))

      (usage!))))
