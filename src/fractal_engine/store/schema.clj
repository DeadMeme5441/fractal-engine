(ns fractal-engine.store.schema
  "Datahike schema for the derived session index.

  SQLite rows remain the canonical hot store; this schema describes the
  rebuildable query index over those rows and blob identities.")

(def schema
  [{:db/ident :blob/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :blob/sha256 :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :blob/size :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :blob/media-type :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :blob/encoding :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :blob/compression :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :blob/store :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :blob/key :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :blob/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :event/uid :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :event/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :event/session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :event/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :event/at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/row-kind :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :event/row-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/turn-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :event/message-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :event/eval-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :event/call-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :event/snapshot-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :event/head-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/invocation-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :event/source-session-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/source-head-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/payload-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}

   {:db/ident :session/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :session/local-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :session/cache-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :session/title :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :session/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :session/kind :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :session/origin :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :session/current-head :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :session/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :session/updated-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :session/ended-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :alias/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :alias/session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :alias/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :alias/updated-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :head/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :head/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :head/session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :head/basis :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :head/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :head/kind :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :head/fingerprint :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :head/state-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :head/state-version :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :head/final-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :head/snapshot-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :head/snapshot-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :head/final-summary :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :head/final-kind :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :head/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :head/turn-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :head/cache-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :head/message-through-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}

   {:db/ident :derivation/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :derivation/version :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :derivation/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :derivation/source-session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :derivation/source-head :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :derivation/target-session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :derivation/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :message/uid :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :message/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :message/session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :message/turn :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :message/role :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :message/content-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :message/call-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :message/char-count :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :message/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :turn/uid :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :turn/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :turn/session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :turn/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :turn/head-before :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :turn/head-after :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :turn/final-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :turn/final-summary :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :turn/started-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :turn/ended-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :eval/uid :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :eval/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :eval/session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :eval/turn :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :eval/message :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :eval/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :eval/code-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :eval/result-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :eval/final-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :eval/error-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :eval/observation-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :eval/started-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :eval/ended-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :call/uid :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :call/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :call/session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :call/turn :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :call/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :call/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :call/input-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :call/request-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :call/response-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :call/result-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :call/final-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :call/error-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :call/parent-eval-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :call/request-storage :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :call/request-message-count :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :call/request-system-hash :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :call/model :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :call/provider :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :call/input-tokens :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :call/output-tokens :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :call/cost-usd :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
   {:db/ident :call/cache-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :edge/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :batch/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :batch/index :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :child/session-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :child/logical-session-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :child/cache-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :child/head-before :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :child/head-after :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/call-row-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :call/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :call/completed-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :snapshot/uid :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :snapshot/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/turn :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/head :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/kind :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/eval-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/current-ns :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/var-count :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/restorable-count :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/unrestorable-count :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/message-through-id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :snapshot/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :invocation/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :invocation/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/caller-session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/caller-head-before :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/caller-head-after :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/callee-session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/callee-head-before :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/callee-head-after :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/call-uid :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/label :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/input-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/final-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :invocation/completed-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :attach/source-session :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :attach/source-head :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :attach/source-snapshot-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :attach/source-fingerprint :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
