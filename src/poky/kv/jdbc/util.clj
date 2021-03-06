(ns poky.kv.jdbc.util
  (:require (poky [util :as util])
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clojure.tools.logging :refer [warn infof errorf]]
            [environ.core :refer [env]])
  (:import com.mchange.v2.c3p0.ComboPooledDataSource
     [java.sql SQLException Timestamp]))

(def ^:private default-min-pool-size 3)
(def ^:private default-max-pool-size 15)
(def ^:private default-driver "org.postgresql.Driver")
(def ^:private poky-column-types
  {:bucket "text"
   :key "text"
   :data "text"
   :created_at "timestamptz"
   :modified_at "timestamptz"})

; when partitioning, the bucket names will be used to create tables so we need
; to ensure that they are valid table names. currently limiting them to words
; and _
(def ^:private valid-partitioned-bucket-regex #"^[^\d][\w_]+")

(defn wrap-join [b d a coll] (str b (string/join d coll) a))

(defn pg-array [coll] (wrap-join "ARRAY[" \, \] coll))

(defn pg-mget-param [k m]
  (str \( k \, m ")::mget_param_row"))

(defn create-db-spec
  "Given a dsn and optionally a driver create a db spec that can be used with pool to
  create a connection pool."
  ([dsn driver]
   (let [uri (java.net.URI. dsn)
         host (.getHost uri)
         scheme (.getScheme uri)
         [user pass] (clojure.string/split (.getUserInfo uri) #":")
         port (.getPort uri)
         port (if (= port -1) 5432 port)
         path (.substring (.getPath uri) 1)]
     {:classname driver
      :subprotocol scheme
      :subname (str "//" host ":" port "/" path)
      :user user
      :password pass}))
  ([dsn]
   (create-db-spec dsn default-driver)))

(defn pool
  "Create a connection pool."
  [spec &{:keys [min-pool-size max-pool-size]
          :or {min-pool-size default-min-pool-size max-pool-size default-max-pool-size}}]
  (infof "Creating pool with min %d and max %d connections." min-pool-size max-pool-size)
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass (:classname spec))
               (.setJdbcUrl (str "jdbc:" (:subprotocol spec) ":" (:subname spec)))
               (.setUser (:user spec))
               (.setPassword (:password spec))
               (.setMinPoolSize min-pool-size)
               (.setMaxPoolSize max-pool-size)
               (.setMaxIdleTimeExcessConnections (* 30 60))
               (.setMaxIdleTime (* 3 60 60)))]
      {:datasource cpds}))

(defn create-connection
  "Create a connection and delay it."
  [dsn]
  (delay (pool (create-db-spec dsn) :max-pool-size (util/parse-int (env :max-pool-size default-max-pool-size)))))

(defn close-connection
  "Close the connection of a JdbcKeyValue object."
  [connection-object]
  (.close (:datasource connection-object)))

(defn format-sql-exception
  "Formats the contents of an SQLException and return string.
  Similar to clojure.java.jdbc/print-sql-exception, but doesn't write to *out*"
  [^SQLException exception]
  (let [^Class exception-class (class exception)]
    (format (str "%s:" \newline
                 " Message: %s" \newline
                 " SQLState: %s" \newline
                 " Error Code: %d")
            (.getSimpleName exception-class)
            (.getMessage exception)
            (.getSQLState exception)
            (.getErrorCode exception))))

(defn warn-sql-exception
  "Outputs a formatted SQLException to log warn"
  [^SQLException e]
  (doall (map #(warn (format-sql-exception %))
              (iterator-seq (.iterator e)))))

