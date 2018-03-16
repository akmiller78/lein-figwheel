(ns ^:figwheel-load figwheel.core
  (:require
   #?@(:cljs
       [[figwheel.client.utils :as utils :refer-macros [dev-assert]]
        [figwheel.client.heads-up :as heads-up]
        [goog.object :as gobj]
        [goog.string :as gstring]])
   [clojure.set :refer [difference]]
   [clojure.string :as string]
   #?@(:clj
       [[cljs.env :as env]
        [cljs.compiler]
        [cljs.closure]
        [cljs.repl]
        [cljs.analyzer :as ana]
        [cljs.build.api :as bapi]
        [clojure.data.json :as json]
        [clojure.java.io :as io]
        [figwheel.tools.exceptions :as fig-ex]]))
  (:import #?@(:cljs [[goog]
                      [goog.async Deferred]
                      [goog Promise]
                      [goog.events EventTarget Event]])))

;; -------------------------------------------------
;; utils
;; -------------------------------------------------

(defn distinct-by [f coll]
  (let [seen (volatile! #{})]
    (filter #(let [k (f %)
                   res (not (@seen k))]
               (vswap! seen conj k)
               res)
            coll)))

(defn map-keys [f coll]
  (into {}
        (map (fn [[k v]] [(f k) v]))
        coll))

;; ------------------------------------------------------
;; inline code message formatting
;; ------------------------------------------------------

(def ^:dynamic *inline-code-message-max-column* 80)

