(ns frontend.components.search
  (:require [rum.core :as rum]
            [lambdaisland.glogi :as log]
            [frontend.util :as util]
            [frontend.components.block :as block]
            [frontend.components.svg :as svg]
            [frontend.handler.route :as route]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.page :as page-handler]
            [frontend.handler.block :as block-handler]
            [frontend.handler.notification :as notification]
            [frontend.db :as db]
            [frontend.db.model :as model]
            [frontend.handler.search :as search-handler]
            [frontend.handler.whiteboard :as whiteboard-handler]
            [frontend.extensions.pdf.assets :as pdf-assets]
            [frontend.ui :as ui]
            [frontend.state :as state]
            [frontend.mixins :as mixins]
            [frontend.config :as config]
            [clojure.string :as string]
            [frontend.context.i18n :refer [t]]
            [frontend.date :as date]
            [reitit.frontend.easy :as rfe]
            [frontend.modules.shortcut.core :as shortcut]))

(defn highlight-exact-query
  [content q]
  (if (or (string/blank? content) (string/blank? q))
    content
    (when (and content q)
      (let [q-words (string/split q #" ")
            lc-content (util/search-normalize content (state/enable-search-remove-accents?))
            lc-q (util/search-normalize q (state/enable-search-remove-accents?))]
        (if (and (string/includes? lc-content lc-q)
                 (not (util/safe-re-find #" " q)))
          (let [i (string/index-of lc-content lc-q)
                [before after] [(subs content 0 i) (subs content (+ i (count q)))]]
            [:div
             (when-not (string/blank? before)
               [:span before])
             [:mark.p-0.rounded-none (subs content i (+ i (count q)))]
             (when-not (string/blank? after)
               [:span after])])
          (let [elements (loop [words q-words
                                content content
                                result []]
                           (if (and (seq words) content)
                             (let [word (first words)
                                   lc-word (util/search-normalize word (state/enable-search-remove-accents?))
                                   lc-content (util/search-normalize content (state/enable-search-remove-accents?))]
                               (if-let [i (string/index-of lc-content lc-word)]
                                 (recur (rest words)
                                        (subs content (+ i (count word)))
                                        (vec
                                         (concat result
                                                 [[:span (subs content 0 i)]
                                                  [:mark.p-0.rounded-none (subs content i (+ i (count word)))]])))
                                 (recur nil
                                        content
                                        result)))
                             (conj result [:span content])))]
            [:p {:class "m-0"} elements]))))))

(rum/defc search-result-item
  [icon content]
  [:.search-result
   (ui/type-icon icon)
   [:.self-center content]])

(rum/defc block-search-result-item
  [repo uuid format content q search-mode]
  (let [content (search-handler/sanity-search-content format content)]
    [:div
     (when (not= search-mode :page)
       [:div {:class "mb-1" :key "parents"}
        (block/breadcrumb {:id "block-search-block-parent"
                           :block? true
                           :search? true}
                          repo
                          (clojure.core/uuid uuid)
                          {:indent? false})])
     [:div {:class "font-medium" :key "content"}
      (highlight-exact-query content q)]]))

(defonce search-timeout (atom nil))

(defn- search-on-chosen-open-link
  [repo search-q {:keys [data type alias]}]
  (search-handler/add-search-to-recent! repo search-q)
  (search-handler/clear-search!)
  (case type
    :block
    ;; Open the first link in a block's content
    (let [block-uuid (uuid (:block/uuid data))
          block (:block/content (db/entity [:block/uuid block-uuid]))
          link (re-find editor-handler/url-regex block)]
      (if link
        (js/window.open link)
        (notification/show! "No link found on this block." :warning)))

    :page
    ;; Open the first link found in a page's properties
    (let [data (or alias data)
          page (when data (db/entity [:block/name (util/page-name-sanity-lc data)]))
          link (some #(re-find editor-handler/url-regex (val %)) (:block/properties page))]
      (if link
        (js/window.open link)
        (notification/show! "No link found on this page's properties." :warning)))

    nil)
  (state/close-modal!))

(defn- search-on-chosen
  [repo search-q {:keys [type data alias]}]
  (search-handler/add-search-to-recent! repo search-q)
  (search-handler/clear-search!)
  (case type
    :graph-add-filter
    (state/add-graph-search-filter! search-q)

    :new-page
    (page-handler/create! search-q {:redirect? true})

    :new-whiteboard
    (whiteboard-handler/create-new-whiteboard-and-redirect! search-q)

    :page
    (let [data (or alias data)]
      (cond
        (model/whiteboard-page? data)
        (route/redirect-to-whiteboard! data)
        :else
        (route/redirect-to-page! data)))

    :file
    (route/redirect! {:to :file
                      :path-params {:path data}})

    :block
    (let [block-uuid (uuid (:block/uuid data))
          collapsed? (db/parents-collapsed? repo block-uuid)
          page (:block/page (db/entity [:block/uuid block-uuid]))
          page-name (:block/name page)
          long-page? (block-handler/long-page? repo (:db/id page))]
      (if page
        (cond
          (model/whiteboard-page? page-name)
          (route/redirect-to-whiteboard! page-name {:block-id block-uuid})

          (or collapsed? long-page?)
          (route/redirect-to-page! block-uuid)

          :else
          (route/redirect-to-page! (:block/name page) {:anchor (str "ls-block-" (:block/uuid data))}))
        ;; search indice outdated
        (println "[Error] Block page missing: "
                 {:block-id block-uuid
                  :block (db/pull [:block/uuid block-uuid])})))
    nil)
  (state/close-modal!))

(defn- search-on-shift-chosen
  [repo search-q {:keys [type data alias]}]
  (search-handler/add-search-to-recent! repo search-q)
  (case type
    :page
    (let [data (or alias data)
          page (when data (db/entity [:block/name (util/page-name-sanity-lc data)]))]
      (when page
        (state/sidebar-add-block!
         repo
         (:db/id page)
         :page)))

    :block
    (let [block-uuid (uuid (:block/uuid data))
          block (db/entity [:block/uuid block-uuid])]
      (state/sidebar-add-block!
       repo
       (:db/id block)
       :block))

    :new-page
    (page-handler/create! search-q)

    :file
    (route/redirect! {:to :file
                      :path-params {:path data}})

    nil)
  (state/close-modal!))

(defn- create-item-render
  [icon label name]
  (search-result-item
   {:name icon
    :class "highlight"
    :extension? true}
   [:div.text.font-bold (str label ": ")
    [:span.ml-1 name]]))

(defn- search-item-render
  [search-q {:keys [type data alias]}]
  (let [search-mode (state/get-search-mode)
        data (if (string? data) (pdf-assets/fix-local-asset-pagename data) data)]
    [:div {:class "py-2"}
     (case type
       :graph-add-filter
       [:b search-q]

       :new-page
       (create-item-render "new-page" (t :new-page) (str "\"" (string/trim search-q) "\""))

       :new-whiteboard
       (create-item-render "new-whiteboard" (t :new-whiteboard) (str "\"" (string/trim search-q) "\""))

       :page
       [:span {:data-page-ref data}
        (when alias
          (let [target-original-name (model/get-page-original-name alias)]
            [:span.mr-2.text-sm.font-medium.mb-2 (str "Alias -> " target-original-name)]))
        (search-result-item {:name (if (model/whiteboard-page? data) "whiteboard" "page")
                             :extension? true
                             :title (t (if (model/whiteboard-page? data) :search-item/whiteboard :search-item/page))}
                            (highlight-exact-query data search-q))]

       :file
       (search-result-item {:name "file"
                            :title (t :search-item/file)}
                           (highlight-exact-query data search-q))

       :block
       (let [{:block/keys [page uuid content]} data  ;; content here is normalized
             page (util/get-page-original-name page)
             repo (state/sub :git/current-repo)
             format (db/get-page-format page)
             block (when-not (string/blank? uuid)
                     (model/query-block-by-uuid uuid))
             content' (if block (:block/content block) content)]
         [:span {:data-block-ref uuid}
          (search-result-item {:name "block"
                               :title (t :search-item/block)
                               :extension? true}

                              (cond
                                (some? block)
                                (block-search-result-item repo uuid format content' search-q search-mode)

                                (not (string/blank? content'))
                                content'

                                :else
                                (do (log/error "search result with non-existing uuid: " data)
                                    (str "Cache is outdated. Please click the 'Re-index' button in the graph's dropdown menu."))))])

       nil)]))

(rum/defc search-auto-complete
  [{:keys [engine pages files blocks has-more?] :as result} search-q all?]
  (let [pages (when-not all? (map (fn [page]
                                    (let [alias (model/get-redirect-page-name page)]
                                      (cond->
                                       {:type :page
                                        :data page}
                                        (and alias
                                             (not= (util/page-name-sanity-lc page)
                                                   (util/page-name-sanity-lc alias)))
                                        (assoc :alias alias))))
                                  (remove nil? pages)))
        files (when-not all? (map (fn [file] {:type :file :data file}) files))
        blocks (map (fn [block] {:type :block :data block}) blocks)
        search-mode (state/sub :search/mode)
        new-page (if (or
                      (some? engine)
                      (and (seq pages)
                           (= (util/safe-page-name-sanity-lc search-q)
                              (util/safe-page-name-sanity-lc (:data (first pages)))))
                      (nil? result)
                      all?)
                   []
                   (if (state/enable-whiteboards?)
                     [{:type :new-page} {:type :new-whiteboard}]
                     [{:type :new-page}]))
        result (cond
                 config/publishing?
                 (concat pages files blocks)

                 (= :whiteboard/link search-mode)
                 (concat pages blocks)

                 :else
                 (concat new-page pages files blocks))
        result (if (= search-mode :graph)
                 [{:type :graph-add-filter}]
                 result)
        repo (state/get-current-repo)]
    [:div
     (ui/auto-complete
      result
      {:class "search-results"
       :on-chosen #(search-on-chosen repo search-q %)
       :on-shift-chosen #(search-on-shift-chosen repo search-q %)
       :item-render #(search-item-render search-q %)
       :on-chosen-open-link #(search-on-chosen-open-link repo search-q %)})
     (when (and has-more? (util/electron?) (not all?))
       [:div.px-2.py-4.search-more
        [:a.text-sm.font-medium {:href (rfe/href :search {:q search-q})
                                 :on-click (fn []
                                             (when-not (string/blank? search-q)
                                               (state/close-modal!)
                                               (search-handler/search (state/get-current-repo) search-q {:limit 1000
                                                                                                         :more? true})
                                               (search-handler/clear-search!)))}
         (t :more)]])]))

(rum/defc recent-search-and-pages
  [in-page-search?]
  [:div.recent-search
   [:div.wrap.px-4.pb-2.text-sm.opacity-70.flex.flex-row.justify-between.align-items.mx-1.sm:mx-0
    [:div "Recent search:"]
    [:div.hidden.md:flex
     (ui/with-shortcut :go/search-in-page "bottom"
       [:div.flex-row.flex.align-items
        [:div.mr-3.flex "Search blocks in page:"]
        [:div.flex.items-center
         (ui/toggle in-page-search?
                    (fn [_value]
                      (state/set-search-mode! (if in-page-search? :global :page)))
                    true)]
        (ui/tippy {:html [:div
                          ;; TODO: fetch from config
                          "Tip: " [:code (util/->platform-shortcut "Ctrl + Shift + p")] " to open the commands palette"]
                   :interactive     true
                   :arrow           true
                   :theme       "monospace"}
                  [:a.flex.fade-link.items-center
                   {:style {:margin-left 12}
                    :on-click #(state/toggle! :ui/command-palette-open?)}
                   (ui/icon "command" {:style {:font-size 20}})])])]]
   (let [recent-search (mapv (fn [q] {:type :search :data q}) (db/get-key-value :recent/search))
         pages (->> (db/get-key-value :recent/pages)
                    (remove nil?)
                    (filter string?)
                    (remove #(= (string/lower-case %) "contents"))
                    (mapv (fn [page] {:type :page :data page})))
         result (concat (take 5 recent-search) pages)]
     (ui/auto-complete
      result
      {:on-chosen (fn [{:keys [type data]}]
                    (case type
                      :page
                      (do (route/redirect-to-page! data)
                          (state/close-modal!))
                      :search
                      (let [q data]
                        (state/set-q! q)
                        (let [search-mode (state/get-search-mode)
                              opts (if (= :page search-mode)
                                     (let [current-page (or (state/get-current-page)
                                                            (date/today))]
                                       {:page-db-id (:db/id (db/entity [:block/name (util/page-name-sanity-lc current-page)]))})
                                     {})]
                          (if (= :page search-mode)
                            (search-handler/search (state/get-current-repo) q opts)
                            (search-handler/search (state/get-current-repo) q))))

                      nil))
       :on-shift-chosen (fn [{:keys [type data]}]
                          (case type
                            :page
                            (let [page data]
                              (when (string? page)
                                (when-let [page (db/pull [:block/name (util/page-name-sanity-lc page)])]
                                 (state/sidebar-add-block!
                                  (state/get-current-repo)
                                  (:db/id page)
                                  :page))
                                (state/close-modal!)))

                            nil))
       :item-render (fn [{:keys [type data]}]
                      (case type
                        :search [:div.flex-row.flex.search-item.font-medium
                                 svg/search
                                 [:span.ml-2 data]]
                        :page (when-let [original-name (model/get-page-original-name data)] ;; might be block reference
                                (search-result-item {:name "page"
                                                     :extension? true}
                                                    original-name))
                        nil))}))])

(defn default-placeholder
  [search-mode]
  (cond
    config/publishing?
    (t :search/publishing)

    (= search-mode :whiteboard/link)
    (t :whiteboard/link-whiteboard-or-block)

    :else
    (t :search)))

(rum/defcs search-modal < rum/reactive
  (shortcut/disable-all-shortcuts)
  (mixins/event-mixin
   (fn [state]
     (mixins/hide-when-esc-or-outside
      state
      :on-hide (fn []
                 (search-handler/clear-search!)))))
  (rum/local nil ::active-engine-tab)
  [state]
  (let [search-result (state/sub :search/result)
        search-q (state/sub :search/q)
        search-mode (state/sub :search/mode)
        engines (state/sub :search/engines)
        *active-engine-tab (::active-engine-tab state)
        timeout 300
        in-page-search? (= search-mode :page)]
    [:div.cp__palette.cp__palette-main
     [:div.ls-search.p-2
      [:div.input-wrap
      [:input.cp__palette-input.w-full
       {:type          "text"
        :auto-focus    true
        :placeholder   (case search-mode
                         :graph
                         (t :graph-search)
                         :page
                         (t :page-search)
                         (default-placeholder search-mode))
        :auto-complete (if (util/chrome?) "chrome-off" "off") ; off not working here
        :value         search-q
        :on-change     (fn [e]
                         (when @search-timeout
                           (js/clearTimeout @search-timeout))
                         (let [value (util/evalue e)
                               is-composing? (util/onchange-event-is-composing? e)] ;; #3199
                           (if (and (string/blank? value) (not is-composing?))
                             (search-handler/clear-search! false)
                             (let [search-mode (state/get-search-mode)
                                   opts (if (= :page search-mode)
                                          (when-let [current-page (or (state/get-current-page)
                                                                      (date/today))]
                                            {:page-db-id (:db/id (db/entity [:block/name (util/page-name-sanity-lc current-page)]))})
                                          {})]
                               (state/set-q! value)
                               (reset! search-timeout
                                       (js/setTimeout
                                        (fn []
                                          (if (= :page search-mode)
                                            (search-handler/search (state/get-current-repo) value opts)
                                            (search-handler/search (state/get-current-repo) value)))
                                        timeout))))))}]]
      [:div.search-results-wrap
        ;; list registered search engines
       (when (seq engines)
         [:ul.search-results-engines-tabs
          [:li (ui/button "Default" :background "orange"
                          :on-click #(reset! *active-engine-tab nil))]

          (for [[k v] engines]
            [:li {:key k}
             (ui/button [:span.flex.items-center (:name v)
                         (when-let [result (and v (:result v))]
                           (str " (" (count (:blocks result)) ")"))]
                        :background (if (= k @*active-engine-tab) "green" "gray")
                        :on-click #(reset! *active-engine-tab k))])])

       (if-not (nil? @*active-engine-tab)
         (let [active-engine-result (get-in engines [@*active-engine-tab :result])]
           (search-auto-complete
            (merge active-engine-result {:engine @*active-engine-tab}) search-q false))
         (if (seq search-result)
           (search-auto-complete search-result search-q false)
           (recent-search-and-pages in-page-search?)))]]]))

(rum/defc more < rum/reactive
  [route]
  (let [search-q (get-in route [:path-params :q])
        search-result (state/sub :search/more-result)]
    [:div#search.flex-1.flex
     [:div.inner
      [:h1.title (t :search/result-for) [:i search-q]]
      [:p.font-medium.tx-sm (str (count (:blocks search-result)) " " (t :search/items))]
      [:div#search-wrapper.relative.w-full.text-gray-400.focus-within:text-gray-600
       (when-not (string/blank? search-q)
         (search-auto-complete search-result search-q true))]]]))
