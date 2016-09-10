(ns com.hypirion.template-unquote
  (:refer-clojure :exclude [peek])
  (:import (java.io PushbackReader Reader EOFException)))

(def ^:dynamic *unquote-char* (int \~))
(def ^:dynamic *splice-char* (int \@))
(def ^:dynamic *inline-char* (int \#))

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
          ;; TODO: Support ~), ~}, ~] as quoted values
          (== c *unquote-char*) (let [c* (.read rdr)]
                               (cond (== c* *unquote-char*)
                                     ,,(do (.append sb (char c))
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

(def ^:private matching-inline-delimiter
  {(int \() (int \))
   (int \[) (int \])})

(def ^:private inline-delimiter-type
  {(int \() list
   (int \[) vector})

(declare parse-forms-until)

(defn ^:private parse-inline-form
  "Parses an inline form."
  [^PushbackReader rdr]
  (let [delimiter (.read rdr)
        end-delimiter (matching-inline-delimiter delimiter)]
    (when (== delimiter (int -1))
      (throw (EOFException. (str "Stream ends with " *unquote-char* *inline-char*))))
    (when-not end-delimiter
      (throw (Exception. (str (char *unquote-char*) (char *inline-char*)
                              " only supports [ and ( as delimiters, not "
                              (char delimiter)))))
    (let [first-value (read rdr)
          inline-data (parse-forms-until rdr end-delimiter)]
      [:inline-form ((inline-delimiter-type delimiter) first-value inline-data)])))

(defn parse-form-until
  "Parses a single unevaluated form or string from the given reader and returns
  it. Will parse until a new token or the character stop-val is found (eof = -1).
  Returns ::eof if we hit end-of-file with the first character."
  [^PushbackReader rdr stop-val]
  (let [c (.read rdr)]
    (cond (== c *unquote-char*)
          ;; Cases:
          ;; We have double-tilde: ~~ which is simply "~". String
          ;; We have tilde-at: ~@, which is splice-form
          ;; We have ~ as first char and not ~, @ or # as second char, which
          ;;   means we pass stuff down to read.
          (let [c* (.read rdr)]
            (cond (== c* *unquote-char*)
                  ,,(parse-string-until rdr stop-val (string-buffer (char c)))
                  (== c* *splice-char*)
                  ,,[:splice-form (read rdr)]
                  (== c* *inline-char*)
                  ,,(parse-inline-form rdr)
                  :otherwise
                  ,,(do (when-not (== c* (int -1))
                          (.unread rdr c*))
                        [:form (read rdr)])))
          ;; Saw the desired value. TODO: Pushback or ignore?
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
          ;; if this is used by other forms than inline-form.
          (throw (Exception. (str "Inside " (char *unquote-char*) (char *inline-char*)
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
  "Returns a lazy sequence of unevaluated forms and strings from rdr as
  specified by the template specification. If you're not sure if you need a lazy
  sequence or not, use parsed-seqv instead."
  [^Reader rdr]
  (parsed-lnpr-seq (PushbackReader. rdr 2)))

(defn parsed-seqv
  "Like parsed-seq, but eager and returns a vector."
  [^Reader rdr]
  (vec (parsed-seq rdr)))

