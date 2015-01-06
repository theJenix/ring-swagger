(ns ring.swagger.swagger2
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [ring.util.response :refer :all]
            [ring.swagger.impl :refer :all]
            [schema.core :as s]
            [plumbing.core :refer :all :exclude [update]]
            ring.swagger.json ;; needed for the json-encoders
            [ring.swagger.common :refer :all]
            [ring.swagger.json-schema :as jsons]
            [org.tobereplaced.lettercase :as lc]
            [ring.swagger.swagger2-spec :as spec]))

(def Anything {s/Keyword s/Any})
(def Nothing {})

;;
;; 2.0 Json Schema changes
;;

(defn ->json [& args]
  (binding [jsons/*swagger-spec-version* "2.0"]
    (apply jsons/->json args)))

(defn properties [schema]
  (binding [jsons/*swagger-spec-version* "2.0"]
    (jsons/properties schema)))

;;
;; Schema transformations
;;

;; COPY from 1.2
(defn- full-name [path] (->> path (map name) (map lc/capitalized) (apply str) symbol))

;; COPY from 1.2
(defn- collect-schemas [keys schema]
  (cond
    (plain-map? schema)
    (if (and (seq (pop keys)) (s/schema-name schema))
      schema
      (with-meta
        (into (empty schema)
              (for [[k v] schema
                    :when (jsons/not-predicate? k)
                    :let [keys (conj keys (s/explicit-schema-key k))]]
                [k (collect-schemas keys v)]))
        {:name (full-name keys)}))

    (valid-container? schema)
    (contain schema (collect-schemas keys (first schema)))

    :else schema))

;; COPY from 1.2
(defn with-named-sub-schemas
  "Traverses a schema tree of Maps, Sets and Sequences and add Schema-name to all
   anonymous maps between the root and any named schemas in thre tree. Names of the
   schemas are generated by the following: Root schema name (or a generated name) +
   all keys in the path CamelCased"
  [schema]
  (collect-schemas [(or (s/schema-name schema) (gensym "schema"))] schema))

(defn extract-models [swagger]
  (let [route-meta      (->> swagger
                             :paths
                             vals
                             (map vals)
                             flatten)
        body-models     (->> route-meta
                             (map (comp :body :parameters)))
        response-models (->> route-meta
                             (map :responses)
                             (mapcat vals)
                             (map :schema))]
    (->> (concat body-models response-models)
         flatten
         (map with-named-sub-schemas)
         (map (juxt s/schema-name identity))
         (into {})
         vals)))

(defn transform [schema]
  (let [required (required-keys schema)
        required (if-not (empty? required) required)]
    (remove-empty-keys
      {:properties (properties schema)
       :required required})))

; COPY from 1.2
(defn collect-models [x]
  (let [schemas (atom {})]
    (walk/prewalk
      (fn [x]
        (when-let [schema (and
                            (plain-map? x)
                            (s/schema-name x))]
          (swap! schemas assoc schema (if (var? x) @x x)))
        x)
      x)
    @schemas))

(defn transform-models [schemas]
  (->> schemas
       (map collect-models)
       (apply merge)
       (map (juxt (comp keyword key) (comp transform val)))
       (into {})))

;;
;; Paths, parameters, responses
;;

(defmulti ^:private extract-body-parameter
  (fn [e]
    (if (instance? java.lang.Class e)
      e
      (class e))))

(defmethod extract-body-parameter clojure.lang.Sequential [e]
  (let [model (first e)
        schema-json (->json model)]
    (vector {:in          :body
             :name        (name (s/schema-name model))
             :description (or (:description schema-json) "")
             :required    true
             :schema      {:type  "array"
                           :items (dissoc schema-json :description)}})))

(defmethod extract-body-parameter clojure.lang.IPersistentSet [e]
  (let [model (first e)
        schema-json (->json model)]
    (vector {:in          :body
             :name        (name (s/schema-name model))
             :description (or (:description schema-json) "")
             :required    true
             :schema      {:type        "array"
                           :uniqueItems true
                           :items       (dissoc schema-json :description)}})))

(defmethod extract-body-parameter :default [model]
  (if-let [schema-name (s/schema-name model)]
    (let [schema-json (->json model)]
     (vector {:in          :body
              :name        (name schema-name)
              :description (or (:description schema-json) "")
              :required    true
              :schema      (dissoc schema-json :description)}))))

(defmulti ^:private extract-parameter first)

(defmethod extract-parameter :body [[_ model]]
  (extract-body-parameter model))

(defmethod extract-parameter :default [[type model]]
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :when (s/specific-key? k)
          :let [rk (s/explicit-schema-key k)]]
      (jsons/->parameter {:in type
                          :name (name rk)
                          :required (s/required-key? k)}
                         (->json v)))))

(defn convert-parameters [parameters]
  (into [] (mapcat extract-parameter parameters)))

;; TODO can we transform anonymous maps like this without $ref
(defn convert-response-messages [responses]
  (binding [jsons/*ignore-missing-mappings* true]
   (letfn [(response-schema [schema]
             (if-let [json-schema (->json schema)]
               json-schema
               (transform schema)))]
     (zipmap (keys responses)
             (map (fn [r] (update-in r [:schema] response-schema))
                  (vals responses))))))

(defn transform-operation
  "Returns a map with methods as keys and the Operation
   maps with parameters and responses transformed to comply
   with Swagger JSON spec as values"
  [operation]
  (for-map [[k v] operation]
    k (-> v
          (update-in-or-remove-key [:parameters] convert-parameters empty?)
          (update-in-or-remove-key [:responses] convert-response-messages empty?))))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn extract-paths-and-definitions [swagger]
  (let [paths (->> swagger
                   :paths
                   (reduce-kv (fn [acc k v]
                                (assoc acc
                                  (swagger-path k)
                                  (transform-operation v))) {}))
        definitions (->> swagger
                         extract-models
                         transform-models)]
    [paths definitions]))


;;
;; Schema
;;

(def swagger-defaults {:swagger  "2.0"
                       :info     {:title "Swagger API"
                                  :version "0.0.1"}
                       :produces ["application/json"]
                       :consumes ["application/json"]})

(s/defschema Parameters {(s/optional-key :body) s/Any
                         (s/optional-key :query) s/Any
                         (s/optional-key :path) s/Any
                         (s/optional-key :header) s/Any
                         (s/optional-key :formData) s/Any})

(s/defschema Operation (-> spec/Operation
                           (assoc (s/optional-key :parameters) Parameters)))

(s/defschema Swagger (-> spec/Swagger
                         (dissoc :paths :definitions)
                         (assoc :paths {s/Str {s/Keyword Operation}})))

;;
;; Public API
;;

(defn validate
  "validates input against the ring-swagger spec"
  [swagger]
  (s/check Swagger (merge swagger-defaults swagger)))

(s/defn swagger-json
  "produces the swagger-json from ring-swagger spec"
  [swagger :- Swagger] :- spec/Swagger
  (let [[paths definitions] (extract-paths-and-definitions swagger)]
    (merge
      swagger-defaults
      (-> swagger
          (assoc :paths paths)
          (assoc :definitions definitions)))))

;; https://github.com/swagger-api/swagger-spec/blob/master/schemas/v2.0/schema.json
;; https://github.com/swagger-api/swagger-spec/blob/master/examples/v2.0/json/petstore.json
;; https://github.com/swagger-api/swagger-spec/blob/master/examples/v2.0/json/petstore-with-external-docs.json
;; https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md
