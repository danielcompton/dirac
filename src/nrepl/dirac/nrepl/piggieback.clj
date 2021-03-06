; nREPL middleware enabling the transparent use of a ClojureScript REPL with nREPL tooling.
; taken from https://github.com/cemerick/piggieback/tree/440b2d03f944f6418844c2fab1e0361387eed543
; original author: Chas Emerick
; Eclipse Public License - v 1.0
;
; this file differs significantly from the original piggieback.clj and was modified to include Dirac-specific functionality
;
; main changes:
;   * removed generic code for supporting arbitrary repl environment
;   * supports only Dirac's WeaselREPLEnv
;   * uses recording driver when performing eval of ClojureScript code (see driver.clj)
;   * observes evaluation errors when performing eval of Clojure code

(ns dirac.nrepl.piggieback
  (:require [clojure.tools.nrepl :as nrepl]
            (clojure.tools.nrepl [transport :as transport]
                                 [misc :refer (response-for returning)]
                                 [middleware :refer (set-descriptor!)])
            [clojure.tools.nrepl.middleware.interruptible-eval :as nrepl-ieval]
            [clojure.tools.nrepl.transport :as nrepl-transport]
            clojure.main
            cljs.repl
            [cljs.env :as env]
            [cljs.analyzer :as ana]
            [dirac.nrepl.state :refer [*cljs-repl-env* *cljs-compiler-env* *cljs-repl-options* *original-clj-ns*]]
            [dirac.nrepl.driver :as driver]
            [dirac.nrepl.version :refer [version]]
            [dirac.nrepl.sessions :as sessions]
            dirac.nrepl.controls
            [dirac.logging :refer [pprint]]
            [clojure.tools.logging :as log])
  (:import clojure.lang.LineNumberingPushbackReader
           java.io.StringReader
           java.io.Writer
           (clojure.tools.nrepl.transport Transport)
           (clojure.lang IDeref))
  (:refer-clojure :exclude (load-file)))

(def ^:const dirac-repl-alias "<dirac repl>")

; -- dirac-specific wrapper for evaluated forms -----------------------------------------------------------------------------

