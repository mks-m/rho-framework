(ns ^:rho/module basic.htmx
  (:require [io.pedestal.http.route :as route]
            [rho.pedestal.html :as html]
            [rho.htmx :as htmx]
            [rho.pedestal.public :as public]))

(defn htmx-page [_]
  (html/response
   {:title "HTMX example"
    :head [[:link {:rel "stylesheet"
                   :href (public/public-url "css/site.css")}]
           (htmx/script-tag)]
    :body [[:main
            [:h1 "HTMX example"]
            [:p "This page swaps a fragment without a full reload."]
            [:button {:hx-get (route/url-for :basic.htmx/htmx-time)
                      :hx-target "#htmx-time"
                      :hx-swap "outerHTML"}
             "Load server time"]
            [:p [:span {:id "htmx-time"} "Click the button to fetch time."]]]]}))

(defn htmx-time [request]
  (htmx/response
   request
   {:body [[:span {:id "htmx-time"}
            (str "Server time: " (java.time.ZonedDateTime/now))]]}))

(def module
  {:rho-pedestal/routes #{["/htmx" :get htmx-page]
                          ["/htmx/time" :get htmx-time]}})
