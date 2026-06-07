(ns fractal.engine.observe-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fractal.engine.observe :as o]))

(deftest fit-or-stub
  (testing "small values show whole"
    (is (= "{:a 1}" (o/value-display {:a 1} o/ok-fit)))
    (is (= "[1 2 3]" (o/value-display [1 2 3] o/ok-fit)))
    (is (= "\"short\"" (o/value-display "short" o/ok-fit)))
    (is (= "nil" (o/value-display nil o/ok-fit)))
    (is (= ":kw" (o/value-display :kw o/ok-fit))))
  (testing "large values stub with kind + size only"
    (is (= "«vector, 1000 items»" (o/value-display (vec (range 1000)) o/ok-fit)))
    (is (= "«map, 200 entries»" (o/value-display (zipmap (range 200) (range 200)) o/ok-fit)))
    (is (= "«set, 300 items»" (o/value-display (set (range 300)) o/ok-fit)))
    (is (= "«string, 500 chars»" (o/value-display (apply str (repeat 500 "x")) o/ok-fit)))
    (is (= "«lazy-seq, 1000 items»" (o/value-display (map inc (range 1000)) o/ok-fit))))
  (testing "final-fit is more generous than ok-fit"
    (let [v (vec (range 100))]
      (is (str/starts-with? (o/value-display v o/final-fit) "[0 1 2")))))

(deftest inspect-text-bounded-and-chrome-stripped
  (let [txt (o/inspect-text (vec (range 200)))]
    (is (str/includes? txt "Class: clojure.lang.PersistentVector"))
    (is (str/includes? txt "Count: 200"))
    (is (str/includes? txt "...") "elides the tail")
    (is (not (str/includes? txt "View mode")) "interactive chrome stripped")
    (is (< (count txt) 3000) "bounded")))

(deftest render-observation-format
  (testing "ok block: stdout + fit value, with the still-open trailer"
    (let [recs [{:eval/block-index 0 :eval/status :ok :eval/stdout "hi\n" :eval/raw-value [1 2 3]}]
          obs  (o/render-observation recs {:final? false})]
      (is (str/includes? obs "Block 0:"))
      (is (str/includes? obs "hi"))
      (is (str/includes? obs "=> [1 2 3]"))
      (is (str/includes? obs "No FINAL was called; the turn is still open."))))
  (testing "error block shows the error message and no trailer when final"
    (let [recs [{:eval/block-index 0 :eval/status :error
                 :eval/error {:error/message "Divide by zero"}}]
          obs  (o/render-observation recs {:final? false})]
      (is (str/includes? obs "ERROR: Divide by zero"))))
  (testing "final block uses the final-fit cap and omits the trailer"
    (let [recs [{:eval/block-index 0 :eval/status :final :eval/raw-final {:answer 42}}]
          obs  (o/render-observation recs {:final? true})]
      (is (str/includes? obs "=> {:answer 42}"))
      (is (not (str/includes? obs "No FINAL"))))))

(deftest no-fence-nudge-text
  (is (str/includes? o/no-fence-nudge "No clojure block found"))
  (is (str/includes? o/no-fence-nudge "(FINAL v)")))
