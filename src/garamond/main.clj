(ns garamond.main
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as string]
            [taoensso.timbre :as timbre]
            [garamond.git :as git]
            [garamond.pom :as pom]
            [garamond.logging :as logging]
            [garamond.version :as version]
            [garamond.version :as v]))

(def cli-options
  [["-h" "--help" "Print usage and exit"]
   ["-v" "--verbose" "Print more debugging logs" :default true]
   [nil "--prefix PREFIX" "Use this prefix in front of versions for tags"]
   ["-p" "--pom" "Generate or update the pom.xml file" :default false]
   ["-t" "--tag" "Create a new git tag based on the given version" :default false]
   ["-m" "--message MESSAGE" "Commit message for git tag"]
   ["-g" "--group-id GROUP-ID" "Update the pom.xml file with this <groupId> value"]
   ["-a" "--artifact-id ARTIFACT-ID" "Update the pom.xml file with this <artifactId> value"]
   [nil "--force-version VERSION" "Use this value for the pom.xml <version> tag"]])

(defn usage [options-summary]
  (->> ["garamond is a utility for printing and incrementing versions based on git tags."
        ""
        "Usage: clojure -m garamond.main [options] [increment-type]"
        ""
        "Options:"
        options-summary
        ""
        "With no increment type, garamond will print the current version number and exit."
        ""
        "The prefix string ('v' in the tag 'v1.2.3') will be preserved in the new tag, or"
        "it can be overridden via the -p option."
        ""
        "Increment types:"
        "  major              1.2.4 -> 2.0.0"
        "  minor              1.2.4 -> 1.3.0"
        "  patch              1.2.4 -> 1.2.5"
        "  major-rc           2.7.9 -> 3.0.0-rc.0, 4.0.0-rc.3 -> 4.0.0-rc.4"
        "  minor-rc           2.7.9 -> 2.8.0-rc.0, 4.3.0-rc.0 -> 4.3.0-rc.1"
        "  major-release      4.0.0-rc.4 -> 4.0.0, 3.2.9 -> 4.0.0"
        "  minor-release      8.1.0-rc.4 -> 8.2.0, 5.9.4 -> 5.10.0"
        ""
        "See https://github.com/workframers/garamond for more information."]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(def valid-inc-types #{:major :minor :patch :minor-rc :minor-release :major-rc :major-release})

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        action (some-> arguments first keyword)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}
      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}
      ;; custom validation on arguments
      (and (some? action) (not (contains? valid-inc-types action)))
      {:exit-message (format "Unknown increment type '%s'! Valid types: %s."
                             (name action) (->> valid-inc-types (map name) sort (string/join ", ")))}
      :else ; failed custom validation => exit with usage summary
      {:incr-type (or action :print-version) :options (assoc options :summary summary)})))

(defn exit
  ([]
   (exit 0))
  ([code]
   (exit code nil))
  ([code message]
   (throw (ex-info "Exit condition" {:code code :message message}))))

(defn- print-version [options status]
  (println (version/to-string (:version status) options)))

(defn- increment [incr-type options {:keys [version current]}]
  (version/increment version incr-type))

(defn- parse-forced-version [v-str]
  (v/parse v-str))

(defn -main [& args]
  (try
    (let [{:keys [incr-type options exit-message ok?]} (validate-args args)
          status (git/current-status)
          opts   (assoc options :prefix (or (:prefix options) (:prefix status) "v"))
          incr?  (not= :print-version incr-type)
          new-v  (cond
                   (:force-version opts) (parse-forced-version (:force-version opts))
                   incr?                 (increment incr-type opts status)
                   :else                 (:version status))]
      (when exit-message
        (exit (if ok? 0 1) exit-message))
      (logging/set-up-logging! options)
      (if incr?
        (timbre/infof "%s increment of %s -> %s" (name incr-type)
                      (:current status)
                      (version/to-string new-v options))
        (print-version opts status))

      (when (:pom opts)
        (pom/generate! new-v opts))

      (when (:tag opts) ; TODO: check for dirty filesystem
        (git/tag! new-v opts)))

    (catch Exception e
      (let [{:keys [code message]} (ex-data e)]
        (if message
          (binding [*out* *err*] (println message))
          (when-not code ; exception
            (timbre/error e (.getMessage e))))
        (System/exit (or code 128)))))
  (System/exit 0))
