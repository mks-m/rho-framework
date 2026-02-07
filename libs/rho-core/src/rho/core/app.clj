(ns rho.core.app
  "Entry points for running a Rho application via Integrant."
  (:require [integrant.core :as ig]
            [rho.core.system :as system])
  (:gen-class))

(def ^:dynamic *system* (atom nil))

(defn start!
  "Start the system for `profile` (keyword) and store it in `system-atom`.
  Defaults to `*system*`."
  ([profile] (start! *system* profile))
  ([system-atom profile]
   (->> {:rho/profile profile}
        system/new-system
        ig/init
        (reset! system-atom))))

(defn stop!
  "Stop the running system in `system-atom` and reset it to nil.
  Defaults to `*system*`."
  ([] (stop! *system*))
  ([system-atom]
   (when-let [system @system-atom]
     (ig/halt! system)
     (reset! system-atom nil))))

(defn -main
  "Main entry point. Starts the system for the first CLI arg (or :dev) and
  installs a shutdown hook."
  [& args]
  (start! *system* (or (keyword (first args)) :dev))
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(stop! *system*)))
  (while true (Thread/sleep 10000)))
