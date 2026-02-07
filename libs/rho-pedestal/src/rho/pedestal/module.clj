(ns ^:rho/module rho.pedestal.module
  "Rho module that wires Pedestal routes and server lifecycle into the system."
  (:require [clojure.pprint :as pprint]
            [integrant.core :as ig]
            [rho.pedestal.html :as html]
            [rho.pedestal]
            [rho.pedestal.public :as public]
            [rho.pedestal.routes]))

(defn- pprint-str
  [value]
  (with-out-str (pprint/pprint value)))

(defn- component-summary
  [components]
  (into (sorted-map)
        (map (fn [[k v]] [k (str (type v))]))
        (or components {})))

(defn- request-summary
  [request]
  (-> request
      (select-keys [:request-method
                    :uri
                    :path-info
                    :context-path
                    :query-string
                    :query-params
                    :path-params
                    :remote-addr
                    :server-name
                    :server-port
                    :scheme
                    :headers])
      (update :headers #(into (sorted-map) (or % {})))))

(defn- default-root-page
  [request]
  (let [components (:components request)
        component-info (component-summary components)
        request-info (request-summary request)]
    (html/response
     {:title "Rho - No Root Route"
      :head [[:link {:rel "stylesheet"
                     :href (public/public-url "css/site.css")}]]
      :body [[:main
              [:h1 "Rho default page"]
              [:p "No root route (\"/\") is configured yet, so Rho is showing this default page."]
              [:section
               [:h2 "System components"]
               [:p (str "Discovered components: " (count component-info))]
               [:pre (pprint-str component-info)]]
              [:section
               [:h2 "Request context"]
               [:pre (pprint-str request-info)]]
              [:section
               [:h2 "Create your own page"]
               [:p "Add a module namespace and define a route for \"/\"."]
               [:pre
                [:code
                 "(ns ^:rho/module my.app\n"
                 "  (:require [rho.pedestal.html :as html]))\n\n"
                 "(defn home [_]\n"
                 "  (html/response\n"
                 "   {:title \"Home\"\n"
                 "    :body [[:main [:h1 \"Hello\"]]]}))\n\n"
                 "(def module\n"
                 "  {:rho-pedestal/routes #{[\"/\" :get #'home]}})\n"]]
               [:p "Reload or restart the server after adding the module."]]]]})))

(def module
  {:rho/system
   {:rho-pedestal/routes {:modules (ig/ref :rho/modules)
                          :default-routes #{["/" :get default-root-page]}
                          :public-routes (public/routes
                                          (public/discover-public-paths))}
    :rho/pedestal {:config (ig/ref :rho/config)
                   :routes (ig/ref :rho-pedestal/routes)
                   :components (ig/ref :rho/components)}}})
