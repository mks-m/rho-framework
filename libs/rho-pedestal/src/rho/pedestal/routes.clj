(ns rho.pedestal.routes
  "Route assembly for the Pedestal module. Combines module routes with
  optional public routes and expands them for logging."
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [io.pedestal.environment :as env]
   [io.pedestal.http.route :as route]))

(defn- var-handler
  [v]
  (let [{:keys [ns name]} (meta v)
        sym (symbol (str (ns-name ns)) (str name))]
    (fn [request]
      (if-let [resolved (requiring-resolve sym)]
        ((deref resolved) request)
        {:status 503
         :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body (str "Handler var not resolved: " sym)}))))

(defn- var-route-name
  [v]
  (let [{:keys [ns name]} (meta v)]
    (when (and ns name)
      (keyword (str (ns-name ns)) (str name)))))

(defn- wrap-route-handler-var
  [route]
  (if (and (vector? route)
           (>= (count route) 3)
           (keyword? (second route))
           (var? (nth route 2)))
    (let [handler-var (nth route 2)
          wrapped (assoc route 2 (var-handler handler-var))
          route-name (var-route-name handler-var)]
      (if (or (some #{:route-name} (drop 3 wrapped))
              (nil? route-name))
        wrapped
        (into []
              (concat (subvec wrapped 0 3)
                      [:route-name route-name]
                      (subvec wrapped 3)))))
    route))

(defn- collect-routes [modules public-routes]
  (let [module-routes (->> modules
                           :modules
                           (map :rho-pedestal/routes)
                           (filter identity)
                           (apply concat))
        header (->> module-routes (filter map?) (take 1) (set))
        wrap-handler (if env/dev-mode? wrap-route-handler-var identity)
        routes (->> module-routes
                    (remove map?)
                    (map wrap-handler)
                    (into header))
        public-routes (or public-routes [])
        module-count (->> module-routes (remove map?) count)
        public-count (count public-routes)]
    {:routes (into routes public-routes)
     :module-count module-count
     :public-count public-count}))

(defn- root-route?
  [routing-table]
  (some (fn [{:keys [method path]}]
          (and (= "/" path)
               (#{:get :any} method)))
        (:routes routing-table)))

(defmethod ig/init-key :rho-pedestal/routes
  [_ {:keys [modules public-routes default-routes]}]
  (log/info "Assembling routes from modules")
  (let [{:keys [routes module-count public-count]}
        (collect-routes modules public-routes)
        expanded (when (seq routes)
                   (route/expand-routes routes))
        default-routes (or default-routes [])
        add-default? (and (seq default-routes)
                          (or (nil? expanded)
                              (not (root-route? expanded))))
        routes (if add-default?
                 (into routes default-routes)
                 routes)
        expanded (route/expand-routes routes)
        default-count (if add-default? (count default-routes) 0)]
    (->> expanded
         route/print-routes
         with-out-str
         java.io.StringReader.
         io/reader
         line-seq
         (map #(log/info %))
         doall)
    (log/info "Assembled" (+ module-count public-count default-count) "routes"
              "(" module-count "module," public-count "public," default-count "default)")
    {:routes routes}))
