(ns electron.plugin
  (:require [promesa.core :as p]
            [cljs-bean.core :as bean]
            ["semver" :as semver]
            ["os" :as os]
            ["fs-extra" :as fs]
            ["path" :as path]
            [clojure.string :as string]
            [electron.utils :refer [fetch extract-zip] :as utils]
            [electron.logger :as logger]
            [electron.configs :as cfgs]
            [electron.window :refer [get-all-windows]]))

;; update & install
;;(def *installing-or-updating (atom nil))
(def debug (partial logger/debug "[Marketplace]"))
(def emit (fn [type payload]
            (doseq [^js win (get-all-windows)]
              (.. win -webContents
                  (send (name type) (bean/->js payload))))))

(defn dotdir-file?
  [file]
  (and file (string/starts-with? (path/normalize file) cfgs/dot-root)))

(defn assetsdir-file?
  [file]
  (and (string? file) (string/includes? file "assets/storages")))

;; Get a release by tag name: /repos/{owner}/{repo}/releases/tags/{tag}
;; Get the latest release: /repos/{owner}/{repo}/releases/latest
;; Zipball https://api.github.com/repos/{owner}/{repo}/zipball

(defn- fetch-release-asset
  [{:keys [repo theme]} url-suffix {:keys [response-transform]
                                    :or {response-transform identity}}]
  (p/catch
   (p/let [repo (some-> repo (string/trim) (string/replace #"^/+(.+?)/+$" "$1"))
           api #(str "https://api.github.com/repos/" repo "/" %)
           endpoint (api url-suffix)
           ^js res (fetch endpoint)
           res (response-transform res)
           res (.json res)
           _ (debug "[Release URL] " endpoint)
           res (bean/->clj res)
           version (:tag_name res)
           asset (first (filter #(string/ends-with? (:name %) ".zip") (:assets res)))]

          [(if (and (nil? asset) theme)
             (if-let [zipball (:zipball_url res)]
               zipball
               (api "zipball"))
             asset)
           version
           (:body res)])

   (fn [^js e]
     (debug e)
     (throw (js/Error. [:release-channel-issue (.-message e)])))))

(defn fetch-latest-release-asset
  "Fetches latest release, normally when user clicks to install or update a plugin"
  [item]
  (fetch-release-asset item "releases/latest" {}))

(defn fetch-specific-release-asset
  "Fetches a specific release asset, normally when installing specific versions
  from plugins.edn. If a release does not exist, it falls back to fetching the
  latest release for a plugin. This is done for unusual plugins where the
  package.json version does not match the git tagged version e.g.
  https://github.com/hkgnp/logseq-osmmaps-plugin has respective values of 1.5
  and v1.5."
  [{:keys [version repo] :as item}]
  (fetch-release-asset item
                       (str "releases/tags/" version)
                       {:response-transform
                        (fn [res]
                          (if (= 404 (.-status res))
                            ;; Fall back to fetching the latest for these rare
                            ;; cases. Previous logseq versions did not store the
                            ;; plugin's git tag required to correctly install it
                            (let [repo' (some-> repo (string/trim) (string/replace #"^/+(.+?)/+$" "$1"))
                                  api #(str "https://api.github.com/repos/" repo' "/" %)]
                              (fetch (api "releases/latest")))
                            res))}))

(defn download-asset-zip
  [{:keys [id repo title author description effect sponsors]} dl-url dl-version dot-extract-to]
  (p/catch
    (p/let [^js res (fetch dl-url {:timeout 30000})
            _ (when-not (.-ok res)
                (throw (js/Error. [:download-channel-issue (.-statusText res)])))
            frm-zip (p/create
                      (fn [resolve1 reject1]
                        (let [body (.-body res)
                              *downloaded (atom 0)
                              dest-basename (path/basename dl-url)
                              dest-basename (if-not (string/ends-with? dest-basename ".zip")
                                              (str id "_" dest-basename ".zip") dest-basename)
                              tmp-dest-file (path/join (os/tmpdir) (str dest-basename ".pending"))
                              dest-file (.createWriteStream fs tmp-dest-file)]
                          (doto body
                            (.on "data" (fn [chunk]
                                          (let [downloaded (+ @*downloaded (.-length chunk))]
                                            (.write dest-file chunk)
                                            (reset! *downloaded downloaded))))
                            (.on "error" (fn [^js e]
                                           (reject1 e)))
                            (.on "end" (fn [^js _e]
                                         (.close dest-file)
                                         (let [dest-file (string/replace tmp-dest-file ".pending" "")]
                                           (fs/renameSync tmp-dest-file dest-file)
                                           (resolve1 dest-file))))))))
            ;; sync extract
            zip-extracted-path (string/replace frm-zip ".zip" "")

            _ (extract-zip frm-zip (bean/->js
                                     {:dir zip-extracted-path}))

            tmp-extracted-root (let [dirs (fs/readdirSync zip-extracted-path)
                                     pkg? (fn [root]
                                            (when-let [^js stat (fs/statSync root)]
                                              (when (.isDirectory stat)
                                                (fs/pathExistsSync (.join path root "package.json")))))]
                                 (if (pkg? zip-extracted-path)
                                   "."
                                   (last (take-while #(pkg? (.join path zip-extracted-path %)) dirs))))

            _ (when-not tmp-extracted-root
                (throw (js/Error. :invalid-plugin-package)))

            tmp-extracted-root (.join path zip-extracted-path tmp-extracted-root)

            _ (and (fs/existsSync dot-extract-to)
                   (fs/removeSync dot-extract-to))

            _ (fs/moveSync tmp-extracted-root dot-extract-to)

            _ (let [src (.join path dot-extract-to "package.json")
                    ^js sponsors (bean/->js sponsors)
                    ^js pkg (fs/readJsonSync src)]
                (set! (.-repo pkg) repo)
                (set! (.-title pkg) title)
                (set! (.-author pkg) author)
                (set! (.-description pkg) description)
                (set! (.-effect pkg) (boolean effect))
                ;; Force overwrite version because of developers tend to
                ;; forget to update the version number of package.json
                (when dl-version (set! (.-version pkg) dl-version))
                (when sponsors (set! (.-sponsors pkg) sponsors))
                (fs/writeJsonSync src pkg))

            _ (do
                (fs/removeSync zip-extracted-path)
                (fs/removeSync frm-zip))]
      true)
    (fn [^js e]
      (emit :lsp-installed {:status :error :payload e})
      (throw e))))

(defn install-or-update!
  "Default behavior is to install the latest version of a given repo. Item map
  includes the following keys:
* :only-check - When set to true, this only fetches the latest version without installing
* :plugin-action - When set to 'install', installs the specific :version given
* :repo - A github repo, not a logseq repo, e.g. user/repo"
  [{:keys [version repo only-check plugin-action] :as item}]
  (if repo
    (let [coerced-version (and version (. semver coerce version))
          updating? (and version (. semver valid coerced-version)
                         (not= plugin-action "install"))]

      (debug (if updating? "Updating:" "Installing:") repo)

      (-> (p/create
           (fn [resolve _reject]
             ;;(reset! *installing-or-updating item)
             ;; get releases
             (-> (p/let [[asset latest-version notes]
                         (if (= plugin-action "install")
                           (fetch-specific-release-asset item)
                           (fetch-latest-release-asset item))

                         _ (debug "[Release Asset] #" latest-version " =>" (:url asset))

                         ;; compare latest version
                         _ (when-let [coerced-latest-version
                                      (and updating? latest-version
                                           (. semver coerce latest-version))]

                             (debug "[Updating Latest?] " version " > " latest-version)

                             (if (. semver lt coerced-version coerced-latest-version)
                               (debug "[Updating Latest] " latest-version)
                               (throw (js/Error. :no-new-version))))

                         dl-url (if-not (string? asset)
                                  (:browser_download_url asset) asset)

                         _ (when-not dl-url
                             (debug "[Download URL Error]" asset)
                             (throw (js/Error. [:release-asset-not-found (js/JSON.stringify asset)])))

                         dest (.join path cfgs/dot-root "plugins" (:id item))
                         _ (when-not only-check (download-asset-zip item dl-url latest-version dest))
                         _ (debug (str "[" (if only-check "Checked" "Updated") "DONE]") latest-version)]

                        (emit :lsp-installed
                              {:status     :completed
                               :only-check only-check
                               :payload    (if only-check
                                             (assoc item :latest-version latest-version :latest-notes notes)
                                             (assoc item :zip dl-url :dst dest :installed-version latest-version))})

                        (resolve nil))

                 (p/catch
                  (fn [^js e]
                    (emit :lsp-installed
                          {:status     :error
                           :only-check only-check
                           :payload    (assoc item :error-code (.-message e))})
                    (debug e))
                  (resolve nil)))))

          (p/finally
           (fn []))))
    (debug "Skip install because no repo was given for: " item)))

(defn uninstall!
  [id]
  (let [id (string/replace id #"^[.\/]+" "")
        plugin-path (.join path (utils/get-ls-dotdir-root) "plugins" id)
        settings-path (.join path (utils/get-ls-dotdir-root) "settings" (str id ".json"))]
    (debug "[Uninstall]" plugin-path)
    (when (fs/pathExistsSync plugin-path)
      (fs/removeSync plugin-path)
      (fs/removeSync settings-path))))
