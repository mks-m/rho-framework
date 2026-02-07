(ns rho.pedestal
  "Pedestal HTTP integration for Rho. Builds connectors and manages the
  lifecycle of the HTTP server."
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [io.pedestal.connector :as conn]
   [io.pedestal.http.secure-headers :as secure-headers]
   [io.pedestal.http.http-kit :as hk]))

(defn- inject-components
  [components]
  {:name  ::inject-components
   :enter #(assoc-in % [:request :components] components)})

(defn- resolve-routes
  [routes]
  (if (and (seq? routes) (symbol? (first routes)))
    (eval routes)
    routes))

(defn- response-content-type
  [response]
  (or (get-in response [:headers "Content-Type"])
      (get-in response [:headers "content-type"])
      "unknown"))

(defn- response-length
  [response]
  (or (some-> (get-in response [:headers "Content-Length"]) Long/parseLong)
      (some-> (get-in response [:headers "content-length"]) Long/parseLong)
      (let [body (:body response)]
        (cond
          (string? body) (count (.getBytes ^String body "UTF-8"))
          (bytes? body) (alength ^bytes body)
          (nil? body) 0
          :else nil))))

(defn- log-response
  []
  {:name ::log-response
   :enter (fn [ctx]
            (assoc ctx ::start-time (System/nanoTime)))
   :leave (fn [ctx]
            (let [elapsed-ms (/ (double (- (System/nanoTime) (::start-time ctx))) 1000000.0)
                  request (:request ctx)
                  response (:response ctx)]
              (log/info
               (or (:request-method request) "-")
               (or (:uri request) "-")
               "->"
               (or (:status response) "-")
               (response-content-type response)
               (or (response-length response) "unknown")
               "bytes"
               (format "%.2fms" elapsed-ms)))
            ctx)})

(defn- secure-headers-interceptor
  [config]
  (when-let [opts (:rho/secure-headers config)]
    (secure-headers/secure-headers opts)))

(defn- create-connector [port routes components config]
  (-> (conn/default-connector-map port)
      (conn/with-interceptor (inject-components components))
      (conn/optionally-with-dev-mode-interceptors)
      (conn/with-default-interceptors)
      (conn/with-interceptor (secure-headers-interceptor config))
      (conn/with-interceptor (log-response))
      (conn/with-routes (resolve-routes routes))
      (hk/create-connector nil)))

(defmethod ig/init-key :rho/pedestal
  [_ {:keys [components config routes]}]
  (let [{:rho/keys [app-port]} config
        connector (create-connector app-port (:routes routes) components config)]
    (conn/start! connector)
    (log/info "Pedestal server started on port" app-port)
    (log/info "Open" (:rho/base-url config) "in your browser to access the application")
    {:service connector}))

(defmethod ig/halt-key! :rho/pedestal
  [_ {:keys [service]}]
  (when service
    (conn/stop! service)
    (log/info "Pedestal server stopped"))
  nil)