(defmacro with-logged-connection [conn & body]
    `(try
         (sql/with-connection ~conn
              ~@body)
         (catch SQLException e#
             (warn-sql-exception e#))))

(defn purge-bucket
  "Should only be used in testing."
  [conn b]
  (with-logged-connection conn
    (sql/with-query-results results ["SELECT purge_bucket(?) AS result" b]
      (first results))))

(defn child-tables-exist?
  "Returns true if child tables exist."
  [conn]
  (with-logged-connection conn
    (sql/with-query-results results ["SELECT c.relname AS child
                                     FROM pg_inherits
                                     JOIN pg_class AS c ON (inhrelid=c.oid)
                                     JOIN pg_class as p ON (inhparent=p.oid)
                                     WHERE p.relname = 'poky'"]
      (pos? (count results)))))


(defn valid-bucket-name?
  "Returns truthy if the bucket name is valid."
  [bucket]
  (re-matches valid-partitioned-bucket-regex bucket))

(defn using-partitioning?
  "Determine if partitining is being used."
  [conn]
  (or (env :poky-partitioned) (child-tables-exist? conn)))

(defn create-bucket
  "Creates a bucket if partitioning is being used. A noop in a flat structure.
  When partitioning is being used, returns truthy on success and nil on error."
  [conn b]
  (when (using-partitioning? conn)
    (if (valid-bucket-name? b)
      (with-logged-connection conn
        (sql/with-query-results results ["SELECT create_bucket_partition(?) AS result" b]
          (first results)))
      (errorf "Error, bucket name '%s' is not a valid partitioned bucket name. Must match letters and underscores only." b))))


(defn jdbc-get
  "Get the tuple at bucket b and key k. Returns a map with the attributes of the table."
  [conn b k]
  (with-logged-connection conn
    (sql/with-query-results
      results
      ["SELECT * FROM poky WHERE bucket=? AND key=?" b k]
      (first results))))

(defn jdbc-set
  "Set a bucket b and key k to value v. Returns a map with key :result upon success.
  The value at result will be one of \"inserted\", \"updated\" or \"rejected\"."
  ([conn b k v modified]
   (with-logged-connection conn
     (sql/with-query-results results ["SELECT upsert_kv_data(?, ?, ?, ?) AS result" b k v modified]
       (first results))))
  ([conn b k v]
   (with-logged-connection conn
     (sql/with-query-results results ["SELECT upsert_kv_data(?, ?, ?) AS result" b k v]
       (first results)))))

(defn jdbc-delete
  "Delete the value at bucket b and key k. Returns true on success and false if the
  tuple does not exist."
  [conn b k]
  (with-logged-connection conn
    (sql/with-query-results results ["SELECT delete_kv_data(?, ?) AS result" b k]
      (list (get (first results) :result)))))

;; ======== MULTI

(defn mget-sproc-conds
  "Returns tuple of [cond-sql cond-params] for conditions
  cond-sql is a string representation of PG array for sproc's 3rd argument"
  [conds]
  (let [param-pairs  (repeat  (count conds)  (pg-mget-param \? \?))
        value-pairs (map (juxt :key :modified_at) conds)]
    [param-pairs value-pairs]))

(defn mget-sproc
  "Returns the SQL params vector (ie [sql & params]) for the stored procedure,
  mget(bucket::text, (key::text, ts::timestamp)[])

  Example:
  (mget-sproc 'b' [{:key 'key1'} {:key 'key2'}) =>
  ['SELECT * FROM mget(?, ARRAY[(?, ?)::mget_param_row, (?, ?)::mget_param_row])' 'b' 'key1' 'modified_at1' 'key2' 'modified_at2']"
  [bucket conds]
  (when (not (empty? conds))
    (let [[cond-sql cond-params] (mget-sproc-conds conds)
          sql (wrap-join "SELECT * FROM mget(" \, \)
                         ["?"
                          (pg-array cond-sql)])]
      (apply vector sql bucket (flatten cond-params )))))

(defn jdbc-mget
  [conn bucket conds]
  (when-let [q (mget-sproc bucket conds)]
    (with-logged-connection conn
      (sql/with-query-results results q
        (doall results)))))

(defn jdbc-mset
  "Upserts multiple records in data. Records are hashmaps with following fields:
  :bucket      (required)
  :key         (required)
  :data        (required)
  :modified_at (optional)
  "
  [conn data]
  ; doing these individually may not be an efficient use of the connection, but
  ; it should avoid deadlock by doing each set individually
  (doall
    (map (fn [{b :bucket k :key v :data t :modified_at}]
           (if t
             (jdbc-set conn b k v t)
             (jdbc-set conn b k v))) data)))
