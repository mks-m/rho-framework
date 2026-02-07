(ns rho.core.system
  "System assembly for Rho. Combines configuration, modules, and module
  system fragments into an Integrant system map."
  (:require [integrant.core :as ig]
            [rho.core.config :as config]
            [rho.core.components]
            [rho.core.modules :as modules]))

(defn- set-pedestal-dev-mode!
  [profile]
  (let [prop "io.pedestal.dev-mode"
        env "PEDESTAL_DEV_MODE"]
    (when-not (or (System/getProperty prop)
                  (System/getenv env))
      (System/setProperty prop (if (= profile :dev) "true" "false")))))

(defn new-system
  "Build a new Integrant system map from `initial-config`. Loads configs,
  discovers modules, merges module configs, and assembles base keys."
  [initial-config]
  (let [config (config/load-config initial-config)
        _ (set-pedestal-dev-mode! (:rho/profile config))
        loaded-modules (modules/load-modules config)
        module-configs (config/load-module-configs loaded-modules (:rho/profile config))
        merged-config (merge module-configs config)
        [modules-for-config system-frag]
        (reduce (fn [[mc sf] m]
                  [(conj mc (dissoc m :rho/system))
                   (merge sf (:rho/system m))])
                [[] {}]
                loaded-modules)
        base-system {:rho/config (assoc merged-config :rho/config-loaded? true)
                     :rho/modules {:modules modules-for-config}
                     :rho/components {:modules (ig/ref :rho/modules)}}]
    (merge base-system system-frag)))