(defn safe-value-conversion-to-string [value]
  ; darwin: I have a feeling that these cljs.core bindings should not be hard-coded.
  ;         I understand that printing must be limited somehow. But this should be user-configurable.
  ;         Dirac REPL does not use returned string value - but normal REPL clients are affected by this.
  `(binding [cljs.core/*print-level* 1
             cljs.core/*print-length* 10]
     (cljs.core/pr-str ~value)))

(defn make-wrap-for-job [job-id]
  (fn [form]
    `(try
       (js/dirac.runtime.repl.present_repl_result ~job-id ~form)
       (catch :default e#
         (js/dirac.runtime.repl.present_repl_exception ~job-id e#)
         (throw e#)))))

(defn make-special-form-evaluator [dirac-wrap]
  (fn [form]
    (safe-value-conversion-to-string (dirac-wrap form))))

(defn make-job-evaluator [dirac-wrap job-id]
  (fn [form]
    (let [result-sym (gensym "result")]
      `(try
         ; we want to redirect all side-effect printing to dirac.runtime, so it can be presented in the Dirac REPL console
         (binding [cljs.core/*print-newline* false
                   cljs.core/*print-fn* (partial js/dirac.runtime.repl.present_output ~job-id "stdout")
                   cljs.core/*print-err-fn* (partial js/dirac.runtime.repl.present_output ~job-id "stderr")]
           (let [~result-sym ~(dirac-wrap form)]
             (set! *3 *2)
             (set! *2 *1)
             (set! *1 ~result-sym)
             ~(safe-value-conversion-to-string result-sym)))
         (catch :default e#
           (set! *e e#)
           (throw e#))))))

(defn make-wrapper-for-form [form]
  (let [nrepl-message nrepl-ieval/*msg*
        dirac-mode (:dirac nrepl-message)
        job-id (or (:id nrepl-message) 0)
        dirac-wrap (case dirac-mode
                     "wrap" (make-wrap-for-job job-id)
                     identity)]
    (cond
      (and (seq? form) (= 'ns (first form))) identity
      ('#{*1 *2 *3 *e} form) (make-special-form-evaluator dirac-wrap)
      :else (make-job-evaluator dirac-wrap job-id))))

(defn eval-cljs
  "Given a REPL evaluation environment, an analysis environment, and a
   form, evaluate the form and return the result. The result is always the value
   represented as a string."
  ([repl-env env form]
   (eval-cljs repl-env env form cljs.repl/*repl-opts*))
  ([repl-env env form opts]
   (let [wrap ((or (:wrap opts) make-wrapper-for-form) form)
         env (assoc env :ns (ana/get-namespace ana/*cljs-ns*))]                                                               ; the pluggability of :wrap is needed for older JS runtimes like Rhino where catching the error will swallow the original trace
     (log/trace "eval-cljs" form)
     (cljs.repl/evaluate-form repl-env env dirac-repl-alias form wrap opts))))

(defn- run-single-cljs-repl-iteration [nrepl-message code repl-env compiler-env options]
  (let [{:keys [session transport ns]} nrepl-message
        initns (if ns (symbol ns) (@session #'ana/*cljs-ns*))
        flush (fn [driver]
                (.flush ^Writer (@session #'*out*))
                (.flush ^Writer (@session #'*err*))
                (driver/flush! driver))
        send-response-fn (fn [response-msg]
                           (transport/send transport (response-for nrepl-message response-msg)))
        print-fn (fn [driver result & _rest]
                   (flush driver)                                                                                             ; make sure that all *printed* output is flushed before sending results of evaluation
                   (if (or (not ns) (not= initns ana/*cljs-ns*))
                     (swap! session assoc #'ana/*cljs-ns* ana/*cljs-ns*))
                   (if (::first-cljs-repl nrepl-message)
                     ; the first run through the cljs REPL is effectively part
                     ; of setup; loading core, (ns cljs.user ...), etc, should
                     ; not yield a value. But, we do capture the compiler
                     ; environment now (instead of attempting to create one to
                     ; begin with, because we can't reliably replicate what
                     ; cljs.repl/repl* does in terms of options munging
                     (set! *cljs-compiler-env* env/*compiler*)
                     ; if the CLJS evaluated result is nil, then we can assume
                     ; what was evaluated was a cljs.repl special fn (e.g. in-ns,
                     ; require, etc)
                     (send-response-fn {:value         (or result "nil")
                                        :printed-value 1
                                        :ns            (@session #'ana/*cljs-ns*)})))
        start-repl (fn [driver repl-env repl-options]
                     (let [effective-repl-options (assoc repl-options
                                                    :flush (partial flush driver)
                                                    :print (partial print-fn driver))]
                       (driver/start-job! driver (:id nrepl-ieval/*msg*))
                       (cljs.repl/repl* repl-env effective-repl-options)
                       (driver/stop-job! driver)))]
    ; MAJOR TRICK HERE! we append :cljs/quit to our code which needs to be evaluated,
    ; this will cause cljs.repl's loop to exit after the first eval
    (binding [*in* (-> (str code " :cljs/quit") StringReader. LineNumberingPushbackReader.)
              *out* (@session #'*out*)
              *err* (@session #'*err*)
              ana/*cljs-ns* initns]
      (let [base-options {:need-prompt  (constantly false)
                          :bind-err     false
                          :quit-prompt  (fn [])
                          :init         (fn [])
                          :prompt       (fn [])
                          :eval         eval-cljs
                          :compiler-env compiler-env}
            repl-options (merge base-options options)]
        (driver/start-repl-with-driver repl-env repl-options start-repl send-response-fn)))))

; This function always executes when the nREPL session is evaluating Clojure,
; via interruptible-eval, etc. This means our dynamic environment is in place,
; so set! and simple dereferencing is available. Contrast w/ evaluate and
; load-file below.
(defn start-cljs-repl!
  "Starts a ClojureScript REPL over top an nREPL session.
   Accepts all options usually accepted by e.g. cljs.repl/repl."
  [repl-env & {:as options}]
  ; TODO I think we need a var to set! the compiler environment from the REPL environment after each eval
  (try
    (let [init-code (nrepl/code (ns cljs.user
                                  (:require [cljs.repl :refer-macros (source doc find-doc apropos dir pst)])))
          nrepl-message (assoc nrepl-ieval/*msg* ::first-cljs-repl true)]                                                     ; initial cljs-repl-iteration is hadnled specially
      (set! ana/*cljs-ns* 'cljs.user)
      (run-single-cljs-repl-iteration nrepl-message init-code repl-env nil options)                                           ; this will implicitly set! *cljs-compiler-env*
      (set! *cljs-repl-env* repl-env)
      (set! *cljs-repl-options* options)
      (set! *original-clj-ns* *ns*)                                                                                           ; interruptible-eval is in charge of emitting the final :ns response in this context
      (set! *ns* (find-ns ana/*cljs-ns*)))
    (catch Exception e
      (set! *cljs-repl-env* nil)
      (throw e))))

;; mostly a copy/paste from interruptible-eval
(defn enqueue [nrepl-message func]
  (let [{:keys [session transport]} nrepl-message
        job (fn []
              (alter-meta! session
                           assoc
                           :thread (Thread/currentThread)
                           :eval-msg nrepl-message)
              (binding [nrepl-ieval/*msg* nrepl-message]
                (func)
                (transport/send transport (response-for nrepl-message :status :done)))
              (alter-meta! session
                           dissoc
                           :thread
                           :eval-msg))]
    (nrepl-ieval/queue-eval session @nrepl-ieval/default-executor job)))

; only executed within the context of an nREPL session having *cljs-repl-env*
; bound. Thus, we're not going through interruptible-eval, and the user's
; Clojure session (dynamic environment) is not in place, so we need to go
; through the `session` atom to access/update its vars. Same goes for load-file.
(defn evaluate [nrepl-message]
  (let [{:keys [session transport ^String code]} nrepl-message]
    ; we append a :cljs/quit to every chunk of code evaluated so we can break out of cljs.repl/repl*'s loop,
    ; so we need to go a gnarly little stringy check here to catch any actual user-supplied exit
    (if-not (.. code trim (endsWith ":cljs/quit"))
      (let [repl-env (@session #'*cljs-repl-env*)
            compiler-env (@session #'*cljs-compiler-env*)
            options (@session #'*cljs-repl-options*)]
        (run-single-cljs-repl-iteration nrepl-message code repl-env compiler-env options))
      (let [actual-repl-env (@session #'*cljs-repl-env*)]
        (reset! (:cached-setup actual-repl-env) :tear-down)                                                                   ; TODO: find a better way
        (cljs.repl/-tear-down actual-repl-env)
        (sessions/remove-session-descriptor! session)
        (swap! session assoc
               #'*ns* (@session #'*original-clj-ns*)
               #'*cljs-repl-env* nil
               #'*cljs-compiler-env* nil
               #'*cljs-repl-options* nil
               #'ana/*cljs-ns* 'cljs.user)
        (transport/send transport (response-for nrepl-message
                                                :value "nil"
                                                :printed-value 1
                                                :ns (str (@session #'*original-clj-ns*))))))))

; struggled for too long trying to interface directly with cljs.repl/load-file,
; so just mocking a "regular" load-file call
; this seems to work perfectly, *but* it only loads the content of the file from
; disk, not the content of the file sent in the message (in contrast to nREPL on
; Clojure). This is necessitated by the expectation of cljs.repl/load-file that
; the file being loaded is on disk, in the location implied by the namespace
; declaration.
; TODO either pull in our own `load-file` that doesn't imply this, or raise the issue upstream.
(defn load-file [nrepl-message]
  (let [{:keys [file-path]} nrepl-message]
    (evaluate (assoc nrepl-message :code (format "(load-file %s)" (pr-str file-path))))))

; -- nrepl-message error observer -------------------------------------------------------------------------------------------

(defn get-session-exception [session]
  {:pre [(instance? IDeref session)]}
  (@session #'clojure.core/*e))

(defn get-nrepl-message-info [nrepl-message]
  (let [{:keys [op code]} nrepl-message]
    (str "op: '" op "'" (if (some? code) (str " code: " code)))))

(defn get-exception-details [nrepl-message e]
  (let [details (driver/capture-exception-details e)
        message-info (get-nrepl-message-info nrepl-message)]
    (str message-info "\n" details)))

(defrecord ErrorsObservingTransport [nrepl-message transport]
  Transport
  (recv [_this timeout]
    (nrepl-transport/recv transport timeout))
  (send [_this reply-message]
    (let [effective-message (if (some #{:eval-error} (:status reply-message))
                              (let [e (get-session-exception (:session nrepl-message))
                                    details (get-exception-details nrepl-message e)]
                                (log/error (str "Clojure eval error: " details))
                                (assoc reply-message :details details))
                              reply-message)]
      (nrepl-transport/send transport effective-message))))

(defn observed-nrepl-message [nrepl-message]
  ; This is a little trick due to unfortunate fact that clojure.tools.nrepl.middleware.interruptible-eval/evaluate does not
  ; offer configurable :caught option. The problem is that eval errors in Clojure REPL are not printed to stderr
  ; for some reasons and reported exception in response message is not helpful.
  ;
  ; Our strategy here is to wrap :transport with our custom implementation which observes send calls and enhances :eval-error
  ; messages with more details. It relies on the fact that :caught implementation
  ; in clojure.tools.nrepl.middleware.interruptible-eval/evaluate sets exception into *e binding in the session atom.
  ;
  ; Also it uses our logging infrastructure to log the error which should be displayed in console (assuming default log
  ; levels)
  (update nrepl-message :transport (partial ->ErrorsObservingTransport nrepl-message)))

; -- handlers for middleware operations -------------------------------------------------------------------------------------

(defn safe-pr-str [value & [level length]]
  (binding [*print-level* (or level 5)
            *print-length* (or length 100)]
    (pr-str value)))

(defrecord LoggingTransport [nrepl-message transport]
  Transport
  (recv [_this timeout]
    (nrepl-transport/recv transport timeout))
  (send [_this reply-message]
    (log/debug "sending raw message via nREPL transport:\n" (pprint reply-message))
    (nrepl-transport/send transport reply-message)))

(defn logged-nrepl-message [nrepl-message]
  (update nrepl-message :transport (partial ->LoggingTransport nrepl-message)))

(defn make-print-output-message [base job-id output-kind content]
  (-> base
      (dissoc :out)
      (dissoc :err)
      (merge {:op      :print-output
              :id      job-id
              :kind    output-kind
              :content content})))

(defrecord OutputCapturingTransport [nrepl-message transport]
  Transport
  (recv [_this timeout]
    (nrepl-transport/recv transport timeout))
  (send [_this reply-message]
    (if-let [content (:out reply-message)]
      (nrepl-transport/send transport (make-print-output-message reply-message (:id nrepl-message) :stdout content)))
    (if-let [content (:err reply-message)]
      (nrepl-transport/send transport (make-print-output-message reply-message (:id nrepl-message) :stderr content)))
    (nrepl-transport/send transport reply-message)))

(defn make-nrepl-message-with-captured-output [nrepl-message]
  (update nrepl-message :transport (partial ->OutputCapturingTransport nrepl-message)))

(defn dirac-special-command? [code]
  (boolean (re-find #"^\(dirac! " code)))                                                                                     ; we don't want to use read-string here, regexp test should be safe and quick

(defn repl-eval! [nrepl-message code ns]
  (let [{:keys [transport session]} nrepl-message
        bindings @session
        out (bindings #'*out*)
        err (bindings #'*err*)]
    (let [result (with-bindings bindings
                   (try
                     (let [form (read-string code)]
                       (binding [*ns* ns
                                 nrepl-ieval/*msg* nrepl-message]
                         (eval form)))
                     (catch Throwable e
                       (let [root-ex (clojure.main/root-cause e)
                             details (get-exception-details nrepl-message e)]
                         (log/error (str "Clojure eval error during eval of a special dirac command: " details))
                         (transport/send transport (response-for nrepl-message
                                                                 :status :eval-error
                                                                 :ex (-> e class str)
                                                                 :root-ex (-> root-ex class str)
                                                                 :details details))
                         (clojure.main/repl-caught e)
                         ::exception))                                                                                        ; this will trigger :err message
                     (finally
                       (.flush ^Writer out)
                       (.flush ^Writer err))))]
      (if (or (= result ::exception) (= result :dirac.nrepl.controls/no-result))
        (transport/send transport (response-for nrepl-message
                                                :status :done))
        (transport/send transport (response-for nrepl-message
                                                :status :done
                                                :value (safe-pr-str result)
                                                :printed-value 1))))))

(defn handle-dirac-eval-command! [nrepl-message]
  (let [{:keys [code session]} nrepl-message
        message (if (sessions/in-cljs-repl? session)
                  (make-nrepl-message-with-captured-output nrepl-message)
                  nrepl-message)]
    (repl-eval! message code (find-ns 'dirac.nrepl.controls))))                                                               ; we want to eval special commands in dirac.nrepl.controls namespace

(defn make-mock-nrepl-message [nrepl-message target-session]
  (let [mock-message (assoc nrepl-message
                       :session target-session
                       ; :transport TODO: retargeting transport
                       )]
    mock-message))

(defn send-empty-reply! [nrepl-message]
  (let [{:keys [transport]} nrepl-message]
    (transport/send transport (response-for nrepl-message
                                            :status :done
                                            :value "nil"
                                            :printed-value 1))))

(defn report-missing-target-session! []
  (println "report-missing-target-session!"))                                                                                 ; TODO

(defn enqueue-command! [command nrepl-message]
  (enqueue nrepl-message #(command nrepl-message)))

(defn enqueue-command-in-joined-session! [command nrepl-message]
  (let [{:keys [session]} nrepl-message]
    (if-let [joined-session-descriptor (sessions/find-joined-session-descriptor session)]
      (let [mock-nrepl-message (make-mock-nrepl-message nrepl-message (:session joined-session-descriptor))]
        (enqueue-command! command mock-nrepl-message))
      (do
        (report-missing-target-session!)
        (send-empty-reply! nrepl-message)))))

(defn identify-dirac-nrepl-middleware! [_next-handler nrepl-message]
  (let [{:keys [transport]} nrepl-message]
    (transport/send transport (response-for nrepl-message
                                            :version version))))

(defn handle-eval! [next-handler nrepl-message]
  (let [{:keys [session code]} nrepl-message]
    (cond
      (dirac-special-command? code) (handle-dirac-eval-command! nrepl-message)
      (sessions/in-cljs-repl? session) (enqueue-command! evaluate nrepl-message)
      (sessions/has-joined-session? session) (enqueue-command-in-joined-session! evaluate nrepl-message)
      :else (next-handler (observed-nrepl-message nrepl-message)))))

(defn handle-load-file! [next-handler nrepl-message]
  (let [{:keys [session]} nrepl-message]
    (if (sessions/in-cljs-repl? session)
      (enqueue-command! load-file nrepl-message)
      (next-handler (observed-nrepl-message nrepl-message)))))

; -- nrepl middleware -------------------------------------------------------------------------------------------------------

(defn dirac-nrepl-middleware [next-handler]
  (fn [nrepl-message]
    (let [nrepl-message (logged-nrepl-message nrepl-message)
          {:keys [session op]} nrepl-message]
      (log/trace "dirac-nrepl-middleware:" op "\n" (pprint nrepl-message))
      (sessions/ensure-bindings! session)
      (case op
        "identify-dirac-nrepl-middleware" (identify-dirac-nrepl-middleware! next-handler nrepl-message)
        "eval" (handle-eval! next-handler nrepl-message)
        "load-file" (handle-load-file! next-handler nrepl-message)
        (next-handler nrepl-message)))
    nil))

; -- additional tools -------------------------------------------------------------------------------------------------------

; this message is sent to client after booting into a Dirac REPL
(defn send-bootstrap-info! [weasel-url]
  (let [{:keys [transport session] :as nrepl-message} nrepl-ieval/*msg*]
    (log/trace "send-bootstrap-info!" weasel-url "\n" (pprint nrepl-message))
    (assert nrepl-message)
    (assert transport)
    (assert session)
    (let [info-message {:op         :bootstrap-info
                        :weasel-url weasel-url
                        :ns         (@session #'ana/*cljs-ns*)}]
      (log/debug "sending :bootstrap-info" info-message)
      (transport/send transport (response-for nrepl-message info-message)))))

(defn weasel-launched! [weasel-url runtime-tag]
  (let [{:keys [session]} nrepl-ieval/*msg*]
    (sessions/add-session-descriptor! session runtime-tag)
    (send-bootstrap-info! weasel-url)))