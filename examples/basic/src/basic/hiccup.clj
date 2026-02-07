(ns ^:rho/module basic.hiccup
  (:require [io.pedestal.http.route :as route]
            [rho.pedestal.html :as html]
            [rho.pedestal.public :as public]))

(defn default-page [_]
  (html/response
   {:title "Hiccup example - Default template"
    :head [[:link {:rel "stylesheet"
                   :href (public/public-url "css/site.css")}]]
    :body [[:main
            [:h1 "Basic"]
            [:p "Hello from a basic HTML template."]
            [:p [:a {:href (route/url-for :basic.hiccup/custom-page)}
                 "Custom template example"]]]]}))

(defn custom-template [{:keys [title body]}]
  [:html
   [:head
    [:title (or title "Basic Custom")]]
   [:body
    [:header [:h1 "Custom Template"]]
    (into [:section] body)]])

(defn custom-page [_]
  (html/response
   {:template custom-template}
   {:title "Hiccup example - Custom template"
    :body [[:main
            [:p "This page renders with a custom template."]
            [:p [:a {:href (route/url-for :basic.hiccup/default-page)}
                 "Default template example"]]]]}))

(def module
  {:rho-pedestal/routes #{["/hiccup/default" :get default-page]
                          ["/hiccup/custom" :get custom-page]}})