(defn wrap-line [text size]
  (re-seq (re-pattern (str ".{1," size "}\\s|.{1," size "}"))
          (str (string/replace text #"\n" " ") " ")))

(defn cross-format [& args]
  (apply #?(:clj format :cljs gstring/format) args))

;; TODO this could be more sophisticated
(defn- pointer-message-lines [{:keys [message column]}]
  (if (> (+ column (count message)) *inline-code-message-max-column*)
    (->> (wrap-line message (- *inline-code-message-max-column* 10))
         (map #(cross-format (str "%" *inline-code-message-max-column* "s") %))
         (cons (cross-format (str "%" (dec column) "s%s") "" "^---"))
         (mapv #(vec (concat [:error-message nil] [%]))))
    [[:error-message nil (cross-format (str "%" (dec column) "s%s %s") "" "^---" message)]]))

(defn inline-message-display-data [{:keys [message line column file-excerpt] :as message-data}]
  (let [{:keys [start-line path excerpt]} file-excerpt
        lines (map-indexed
               (fn [i l] (let [ln (+ i start-line)]
                           (vector (if (= line ln) :error-in-code :code-line) ln l)))
               (string/split-lines excerpt))
        [begin end] (split-with #(not= :error-in-code (first %)) lines)]
    (concat
     (take-last 5 begin)
     (take 1 end)
     (pointer-message-lines message-data)
     (take 5 (rest end)))))

(defn file-line-column [{:keys [file line column]}]
  (cond-> ""
    file (str "file " file)
    line (str " at line " line)
    (and line column) (str ", column " column)))

#?(:cljs
   (do

;; --------------------------------------------------
;; Cross Platform event dispatch
;; --------------------------------------------------
(def event-target (if (and (exists? js/document)
                           (exists? js/document.body))
                    js/document.body
                    (EventTarget.)))

(defonce listener-key-map (atom {}))

(defn unlisten [ky event-name]
  (when-let [f (get @listener-key-map ky)]
    (.removeEventListener event-target (name event-name) f)))

(defn listen [ky event-name f]
  (unlisten ky event-name)
  (.addEventListener event-target (name event-name) f)
  (swap! listener-key-map assoc ky f))

(defn dispatch-event [event-name data]
  (.dispatchEvent
   event-target
   (doto (if (instance? EventTarget event-target)
           (Event. (name event-name) event-target)
           (js/Event. (name event-name) event-target))
     (gobj/add "data" (or data {})))))

(defn event-data [e]
  (gobj/get
   (if-let [e (.-event_ e)] e e)
   "data"))

;; ------------------------------------------------------------
;; Global state
;; ------------------------------------------------------------

(def config-defaults
  {;; this could also be done server side
   :load-warninged-code false
   ::reload-state {}})

(defonce state (atom config-defaults))


;; ------------------------------------------------------------
;; Heads up display logic
;; ------------------------------------------------------------

;; TODO could move the state atom and heads up display logic to heads-up display
;; TODO could probably make it run completely off of events emitted here

(let [last-reload-timestamp (atom 0)
      promise-chain (Promise. (fn [r _] (r true)))]
  (defn render-watcher [_ _ o n]
    ;; a new reload has arrived
    (if-let [ts (when-let [ts (get-in n [::reload-state :reload-started])]
                  (and (< @last-reload-timestamp ts) ts))]
      (let [warnings  (not-empty (get-in n [::reload-state :warnings]))
            exception (get-in n [::reload-state :exception])]
        (reset! last-reload-timestamp ts)
        (cond
          warnings
          (.then promise-chain
                 (fn [] (let [warn (first warnings)]
                          (binding [*inline-code-message-max-column* 132]
                            (.then (heads-up/display-warning (assoc warn :error-inline (inline-message-display-data warn)))
                                   (fn []
                                     (doseq [w (rest warnings)]
                                       (heads-up/append-warning-message w))))))))
          exception
          (.then promise-chain
                 (fn []
                   (binding [*inline-code-message-max-column* 132]
                     (heads-up/display-exception
                      (assoc exception :error-inline (inline-message-display-data exception))))))
          :else
          (.then promise-chain (fn [] (heads-up/flash-loaded))))))))

(add-watch state ::render-watcher render-watcher)

;; ------------------------------------------------------------
;; Namespace reloading
;; ------------------------------------------------------------

(defn immutable-ns? [ns]
  (let [ns (name ns)]
    (or (#{"goog" "cljs.core" "cljs.nodejs"
           "figwheel.preload"
           "figwheel.connect"} ns)
        (goog.string/startsWith "clojure." ns)
        (goog.string/startsWith "goog." ns))))

(defn name->path [ns]
  (gobj/get js/goog.dependencies_.nameToPath ns))

(defn provided? [ns]
  (gobj/get js/goog.dependencies_.written (name->path (name ns))))

(defn ns-exists? [ns]
  (some? (reduce (fnil gobj/get #js{})
                 goog.global (string/split (name ns) "."))))

(defn reload-ns? [namespace]
  (let [meta-data (meta namespace)]
    (and
     (not (immutable-ns? namespace))
     (not (:figwheel-no-load meta-data))
     (or
      (:figwheel-always meta-data)
      (:figwheel-load meta-data)
      ;; might want to use .-visited here
      (provided? namespace)
      (ns-exists? namespace)))))

;; ----------------------------------------------------------------
;; TODOS
;; ----------------------------------------------------------------

;; don't unprovide for things with no-load meta data

;; look more closely at getting a signal for reloading from the env/compiler

;; use goog logging and make log configurable log level
;; make reloading conditional on warnings
;; have an interface that just take the current compiler env and returns a list of namespaces to reload

;; ----------------------------------------------------------------
;; reloading namespaces
;; ----------------------------------------------------------------

(defn ^:export reload-namespaces [namespaces figwheel-meta]
  ;; reconstruct serialized data
  (let [figwheel-meta (into {}
                            (map (fn [[k v]] [(name k) v]))
                            (js->clj figwheel-meta :keywordize-keys true))
        namespaces (map #(with-meta (symbol %)
                           (get figwheel-meta %))
                        namespaces)]
    (swap! state assoc-in [::reload-state :reload-started] (.getTime (js/Date.)))
    (when-not (empty? namespaces)
      (js/setTimeout #(dispatch-event :figwheel.before-js-reload {:namespaces namespaces}) 0))
    (let [to-reload
          (when-not (and (false? (:load-warninged-code @state))
                         (not-empty (get-in @state [::reload-state :warnings])))
            (filter #(reload-ns? %) namespaces))]
      (doseq [ns to-reload]
        ;; goog/require has to be patched by a repl bootstrap
        (goog/require (name ns) true))
      (let [after-reload-fn
            (fn []
              (try
                (when (not-empty to-reload)
                  (utils/log (str "Figwheel: loaded " (pr-str to-reload))))
                (when-let [not-loaded (not-empty (filter (complement (set to-reload)) namespaces))]
                  (utils/log (str "Figwheel: did not load " (pr-str not-loaded))))
                (dispatch-event :figwheel.js-reload {:reloaded-namespaces to-reload})
                (finally
                  (swap! state assoc ::reload-state {}))))]
        (if (and (exists? js/figwheel.client)
                 (exists? js/figwheel.client.file_reloading)
                 (exists? js/figwheel.client.file_reloading.after_reloads))
          (js/figwheel.client.file_reloading.after_reloads after-reload-fn)
          (js/setTimeout after-reload-fn 100)))
      nil)))

;; ----------------------------------------------------------------
;; compiler warnings
;; ----------------------------------------------------------------

(defn ^:export compile-warnings [warnings]
  (when-not (empty? warnings)
    (js/setTimeout #(dispatch-event :figwheel.compile-warnings {:warnings warnings}) 0))
  (swap! state update-in [::reload-state :warnings] concat warnings)
  (doseq [warning warnings]
    (utils/log :warn (str "Figwheel: Compile Warning - " (:message warning) " in " (file-line-column warning))))
  )

(defn ^:export compile-warnings-remote [warnings-json]
  (compile-warnings (js->clj warnings-json :keywordize-keys true)))

;; ----------------------------------------------------------------
;; exceptions
;; ----------------------------------------------------------------

(defn ^:export handle-exception [{:keys [file type message] :as exception-data}]
  (try
    (js/setTimeout #(dispatch-event :figwheel.compile-exception exception-data) 0)
    (swap! state #(-> %
                      (assoc-in [::reload-state :reload-started] (.getTime (js/Date.)))
                      (assoc-in [::reload-state :exception] exception-data)))
    (utils/log :info "Figwheel: Compile Exception")
    (when (or type message)
      (utils/log :info (string/join " : "(filter some? [type message]))))
    (when file
      (utils/log :info (str "Error on " (file-line-column exception-data))))
    (finally
      (swap! state assoc-in [::reload-state] {})))

  )

(defn ^:export handle-exception-remote [exception-data]
  (handle-exception (js->clj exception-data :keywordize-keys true)))

))

#?(:clj
   (do

     (defn debug-prn [& args]
       (binding [*err* *out*]
         (apply prn args)))

(def scratch (atom {}))

(defonce last-compiler-env (atom {}))

(defn client-eval [code]
  (when-not (string/blank? code)
    (cljs.repl/-evaluate
     cljs.repl/*repl-env*
     "<cljs repl>" 1
     code)))

(defn find-figwheel-meta []
  (into {}
        (comp
         (map :ns)
         (map (juxt
               identity
               #(select-keys
                 (meta %)
                 [:figwheel-always :figwheel-load :figwheel-no-load])))
         (filter (comp not-empty second)))
        (:sources @env/*compiler*)))

(defn in-upper-level? [topo-state current-depth dep]
  (some (fn [[_ v]] (and v (v dep)))
        (filter (fn [[k v]] (> k current-depth)) topo-state)))

(defn build-topo-sort [get-deps]
  (let [get-deps (memoize get-deps)]
    (letfn [(topo-sort-helper* [x depth state]
              (let [deps (get-deps x)]
                (when-not (empty? deps) (topo-sort* deps depth state))))
            (topo-sort*
              ([deps]
               (topo-sort* deps 0 (atom (sorted-map))))
              ([deps depth state]
               (swap! state update-in [depth] (fnil into #{}) deps)
               (doseq [dep deps]
                 (when (and dep (not (in-upper-level? @state depth dep)))
                   (topo-sort-helper* dep (inc depth) state)))
               (when (= depth 0)
                 (elim-dups* (reverse (vals @state))))))
            (elim-dups* [[x & xs]]
              (if (nil? x)
                (list)
                (cons x (elim-dups* (map #(clojure.set/difference % x) xs)))))]
      topo-sort*)))

(defn invert-deps [sources]
  (apply merge-with concat
         {}
         (map (fn [{:keys [requires ns]}]
                (reduce #(assoc %1 %2 [ns]) {} requires))
              sources)))

(defn expand-to-dependents [deps]
  (reverse (apply concat
                  ((build-topo-sort (invert-deps (:sources @env/*compiler*)))
                   deps))))

(defn sources-with-paths [files sources]
  (let [files (set files)]
    (filter
     #(when-let [source-file (:source-file %)]
        (files (.getCanonicalPath source-file)))
     sources)))

(defn js-dependencies-with-file-urls [js-dependency-index]
  (distinct-by :url
   (filter #(when-let [u  (:url %)]
              (= "file" (.getProtocol u)))
           (vals js-dependency-index))))

(defn js-dependencies-with-paths [files js-dependency-index]
  (let [files (set files)]
    (distinct
     (filter
      #(when-let [source-file (.getFile (:url %))]
         (files source-file))
      (js-dependencies-with-file-urls js-dependency-index)))))

(defn clj-paths->namespaces [paths]
  (->> paths
       (filter #(.exists (io/file %)))
       (map (comp :ns ana/parse-ns io/file))
       distinct))

(defn figwheel-always-namespaces [figwheel-ns-meta]
  (keep (fn [[k v]] (when (:figwheel-always v) k))
        figwheel-ns-meta))

(defn sources->namespaces-to-reload [sources]
  (distinct
   (concat
    (figwheel-always-namespaces (find-figwheel-meta))
    (map :ns (filter :source-file sources))
    (map symbol
     (mapcat :provides (filter :url sources))))))

(defn paths->namespaces-to-reload [paths]
  (let [cljs-paths (filter #(or (.endsWith % ".cljs")
                                (.endsWith % ".cljc"))
                           paths)
        js-paths   (filter #(.endsWith % ".js") paths)
        clj-paths  (filter #(.endsWith % ".clj") paths)]
    (distinct
     (concat
      (sources->namespaces-to-reload
       (concat
        (when-not (empty? cljs-paths)
          (sources-with-paths cljs-paths (:sources @env/*compiler*)))
        (when-not (empty? js-paths)
          (js-dependencies-with-paths
           js-paths
           (:js-dependency-index @env/*compiler*)))))
      (when-not (empty? clj-paths)
        (bapi/cljs-dependents-for-macro-namespaces
         env/*compiler*
         (clj-paths->namespaces clj-paths)))))))

(defn require-map [env]
  (->> env
       :sources
       (map (juxt :ns :requires))
       (into {})))

(defn changed-dependency-tree? [previous-compiler-env compiler-env]
  (not= (require-map previous-compiler-env) (require-map compiler-env)))

(defrecord FakeReplEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [this opts])
  (-evaluate [_ _ _ js] js)
  (-load [this ns url])
  (-tear-down [_] true))

;; this is a hack for now, easy enough to write this without the hack
(let [noop-repl-env (FakeReplEnv.)]
  (defn add-dependencies-js [ns-sym output-dir]
    (cljs.repl/load-namespace noop-repl-env ns-sym {:output-dir (or output-dir "out")})))

(defn all-add-dependencies [ns-syms output-dir]
  (string/join
   "\n"
   (distinct
    (mapcat #(filter
              (complement string/blank?)
              (string/split-lines %))
            (concat
             ;; this is strange because foreign libs aren't being included in add-dependencies above
             (let [deps-file (io/file output-dir "cljs_deps.js")]
               (when-let [deps-data (and (.exists deps-file) (slurp deps-file))]
                 (when-not (string/blank? deps-data)
                   [deps-data])))
             (map
              #(add-dependencies-js % output-dir)
              ns-syms))))))

(defn output-dir []
  (-> @env/*compiler* :options :output-dir (or "out")))

(defn root-namespaces [env]
  (clojure.set/difference (->> env :sources (mapv :ns) (into #{}))
                          (->> env :sources (map :requires) (reduce into #{}))))

;; TODO since this is the only fn that needs state perhaps isolate
;; last compiler state here?
(defn all-dependency-code [ns-syms]
  (when-let [last-env (get @last-compiler-env env/*compiler*)]
    (when (changed-dependency-tree? last-env @env/*compiler*)
      (let [roots (root-namespaces @env/*compiler*)]
        (all-add-dependencies roots (output-dir))))))

;; TODO change this to reload_namespace_remote interface
;; I think we only need the meta data for the current symbols
;; better to send objects that hold a namespace and its meta data
;; and have a function that reassembles this on the other side
;; this will allow us to add arbitrary data and pehaps change the
;; serialization in the future
(defn reload-namespace-code [ns-syms]
  (str (all-dependency-code ns-syms)
       (format "figwheel.core.reload_namespaces(%s,%s)"
               (json/write-str (mapv cljs.compiler/munge ns-syms))
               (json/write-str (map-keys cljs.compiler/munge (find-figwheel-meta))))))

(defn reload-namespaces [ns-syms]
  (let [ret (client-eval (reload-namespace-code ns-syms))]
    ;; currently we are saveing the value of the compiler env
    ;; so that we can detect if the dependency tree changed
    (swap! last-compiler-env assoc env/*compiler* @env/*compiler*)
    ret))

;; -------------------------------------------------------------
;; reload clojure namespaces
;; -------------------------------------------------------------

;; keep in mind that you need to reload clj namespaces before cljs compiling
(defn reload-clj-namespaces [nses]
  (when (not-empty nses)
    (doseq [ns nses] (require ns :reload))
    (let [affected-nses (bapi/cljs-dependents-for-macro-namespaces env/*compiler* nses)]
      (doseq [ns affected-nses]
        (bapi/mark-cljs-ns-for-recompile! ns (output-dir)))
      affected-nses)))

(defn reload-clj-files [files]
  (reload-clj-namespaces (clj-paths->namespaces files)))

;; -------------------------------------------------------------
;; warnings
;; -------------------------------------------------------------

(defn file-excerpt [file start length]
  {:start-line start
   :path       (.getCanonicalPath file)
   :excerpt  (->> (string/split-lines (slurp file))
                  (drop (dec start))
                  (take length)
                  (string/join "\n"))})

(defn warning-info [{:keys [warning-type env extra path]}]
  (when warning-type
    (let [file (io/file path)
          line (:line env)
          file-excerpt (when (and file (.exists file))
                         (file-excerpt file (max 1 (- line 10)) 20))
          message (cljs.analyzer/error-message warning-type extra)]
      (cond-> {:warning-type warning-type
               :line    (:line env)
               :column  (:column env)
               :ns      (-> env :ns :name)
               :extra   extra}
        message      (assoc :message message)
        path         (assoc :file path)
        file-excerpt (assoc :file-excerpt file-excerpt)))))

(defn warnings->warning-infos [warnings]
  (->> warnings
       (filter
        (comp cljs.analyzer/*cljs-warnings* :warning-type))
       (map warning-info)
       not-empty))

(defn compiler-warnings-code [warning-infos]
  (format "figwheel.core.compile_warnings_remote(%s);"
          (json/write-str warning-infos)))

(defn handle-warnings [warnings]
  (when-let [warns (warnings->warning-infos warnings)]
    (client-eval (compiler-warnings-code warns))))

(comment
  (reset! scratch {})
  (def x
    (first
     (filter (comp cljs.analyzer/*cljs-warnings* :warning-type) (:warnings @scratch))))
  (:warning-data @scratch)
  (count (:parsed-warning @scratch))

  (warnings->warning-infos (:warnings @scratch))

  (handle-warnings (:warnings @scratch))

  )

;; -------------------------------------------------------------
;; exceptions
;; -------------------------------------------------------------

(defn exception-code [parsed-exception]
  (format "figwheel.core.handle_exception_remote(%s);"
          (json/write-str (update parsed-exception :tag #(string/join "/" ((juxt namespace name) %))))))

(defn handle-exception [exception-o-throwable-map]
  (let [{:keys [file line] :as parsed-ex} (fig-ex/parse-exception exception-o-throwable-map)
        file-excerpt (when (and file line (.exists (io/file file)))
                       (file-excerpt (io/file file) (max 1 (- line 10)) 20))
        parsed-ex (cond-> parsed-ex
                    file-excerpt (assoc :file-excerpt file-excerpt))]
    (when parsed-ex
      (client-eval
       (exception-code parsed-ex)))))

(comment
  (require 'figwheel.tools.exceptions-test)

  (handle-exception (figwheel.tools.exceptions-test/fetch-exception "(defn"))
  )


;; -------------------------------------------------------------
;; listening for changes
;; -------------------------------------------------------------

(defn all-sources [compiler-env]
  (concat
   (filter :source-file (:sources compiler-env))
   (js-dependencies-with-file-urls (:js-dependency-index compiler-env))))

(defn source-file [source-o-js-dep]
  (cond
    (:url source-o-js-dep) (io/file (.getFile (:url source-o-js-dep)))
    (:source-file source-o-js-dep) (:source-file source-o-js-dep)))

(defn sources->modified-map [sources]
  (into {}
        (map (comp
              (juxt #(.getCanonicalPath %) #(.lastModified %))
              source-file))
        sources))

(defn sources-modified [compiler-env last-modifieds]
  (doall
   (keep
    (fn [source]
      (let [file' (source-file source)
            path  (.getCanonicalPath file')
            last-modified' (.lastModified file')
            last-modified (get last-modifieds path 0)]
        (when (> last-modified' last-modified)
          (vary-meta source assoc ::last-modified last-modified'))))
    (all-sources compiler-env))))

(defn sources-modified! [compiler-env last-modified-vol]
  (let [modified-sources (sources-modified compiler-env @last-modified-vol)]
    (vswap! last-modified-vol merge (sources->modified-map modified-sources))
    modified-sources))

(defn start*
  ([] (start* env/*compiler* cljs.repl/*repl-env*))
  ([compiler-env repl-env]
   (add-watch
    compiler-env
    ::watch-hook
    (let [last-modified (volatile! (sources->modified-map (all-sources @compiler-env)))]
      (fn [_ _ o n]
        (let [compile-data (-> n meta ::compile-data)]
          (when (and (not= (-> o meta ::compile-data) compile-data)
                     (not-empty (-> n meta ::compile-data)))
            (cond
              (and (:finished compile-data)
                   (not (:exception compile-data))
                   (not (:warnings compile-data)))
              (binding [env/*compiler* compiler-env
                        cljs.repl/*repl-env* repl-env]
                (let [modified-sources (sources-modified! @compiler-env last-modified)
                      namespaces (sources->namespaces-to-reload modified-sources)]
                  (reload-namespaces namespaces)))
              ;; next cond
              :else nil
              ))))))))

;; TODO this is still really rough, not quite sure about this yet
(defmacro start-from-repl
  ([] (start*) nil)
  ([config]
   (start*)
   (when config
     `(swap! state merge ~config))))

(defn stop
  ([] (stop env/*compiler*))
  ([compiler-env] (remove-watch ::watch-hook compiler-env)))

;; -------------------------------------------------------------
;; building
;; -------------------------------------------------------------

(let [cljs-build cljs.closure/build]
  (defn build
    ([src opts] (with-redefs [cljs.closure/build build]
                  (cljs-build src opts)))
    ([src opts compiler-env]
     (let [local-data (volatile! {})]
       (binding [cljs.analyzer/*cljs-warning-handlers*
                 (conj cljs.analyzer/*cljs-warning-handlers*
                       (fn [warning-type env extra]
                         (vswap! local-data update :warnings
                                 (fnil conj [])
                                 {:warning-type warning-type
                                  :env env
                                  :extra extra})))]
         (try
           (swap! compiler-env vary-meta assoc ::compile-data {:started (System/currentTimeMillis)})
           (cljs-build src opts compiler-env)
           (swap! compiler-env
                  vary-meta
                  update ::compile-data
                  (fn [x]
                    (merge (select-keys x [:started])
                           @local-data
                           {:finished (System/currentTimeMillis)})))
           (vreset! local-data {})
           (catch Throwable e
             (swap! compiler-env
                    vary-meta
                    update ::compile-data
                    (fn [x]
                      (merge (select-keys x [:started])
                             @local-data
                             {:exception e
                              :finished (System/currentTimeMillis)}))))
           (finally
             (swap! compiler-env vary-meta assoc ::compile-data {})))))))

  ;; invasive hook of cljs.closure/build
  (defn hook-cljs-closure-build! []
    (if (and (= cljs-build cljs.closure/build) (not= build cljs.closure/build))
      (alter-var-root #'cljs.closure/build build)
      (when (not= cljs-build cljs.closure/build)
        (ex-info "Figwheel can't hook cljs.closure/build as it has already been modified."
                 {:cljs.closure.build cljs.closure/build}))))
  )






(comment



  (first (cljs.js-deps/load-library* "src"))

  (bapi/cljs-dependents-for-macro-namespaces (atom (first (vals @last-compiler-env)))
                                             '[example.macros])

  (swap! scratch assoc :require-map2 (require-map (first (vals @last-compiler-env))))

  (def last-modifieds (volatile! (sources->modified-map (all-sources (first (vals @last-compiler-env))))))

  (map source-file
       (all-sources (first (vals @last-compiler-env))))

  (let [compile-env (atom (first (vals @last-compiler-env)))]
    (binding [env/*compiler* compile-env]
      (paths->namespaces-to-reload [(.getCanonicalPath (io/file "src/example/fun_tester.js"))])

      ))
  (secon (:js-dependency-index (first (vals @last-compiler-env))))
  (js-dependencies-with-file-urls (:js-dependency-index (first (vals @last-compiler-env))))
  (filter (complement #(or (.startsWith % "goog") (.startsWith % "proto")))
          (mapcat :provides (vals (:js-dependency-index (first (vals @last-compiler-env))))))
  (map :provides  (all-sources (first (vals @last-compiler-env))))
  (sources-last-modified (first (vals @last-compiler-env)))


(map source-file )






  (js-dependencies-with-file-urls (:js-dependency-index (first (vals @last-compiler-env))))

(distinct (filter #(= "file" (.getProtocol %)) (keep :url (vals ))))

  (def save (:files @scratch))

  (clj-files->namespaces ["/Users/bhauman/workspace/lein-figwheel/example/src/example/macros.clj"])
  (js-dependencies-with-paths save (:js-dependency-index ))
  (namespaces-for-paths ["/Users/bhauman/workspace/lein-figwheel/example/src/example/macros.clj"]
                        (first (vals @last-compiler-env)))

  (= (-> @scratch :require-map)
     (-> @scratch :require-map2)
     )

  (binding [env/*compiler* (atom (first (vals @last-compiler-env)))]
    #_(add-dependiencies-js 'example.core (output-dir))
    #_(all-add-dependencies '[example.core figwheel.preload]
                          (output-dir))
    #_(reload-namespace-code '[example.core])
    (find-figwheel-meta)
    )

  #_(require 'cljs.core)

  (count @last-compiler-env)
  (map :requires (:sources (first (vals @last-compiler-env))))
  (expand-to-dependents (:sources (first (vals @last-compiler-env))) '[example.fun-tester])

  (def scratch (atom {}))
  (def comp-env (atom nil))

  (first (:files @scratch))
  (.getAbsolutePath (:source-file (first (:sources @comp-env))))
  (sources-with-paths (:files @scratch) (:sources @comp-env))
  (invert-deps (:sources @comp-env))
  (expand-to-dependents (:sources @comp-env) '[figwheel.client.utils])
  (clojure.java.shell/sh "touch" "cljs_src/figwheel_helper/core.cljs")
  )


)






   )