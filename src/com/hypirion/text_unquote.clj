(ns com.hypirion.text-unquote
  (:require [clojure.core.match :refer [match]])
  (:import (java.io Reader StringReader PushbackReader
                    Writer StringWriter
                    EOFException)))

(def ^:dynamic *unquote-char* \~)
(def ^:dynamic *splice-char* \@)
(def ^:dynamic *recur-char* \#)

(defn ^:private string-buffer
  "Returns a StringBuffer which contains the single character c."
  ^StringBuffer [c]
  (doto (StringBuffer.)
    (.append c)))

(defn ^:private parse-string-until
  [^PushbackReader rdr stop-val ^StringBuffer sb]
  (let [c (.read rdr)]
    (cond (== c (int -1)) [:string (.toString sb)]
          (== c stop-val) (do (.unread rdr c)
                             [:string (.toString sb)])
          ;; If next is *unquote-char*, then gobble a single unquote char,
          ;; otherwise pushback both and finish.
          (== c (int *unquote-char*)) (let [c* (.read rdr)]
                                       (cond (or (== c* (int *unquote-char*))
                                                 (== c* (int \]))
                                                 (== c* (int \))))
                                             ,,(do (.append sb (char c*))
                                                   (recur rdr stop-val sb))
                                             (== c* (int -1)) ;; funny edge cases
                                             ,,(do (.unread rdr c)
                                                   [:string (.toString sb)])
                                             :otherwise
                                             ,,(do (.unread rdr c*)
                                                   (.unread rdr c)
                                                   [:string (.toString sb)])))
          :otherwise (do (.append sb (char c))
                         (recur rdr stop-val sb)))))

(def ^:private matching-recur-delimiter
  {(int \() (int \))
   (int \[) (int \])})

(def ^:private recur-delimiter-type
  {(int \() list
   (int \[) vector})

(declare parse-forms-until)

(defn ^:private parse-recur-form
  "Parses a recur form."
  [^PushbackReader rdr]
  (let [delimiter (.read rdr)
        end-delimiter (matching-recur-delimiter delimiter)]
    (when (== delimiter (int -1))
      (throw (EOFException. (str "Stream ends with " *unquote-char* *recur-char*))))
    (when-not end-delimiter
      (throw (RuntimeException. (str *unquote-char* *recur-char*
                                     " only supports [ and ( as delimiters, not "
                                     (char delimiter)))))
    (let [first-value (read rdr)
          ;; Remove a single space if present
          _ (let [c (.read rdr)]
              (if-not (= (char c) \space)
                (.unread rdr c)))
          recur-data (parse-forms-until rdr end-delimiter)]
      [:recur-form ((recur-delimiter-type delimiter) first-value recur-data)])))

(defn parse-form-until
  "Parses a single unevaluated form or string from the given reader and returns
  it. Will parse until a new token or the character stop-val is found (eof = -1).
  Returns ::eof if we hit end-of-file with the first character."
  [^PushbackReader rdr stop-val]
  (let [c (.read rdr)]
    (cond (== c (int *unquote-char*))
          ;; Cases:
          ;; We have double-tilde: ~~ which is simply "~". String
          ;; We have tilde-at: ~@, which is splice-form
          ;; We have ~ as first char and not ~, @ or # as second char, which
          ;;   means we pass stuff down to read.
          (let [c* (.read rdr)]
            (cond (or (== c* (int \]))
                      (== c* (int \)))
                      (== c* (int *unquote-char*)))
                  ,,(parse-string-until rdr stop-val (string-buffer (char c*)))
                  (== c* (int *splice-char*))
                  ,,[:splice-form (read rdr)]
                  (== c* (int *recur-char*))
                  ,,(parse-recur-form rdr)
                  :otherwise
                  ,,(do (when-not (== c* (int -1))
                          (.unread rdr c*))
                        [:form (read rdr)])))
          (== c stop-val) nil
          (== c (int -1)) ::eof
          :otherwise (parse-string-until rdr stop-val (string-buffer (char c))))))

