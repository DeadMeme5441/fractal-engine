(ns fractal-engine.session-store
  (:require [clojure.java.io :as io]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.lineage :as lineage])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.security MessageDigest]))

(def canonical-files
  ["session.edn" "messages.edn" "turns.edn" "evals.edn" "calls.edn" "snapshots.edn"])

(defn- exists? [p]
  (Files/exists (artifacts/path p) (make-array java.nio.file.LinkOption 0)))

(defn- safe-read [dir file default]
  (try
    {:value (artifacts/read-edn-file (artifacts/path dir file) default)}
    (catch Throwable t
      {:value default
       :warning {:warning/type :artifact/read-failed
                 :file file
                 :message (.getMessage t)}})))

(defn read-lineage [dir]
  (:value (safe-read dir "lineage.edn" nil)))

(defn child-dirs [dir]
  (let [children (artifacts/path dir "children")]
    (if (exists? children)
      (->> (.listFiles (.toFile children))
           (filter #(.isDirectory %))
           (sort-by #(.getName %))
           (mapv #(.toPath %)))
      [])))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn session-fingerprint [dir]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [file canonical-files]
      (let [p (artifacts/path dir file)]
        (.update digest (.getBytes file StandardCharsets/UTF_8))
        (.update digest (byte-array [(byte 0)]))
        (if (exists? p)
          (.update digest (Files/readAllBytes p))
          (.update digest (.getBytes "<missing>" StandardCharsets/UTF_8)))))
    (str "sha256:" (hex (.digest digest)))))

(defn load-session [dir]
  (let [pairs (map (fn [[k file default]]
                     [k (safe-read dir file default)])
                   [[:session "session.edn" {}]
                    [:messages "messages.edn" []]
                    [:turns "turns.edn" []]
                    [:evals "evals.edn" []]
                    [:calls "calls.edn" []]
                    [:events "events.edn" []]
                    [:snapshots "snapshots.edn" []]
                    [:final "final.edn" nil]
                    [:usage "usage.edn" nil]
                    [:tree "tree.edn" nil]
                    [:lineage "lineage.edn" nil]
                    [:rehydration "rehydration.edn" nil]])
        values (into {} (map (fn [[k r]] [k (:value r)]) pairs))
        warnings (vec (keep (comp :warning second) pairs))]
    (assoc values
           :dir (artifacts/path dir)
           :warnings warnings
           :handle (lineage/session-handle dir)
           :fingerprint (session-fingerprint dir))))

(defn- status [loaded]
  (or (get-in loaded [:session :session/status])
      (when (seq (:warnings loaded)) :malformed)
      :unknown))

(defn- latest-final [loaded]
  (let [final (:final loaded)
        turn (last (:turns loaded))]
    (cond
      (contains? final :final/latest-value-preview)
      {:handle (lineage/final-handle (:dir loaded))
       :preview (:final/latest-value-preview final)
       :turn-id (:final/latest-turn-id final)}

      (:turn/final-preview turn)
      {:handle (lineage/final-handle (:dir loaded))
       :preview (:turn/final-preview turn)
       :turn-id (:turn/id turn)}

      :else nil)))

(defn- turn-summary [dir turn]
  {:handle (lineage/turn-handle dir (:turn/id turn))
   :id (:turn/id turn)
   :status (:turn/status turn)
   :final-preview (:turn/final-preview turn)
   :eval-count (count (:turn/eval-ids turn))
   :call-count (count (:turn/call-ids turn))})

(defn- eval-summary [dir row]
  {:handle (lineage/eval-handle dir (:eval/id row))
   :id (:eval/id row)
   :status (:eval/status row)
   :code-preview (some-> (:eval/code row)
                         (subs 0 (min 120 (count (:eval/code row)))))})

(defn- call-summary [dir row]
  {:handle (lineage/call-handle dir (:call/id row))
   :id (:call/id row)
   :type (:call/type row)
   :status (:call/status row)
   :child-handle (when-let [rel (:child/dir row)]
                   (lineage/child-handle dir rel))})

(defn- files [dir]
  {:session (lineage/path-string (artifacts/path dir "session.edn"))
   :messages (lineage/path-string (artifacts/path dir "messages.edn"))
   :turns (lineage/path-string (artifacts/path dir "turns.edn"))
   :evals (lineage/path-string (artifacts/path dir "evals.edn"))
   :calls (lineage/path-string (artifacts/path dir "calls.edn"))
   :lineage (lineage/path-string (artifacts/path dir "lineage.edn"))
   :rehydration (lineage/path-string (artifacts/path dir "rehydration.edn"))
   :tree (lineage/path-string (artifacts/path dir "tree.edn"))})

(declare session-summary)

(defn- child-summary [dir child-dir opts]
  (let [rel (.relativize ^Path (artifacts/path dir) ^Path (artifacts/path child-dir))]
    (try
      (assoc (session-summary child-dir opts)
             :kind :child
             :child/dir (str rel)
             :child/handle (lineage/child-handle dir (str rel)))
      (catch Throwable t
        {:kind :child
         :child/dir (str rel)
         :child/handle (lineage/child-handle dir (str rel))
         :status :malformed
         :warnings [{:warning/type :child/malformed
                     :path (str child-dir)
                     :message (.getMessage t)}]}))))

(defn session-summary
  ([dir] (session-summary dir {}))
  ([dir opts]
   (let [loaded (load-session dir)
         children (mapv #(child-summary dir % opts) (child-dirs dir))]
     {:handle (:handle loaded)
      :id (or (get-in loaded [:session :session/id])
              (some-> (io/file (str dir)) .getName))
      :path (lineage/path-string dir)
      :status (status loaded)
      :fingerprint (:fingerprint loaded)
      :turn-count (count (:turns loaded))
      :eval-count (count (:evals loaded))
      :call-count (count (:calls loaded))
      :message-count (count (:messages loaded))
      :child-count (count children)
      :latest-final (latest-final loaded)
      :turns (mapv #(turn-summary dir %) (:turns loaded))
      :evals (mapv #(eval-summary dir %) (:evals loaded))
      :calls (mapv #(call-summary dir %) (:calls loaded))
      :children children
      :lineage (:lineage loaded)
      :rehydration (:rehydration loaded)
      :usage (:usage loaded)
      :files (files dir)
      :warnings (:warnings loaded)})))
