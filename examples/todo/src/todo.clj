(ns ^:rho/module todo
  (:require [rho.ar :as ar]
            [rho.htmx :as htmx]
            [rho.pedestal.html :as html]
            [rho.pedestal.public :as public]))

(ar/defmodel Todo {})

(defn- request-id [request] (parse-long (get-in request [:path-params :id])))

(defn- todo-timestamp [value] (if (some? value) (str value) ""))

(defn- todo-item [{:keys [id title completed-at created-at]}]
  [:li {:class (str "todo-card" (when completed-at " is-complete"))
        :data-id id}
   [:div.todo-main
    [:form.toggle-form {:hx-post (str "/toggle/" id)
                        :hx-target "#todo-list"
                        :hx-swap "outerHTML"}
     [:button.toggle-btn {:type "submit"
                          :aria-label (if completed-at "Mark as incomplete" "Mark as complete")}]]
    [:div.todo-text
     [:div.todo-title {:id (str "todo-title-" id)} title]
     [:div.todo-meta (todo-timestamp created-at)]]]
   [:div {:class "todo-actions"}
    [:form.icon-form {:hx-post (str "/delete/" id)
                      :hx-target "#todo-list"
                      :hx-swap "outerHTML"}
     [:button.icon-button.is-delete {:type "submit" :title "Delete"} "Del"]]
    [:button.icon-button.is-edit {:title "Edit"
                                  :hx-get (str "/edit/" id)
                                  :hx-target (str "#todo-title-" id)
                                  :hx-swap "outerHTML"} "Edit"]]])

(defn- todo-list [db]
  [:div#todo-list.todo-list
   (let [todos (todos-all db {:order-by [[:created-at :desc]]})]
     (if (seq todos)
       [:ul {:class "todo-items"} (map todo-item todos)]
       [:p {:class "todo-empty"} "No todos yet."]))])

(defn index [{{:keys [db]} :components}]
  (html/response
   {:title "Todos"
    :head [[:link {:rel "stylesheet"
                   :href (public/public-url "todo.css")}]
           (htmx/script-tag)]
    :body [[:main.todo-shell
            [:header.page-header [:h1.page-title "Todo List"]]
            [:section.todo-toolbar
             [:form.todo-form {:hx-post "/add"
                               :hx-target "#todo-list"
                               :hx-swap "outerHTML"}
              [:label.sr-only {:for "todo-title"} "New todo"]
              [:input {:id "todo-title"
                       :name "title"
                       :type "text"
                       :placeholder "Add a new task"
                       :required true}]
              [:button {:type "submit"} "Add Task"]]]
            (todo-list db)]]}))

(defn add [{{:keys [db]} :components :as request}]
  (when-let [title (some-> request :form-params :title str not-empty)]
    (todos-create! db {:title title}))
  (htmx/response request {:body (todo-list db)}))

(defn delete [{{:keys [db]} :components :as request}]
  (when-let [id (request-id request)]
    (todos-delete! db id))
  (htmx/response request {:body (todo-list db)}))

(defn toggle [{{:keys [db]} :components :as request}]
  (when-let [id (request-id request)]
    (todos-update! db
                   {:id id
                    :completed-at [:case
                                   [:= :completed-at nil] [:raw "CURRENT_TIMESTAMP"]
                                   :else nil]
                    :updated-at [:raw "CURRENT_TIMESTAMP"]}))
  (htmx/response request {:body (todo-list db)}))

(defn edit-get [{{:keys [db]} :components :as request}]
  (if-let [todo (some->> (request-id request) (todos-find db))]
    (htmx/response
     request
     {:body (let [{:keys [id title]} todo]
              [:form.edit-form {:id (str "todo-title-" id)
                                :hx-post (str "/edit/" id)
                                :hx-target "#todo-list"
                                :hx-swap "outerHTML"}
               [:label.sr-only {:for (str "todo-edit-" id)} "Edit todo"]
               [:input {:id (str "todo-edit-" id)
                        :name "title"
                        :required true
                        :value title}]
               [:button.btn-save {:type "submit"} "Save"]])})
    {:status 404
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Todo not found."}))

(defn edit-post [{{:keys [db]} :components :as request}]
  (when-let [id (request-id request)]
    (when-let [title (some-> request :form-params :title str not-empty)]
      (todos-update! db
                     {:id id
                      :title title
                      :updated-at [:raw "CURRENT_TIMESTAMP"]})))
  (htmx/response request {:body (todo-list db)}))

(def module
  {:rho-pedestal/routes [["/" :get #'index]
                         ["/add" :post #'add]
                         ["/delete/:id" :post #'delete]
                         ["/toggle/:id" :post #'toggle]
                         ["/edit/:id" :get #'edit-get]
                         ["/edit/:id" :post #'edit-post]]})
