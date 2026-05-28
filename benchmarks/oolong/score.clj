(ns score
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]))

(def rows (range 50 60))

(defn read-json [path]
  (json/parse-string (slurp path) true))

(defn expected [row-idx]
  (:answer (read-json (format "benchmarks/oolong/samples/spam-2k-row-%d.row.json" row-idx))))

(defn normalize [x]
  (let [s (-> (pr-str x)
              (str/replace #"(?i)answer:" "")
              (str/replace #"(?i)label:" "")
              (str/replace #"(?i)\bclass\b" "")
              (str/replace #"[\[\]\"',:]" " ")
              (str/replace #"\s+" " ")
              str/trim
              str/lower-case)]
    (cond
      (str/includes? s "less common than") "less common than"
      (str/includes? s "more common than") "more common than"
      (str/includes? s "same frequency as") "same frequency as"
      :else
      (if-let [[_ n] (re-matches #".*?(-?\d+).*" s)]
        (Long/parseLong n)
        s))))

(defn missing-ref? [x]
  (= :fractal-engine.artifacts/missing x))

(defn read-rlm-answer [session]
  (let [dir (str "runs/" session)
        final-file (io/file dir "final.edn")]
    (when (.exists final-file)
      (let [final (edn/read-string (slurp final-file))
            value (artifacts/read-ref dir (:final/value-ref final))]
        (when-not (missing-ref? value)
          (:answer value value))))))

(defn read-pi-answer [row-idx]
  (let [f (io/file (format "benchmarks/oolong/pi-runs/spam-2k-row-%d.out" row-idx))]
    (when (.exists f)
      (let [text (slurp f)]
        (or (second (re-find #"(?s):answer\s+\"([^\"]+)\"" text))
            (second (re-find #"(?s):answer\s+([^\n\r}]+)" text))
            text)))))

(defn score-one [lane row-idx]
  (let [gold (expected row-idx)
        got (case lane
              :vertex (read-rlm-answer (format "oolong-rlm-spam-2k-row-%d" row-idx))
              :codex (read-rlm-answer (format "oolong-codex-spam-2k-row-%d" row-idx))
              :pi (read-pi-answer row-idx))
        ok? (and (some? got)
                 (= (normalize gold) (normalize got)))]
    {:lane lane
     :row row-idx
     :gold gold
     :got got
     :normalized-gold (normalize gold)
     :normalized-got (some-> got normalize)
     :ok? ok?}))

(defn -main [& _]
  (let [results (for [lane [:vertex :codex :pi]
                      row rows]
                  (score-one lane row))
        by-lane (group-by :lane results)]
    (doseq [lane [:vertex :codex :pi]]
      (let [xs (get by-lane lane)
            complete (filter :got xs)
            correct (filter :ok? xs)]
        (println (name lane)
                 "complete" (count complete) "/" (count xs)
                 "correct" (count correct) "/" (count complete))
        (doseq [r xs]
          (println " " (:row r)
                   (if (:got r) (if (:ok? r) "OK" "MISS") "PENDING")
                   "gold=" (:normalized-gold r)
                   "got=" (:normalized-got r)))))
    (spit "benchmarks/oolong/results.edn" (with-out-str (prn (vec results))))))

(when *file*
  (apply -main *command-line-args*))
