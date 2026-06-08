(ns fractal.engine.surface
  "SDK-supplied model-facing surfaces.

   A surface is an embedder-owned set of namespaced host functions, plus public
   metadata used to keep SCI injection and prompt truth aligned. The engine owns
   validation, stamping, capability filtering, prompt rendering, and resume
   compatibility; embedders own function behavior and world-specific effects."
  (:require [clojure.string :as str]
            [fractal.engine.payload :as payload]))

(def ^:private reserved-ns-roots
  #{"clojure" "java" "javax" "sci" "fractal.engine"})

(def ^:private prompt-keys
  #{:system :request :leaf})

(defn- blank? [x]
  (or (nil? x) (and (string? x) (str/blank? x))))

(defn- reserved-ns? [ns-sym]
  (let [s (name ns-sym)]
    (boolean
      (some (fn [root]
              (or (= s root) (str/starts-with? s (str root "."))))
            reserved-ns-roots))))

(defn- valid-ns-symbol? [x]
  (and (symbol? x) (nil? (namespace x)) (not (blank? (name x)))))

(defn- valid-fn-symbol? [x]
  (and (symbol? x) (nil? (namespace x)) (not (blank? (name x)))))

(defn- qsym [ns-sym fn-sym]
  (symbol (name ns-sym) (name fn-sym)))

(defn- ex [msg data]
  (throw (ex-info msg (assoc data :error/type :surface/invalid))))

(defn- normalize-entry [surface-id ns-sym fn-sym entry]
  (when-not (valid-fn-symbol? fn-sym)
    (ex "surface function names must be unqualified symbols"
        {:surface/id surface-id :surface/ns ns-sym :surface/fn fn-sym}))
  (when-not (map? entry)
    (ex "surface function entry must be a map"
        {:surface/id surface-id :surface/function (qsym ns-sym fn-sym)}))
  (let [has-fn?      (contains? entry :fn)
        has-factory? (contains? entry :factory)]
    (when (= has-fn? has-factory?)
      (ex "surface function entry must contain exactly one of :fn or :factory"
          {:surface/id surface-id :surface/function (qsym ns-sym fn-sym)}))
    (when (and has-fn? (not (fn? (:fn entry))))
      (ex "surface :fn must be a function"
          {:surface/id surface-id :surface/function (qsym ns-sym fn-sym)}))
    (when (and has-factory? (not (fn? (:factory entry))))
      (ex "surface :factory must be a function"
          {:surface/id surface-id :surface/function (qsym ns-sym fn-sym)}))
    (assoc entry :surface/function (qsym ns-sym fn-sym))))

(defn- normalize-ns [surface-id [ns-sym fns]]
  (when-not (valid-ns-symbol? ns-sym)
    (ex "surface namespace must be an unqualified symbol"
        {:surface/id surface-id :surface/ns ns-sym}))
  (when (reserved-ns? ns-sym)
    (ex "surface namespace is reserved"
        {:surface/id surface-id :surface/ns ns-sym}))
  (when-not (map? fns)
    (ex "surface namespace value must be a map of function symbols to entries"
        {:surface/id surface-id :surface/ns ns-sym}))
  [ns-sym (into (sorted-map)
                (map (fn [[fn-sym entry]]
                       [fn-sym (normalize-entry surface-id ns-sym fn-sym entry)]))
                fns)])

(defn- normalize-prompts [surface-id prompts]
  (when-not (map? prompts)
    (ex "surface prompts must be a map"
        {:surface/id surface-id :surface/prompts prompts}))
  (into (sorted-map)
        (map (fn [[k v]]
               (when-not (contains? prompt-keys k)
                 (ex "surface prompt kind is unsupported"
                     {:surface/id surface-id :surface/prompt-kind k
                      :surface/prompt-kinds prompt-keys}))
               (when-not (or (nil? v) (string? v) (fn? v))
                 (ex "surface prompt entries must be strings or functions"
                     {:surface/id surface-id :surface/prompt-kind k}))
               [k v]))
        prompts))

(defn- public-function-entry [ns-sym fn-sym entry]
  (cond-> {:symbol (qsym ns-sym fn-sym)}
    (contains? entry :doc)      (assoc :doc (:doc entry))
    (contains? entry :arglists) (assoc :arglists (:arglists entry))))

(defn- public-prompt-entry [v]
  (cond
    (nil? v) nil
    (string? v) v
    (fn? v) :dynamic))

(defn- public-prompts [surface]
  (let [prompts (:surface/prompts surface)]
    (cond-> {}
      (contains? surface :surface/prompt)
      (assoc :legacy-system (public-prompt-entry (:surface/prompt surface)))

      (seq prompts)
      (assoc :prompts
             (into (sorted-map)
                   (keep (fn [[k v]]
                           (when-let [shape (public-prompt-entry v)]
                             [k shape])))
                   prompts)))))

(defn- public-shape [surface]
  {:surface/id      (:surface/id surface)
   :surface/version (:surface/version surface)
   :surface/prompts (public-prompts surface)
   :surface/functions
   (->> (:surface/namespaces surface)
        (mapcat (fn [[ns-sym fns]]
                  (map (fn [[fn-sym entry]]
                         (public-function-entry ns-sym fn-sym entry))
                       fns)))
        (sort-by (comp str :symbol))
        vec)})

(defn stamp [surface]
  (let [shape (public-shape surface)]
    {:surface/id      (:surface/id shape)
     :surface/version (:surface/version shape)
     :surface/hash    (payload/content-id shape)}))

(defn normalize-surface [surface]
  (when-not (map? surface)
    (ex "surface descriptor must be a map" {:surface/value surface}))
  (let [{:surface/keys [id version namespaces prompt prompts]} surface]
    (when-not (keyword? id)
      (ex "surface id must be a keyword" {:surface/id id}))
    (when-not (pos-int? version)
      (ex "surface version must be a positive integer" {:surface/id id :surface/version version}))
    (when-not (map? namespaces)
      (ex "surface namespaces must be a map" {:surface/id id}))
    (when (and (contains? surface :surface/prompt) (not (or (nil? prompt) (string? prompt))))
      (ex "surface prompt must be a string when present" {:surface/id id}))
    (let [normalized (cond-> (assoc surface
                                    :surface/namespaces
                                    (into (sorted-map)
                                          (map #(normalize-ns id %))
                                          namespaces))
                       (contains? surface :surface/prompts)
                       (assoc :surface/prompts (normalize-prompts id prompts)))]
      (assoc normalized :surface/stamp (stamp normalized)))))

(defn- duplicate-values [xs]
  (->> xs frequencies (filter (fn [[_ n]] (> n 1))) (map first) vec))

(defn function-symbols [surfaces]
  (->> surfaces
       (mapcat (fn [surface]
                 (mapcat (fn [[_ fns]]
                           (map (comp :surface/function second) fns))
                         (:surface/namespaces surface))))
       vec))

(defn normalize-surfaces [surfaces]
  (let [xs (mapv normalize-surface (or surfaces []))
        dup-ids (duplicate-values (map :surface/id xs))
        dup-fns (duplicate-values (function-symbols xs))]
    (when (seq dup-ids)
      (ex "surface ids must be unique" {:surface/duplicate-ids dup-ids}))
    (when (seq dup-fns)
      (ex "surface function symbols must be unique" {:surface/duplicate-functions dup-fns}))
    xs))

(defn stamps [surfaces]
  (mapv :surface/stamp surfaces))

(defn- stamp-key [m]
  [(:surface/id m) (:surface/version m) (:surface/hash m)])

(defn canonical-stamps [stamps']
  (->> (or stamps' []) (sort-by stamp-key) vec))

(defn assert-compatible! [stored-stamps configured-surfaces]
  (let [stored     (canonical-stamps stored-stamps)
        configured (canonical-stamps (stamps configured-surfaces))]
    (when-not (= stored configured)
      (throw (ex-info "configured SDK surfaces do not match the persisted session"
                      {:error/type :surface/mismatch
                       :surface/stored stored
                       :surface/configured configured})))))

(defn- allowed? [profile q]
  (contains? (or (:surface/fns profile) #{}) q))

(defn- system-prompt [surface]
  (let [prompts (:surface/prompts surface)]
    (some->> [(:surface/prompt surface) (:system prompts)]
             (remove str/blank?)
             seq
             (str/join "\n"))))

(defn- exposed-function-symbols [surface profile]
  (->> (:surface/namespaces surface)
       (mapcat (fn [[_ fns]]
                 (keep (fn [[_ entry]]
                         (let [q (:surface/function entry)]
                           (when (allowed? profile q) q)))
                       fns)))
       (sort-by str)
       vec))

(defn- exposed-surface? [surface profile]
  (seq (exposed-function-symbols surface profile)))

(defn exposed-functions [surfaces profile]
  (->> surfaces
       (mapcat (fn [surface]
                 (mapcat (fn [[ns-sym fns]]
                           (for [[fn-sym entry] fns
                                 :let [q (:surface/function entry)]
                                 :when (allowed? profile q)]
                             (assoc (select-keys entry [:doc :arglists :surface/function])
                                    :surface/id (:surface/id surface)
                                    :surface/version (:surface/version surface)
                                    :surface/prompt (system-prompt surface)
                                    :surface/ns ns-sym
                                    :surface/fn fn-sym)))
                         (:surface/namespaces surface))))
       (sort-by (comp str :surface/function))
       vec))

(defn- arglists-text [arglists]
  (when (seq arglists)
    (str " " (pr-str arglists))))

(defn prompt-card [surfaces profile]
  (let [entries (exposed-functions surfaces profile)]
    (when (seq entries)
      (let [by-surface (->> entries
                            (group-by (juxt :surface/id :surface/version))
                            vals
                            (sort-by (comp str :surface/id first)))]
        (str
          "Additional SDK surfaces are available as namespaced Clojure functions. "
          "Use them only when they directly fit the task; they are host functions, "
          "not hidden context.\n"
          (str/join
            "\n"
            (for [surface-entries by-surface
                  :let [e (first surface-entries)]]
              (str "Surface " (name (:surface/id e)) " v" (:surface/version e)
                   (when-not (str/blank? (:surface/prompt e))
                     (str ": " (:surface/prompt e)))
                   "\n"
                   (str/join
                     "\n"
                     (for [entry surface-entries]
                       (str "- " (:surface/function entry)
                            (arglists-text (:arglists entry))
                            (when-not (str/blank? (:doc entry))
                              (str " -- " (:doc entry))))))))))))))

(defn- render-prompt-value [surface phase ctx prompt-value]
  (let [ctx' (assoc ctx
                    :surface/id (:surface/id surface)
                    :surface/version (:surface/version surface)
                    :surface/functions (exposed-function-symbols surface (:capability ctx))
                    :surface/prompt-phase phase)
        value (try
                (if (fn? prompt-value)
                  (prompt-value ctx')
                  prompt-value)
                (catch Throwable t
                  (throw (ex-info "surface prompt function failed"
                                  {:error/type :surface/prompt-failed
                                   :surface/id (:surface/id surface)
                                   :surface/prompt-phase phase}
                                  t))))]
    (when-not (or (nil? value) (string? value))
      (throw (ex-info "surface prompt function must return nil or string"
                      {:error/type :surface/prompt-invalid
                       :surface/id (:surface/id surface)
                       :surface/prompt-phase phase
                       :surface/prompt-value value})))
    (when-not (str/blank? value) value)))

(defn prompt-fragments
  "Render dynamic prompt fragments for a prompt phase. Only surfaces with at
   least one capability-exposed function can contribute prompt text."
  [surfaces profile phase ctx]
  (when-not (contains? prompt-keys phase)
    (ex "surface prompt kind is unsupported"
        {:surface/prompt-kind phase :surface/prompt-kinds prompt-keys}))
  (->> surfaces
       (keep (fn [surface]
               (when (exposed-surface? surface profile)
                 (when-let [text (render-prompt-value
                                   surface
                                   phase
                                   (assoc ctx :capability profile)
                                   (get-in surface [:surface/prompts phase]))]
                   {:surface/id (:surface/id surface)
                    :surface/version (:surface/version surface)
                    :surface/prompt-phase phase
                    :text text}))))
       vec))

(defn prompt-fragment-card [heading fragments]
  (when (seq fragments)
    (str heading "\n"
         (str/join
           "\n"
           (for [{:surface/keys [id version] :keys [text]} fragments]
             (str "Surface " (name id) " v" version ":\n" text))))))

(defn request-prompt-card [surfaces profile ctx]
  (prompt-fragment-card
    "SDK surface request context:"
    (prompt-fragments surfaces profile :request ctx)))

(defn leaf-prompt-card [surfaces profile ctx]
  (prompt-fragment-card
    "SDK surface leaf context:"
    (prompt-fragments surfaces profile :leaf ctx)))

(defn- surface-context [handle surface entry]
  {:handle           handle
   :session/id       (:session-id handle)
   :cfg              (:cfg handle)
   :capability       (:capability handle)
   :surface/id       (:surface/id surface)
   :surface/version  (:surface/version surface)
   :surface/function (:surface/function entry)})

(defn- callable [handle surface entry]
  (let [f (if-let [factory (:factory entry)]
            (factory (surface-context handle surface entry))
            (:fn entry))]
    (when-not (fn? f)
      (throw (ex-info "surface function factory did not return a function"
                      {:error/type :surface/invalid-function
                       :surface/id (:surface/id surface)
                       :surface/function (:surface/function entry)})))
    f))

(defn sci-namespaces [handle surfaces profile]
  (or (apply merge
             (keep (fn [surface]
                     (let [ns-map (into {}
                                        (keep (fn [[ns-sym fns]]
                                                (let [exposed
                                                      (into {}
                                                            (keep (fn [[fn-sym entry]]
                                                                    (when (allowed? profile (:surface/function entry))
                                                                      [fn-sym (callable handle surface entry)])))
                                                            fns)]
                                                  (when (seq exposed) [ns-sym exposed]))))
                                        (:surface/namespaces surface))]
                       (when (seq ns-map) ns-map)))
                   surfaces))
      {}))
