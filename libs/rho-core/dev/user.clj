(ns user
  "REPL helpers for developing Rho apps with Integrant and tools.namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rho.core.system :as system]))

(when-not (System/getProperty "org.slf4j.simpleLogger.logFile")
  (System/setProperty "org.slf4j.simpleLogger.logFile" "System.out"))

(println "[repl] user namespace loaded.")

(defn- existing-dir
  [path]
  (let [f (io/file path)]
    (when (.isDirectory f)
      (.getPath f))))

(defn- refresh-dirs
  []
  (->> ["dev" "src"]
       (map existing-dir)
       (remove nil?)))

(defn- resolve-var
  [sym]
  (try
    (requiring-resolve sym)
    (catch Throwable _ nil)))

(defn- ensure-var
  [sym]
  (or (resolve-var sym)
      (throw (ex-info (str "Missing dependency for " sym
                           ". Add the dev deps (tools.namespace, integrant.repl).")
                      {:missing sym}))))

(defn- set-refresh-dirs!
  []
  (when-let [set-dirs (resolve-var 'clojure.tools.namespace.repl/set-refresh-dirs)]
    (apply set-dirs (refresh-dirs))))

(defn- set-prep!
  []
  (when-let [set-prep (resolve-var 'integrant.repl/set-prep!)]
    (set-prep (fn [] (system/new-system {:rho/profile :dev})))))

(def ^:private watched-extensions #{".clj" ".cljc" ".edn"})
(defonce ^:private watcher (atom nil))

(defn- watched-file?
  [path]
  (let [path (str path)]
    (some #(str/ends-with? path %) watched-extensions)))

(defn- reloadable-event?
  [{:keys [type path]}]
  (and (#{:create :modify :delete} type)
       (watched-file? path)))

(defn- on-file-event
  [event]
  (when (reloadable-event? event)
    (println "[repl] change detected:" (:type event) (:path event))
    ((ensure-var 'clojure.tools.namespace.repl/refresh))))

(defn start-watcher!
  "Start a file watcher that runs `refresh` when refresh dirs change."
  []
  (let [watch (resolve-var 'nextjournal.beholder/watch)
        refresh-var (resolve-var 'clojure.tools.namespace.repl/refresh)
        dirs (refresh-dirs)]
    (cond
      (nil? watch)
      (println "[repl] beholder not on the classpath; watcher not started.")

      (nil? refresh-var)
      (println "[repl] tools.namespace not on the classpath; watcher not started.")

      (empty? dirs)
      (println "[repl] no refresh dirs found; watcher not started.")

      @watcher
      (println "[repl] watcher already running.")

      :else
      (do
        (reset! watcher (apply watch on-file-event dirs))
        (println "[repl] watching" (pr-str dirs))))))

(defn stop-watcher!
  "Stop the running file watcher, if any."
  []
  (if-let [stop (resolve-var 'nextjournal.beholder/stop)]
    (if-let [w @watcher]
      (do
        (stop w)
        (reset! watcher nil)
        (println "[repl] watcher stopped."))
      (println "[repl] watcher not running."))
    (println "[repl] beholder not on the classpath; watcher not stopped.")))

(set-refresh-dirs!)
(set-prep!)
(start-watcher!)

(defn go
  "Start the Integrant system via `integrant.repl/go`."
  []
  ((ensure-var 'integrant.repl/go)))

(defn halt
  "Stop the Integrant system via `integrant.repl/halt`."
  []
  ((ensure-var 'integrant.repl/halt)))

(defn reset
  "Refresh code and restart the system via `integrant.repl/reset`."
  []
  ((ensure-var 'integrant.repl/reset)))

(defn reset-all
  "Refresh all code and restart the system via `integrant.repl/reset-all`."
  []
  ((ensure-var 'integrant.repl/reset-all)))