(defn parse-forms-until
  "Returns a vector of forms until stop-val is found. If end of file is found,
  this function throws an exception."
  [^PushbackReader rdr stop-val]
  (loop [forms []]
    (if-let [form (parse-form-until rdr stop-val)]
      (do
        (when (identical? form ::eof)
          ;; TODO: Well eh, this is a complect error message: Should change this
          ;; if this is used by other forms than recur-form.
          (throw (RuntimeException. (str "Inside " *unquote-char* *recur-char*
                                         " form: Expected " (char stop-val) " at some point,"
                                         " but EOF was found before that"))))
        (recur (conj forms form)))
      forms)))

(defn parse-form
  "Parses a single unevaluated form or string from the given reader and returns
  it. Will parse until a new token or eof is found."
  [^PushbackReader rdr]
  (parse-form-until rdr (int -2)))

(defn ^:private parsed-lnpr-seq
  [^PushbackReader rdr]
  (lazy-seq
   (let [val (parse-form rdr)]
     (if (identical? val ::eof)
       nil
       (cons val
             (parsed-lnpr-seq rdr))))))

(defn parsed-seq
  "Returns a lazy sequence of unevaluated forms and strings from rdr
  as specified by the text-unquote specification. If you're not sure
  if you need a lazy sequence or not, use parsed-vec instead."
  [^Reader rdr]
  (parsed-lnpr-seq (PushbackReader. rdr 2)))

(defn parsed-vec
  "Like parsed-seq, but eager and returns a vector."
  [^Reader rdr]
  (vec (parsed-seq rdr)))

(defn evaluate-form
  "Lazily transforms an element into values by sequentially evaluating
  forms with eval-fn. Nested forms are evaluated left-to-right."
  [form eval-fn]
  (match [form]
         [[:string s]] [s]
         [[:form f]] [(eval-fn f)]
         [[:splice-form f]] (eval-fn f)
         [[:recur-form f]] (let [[fst args] f
                                 eval-args (->> (mapcat #(evaluate-form % eval-fn) args)
                                              (map #(if (string? %) % (pr-str %)))
                                              (apply str))]
                              (if (vector? f)
                                [[fst eval-args]]
                                [(eval-fn (list fst eval-args))]))))

(defn evaluated-seq
  "Lazily transforms every element of coll into values by sequentially
  evaluating forms with eval-fn. Nested forms are evaluated
  left-to-right. If you're not sure if you need a lazy sequence or
  not, use evaluated-vec instead."
  [coll eval-fn]
  (mapcat #(evaluate-form % eval-fn) coll))

(defn evaluated-vec
  "Like evaluated-seq, but eager and returns a vector."
  [coll eval-fn]
  (vec (evaluated-seq coll eval-fn)))

(defn render
  "Renders content from the reader onto the writer with eval-fn as the
  evaluator."
  [^Reader rdr ^Writer wrt eval-fn]
  (doseq [data (-> rdr (parsed-seq) (evaluated-seq eval-fn))]
    (if (string? data)
      (.write wrt data)
      (.write wrt (pr-str data)))))

(defn render-string
  "Renders the input string with eval-fn and returns the result as a string."
  [s eval-fn]
  (let [wrt (StringWriter.)]
    (render (StringReader. s) wrt eval-fn)
    (str wrt)))

(defn eval-in-ns
  "Like eval, but performs the evaluation inside the given ns."
  [ns form]
  (binding [*ns* (the-ns ns)]
    (eval form)))

(defmacro with-tmp-ns
  "Creates a temporary namespace with the symbol name s which will be
  removed after execution"
  [[s] & body]
  `(let [~s (gensym "tmp_ns")]
     (try
       (create-ns ~s)
       ~@body
       (finally
         (remove-ns ~s)))))
