(ns com.hypirion.template-unquote-test
  (:require [clojure.test :refer :all]
            [com.hypirion.template-unquote :refer :all])
  (:import (java.io StringReader)))

(defn parse-string [s]
  (parsed-seq (StringReader. s)))

(deftest parse-tests
  (testing "basic parse tests"
    (are [s forms] (= (parse-string s) (quote forms))
      "foo bar" ([:string "foo bar"])
      "~~escaped~~" ([:string "~escaped~"])
      "~] ~)" ([:string "] )"])
      "~(foo bar)" ([:form (foo bar)])
      "~@(foo bar)" ([:splice-form (foo bar)])
      "~(baz)zap" ([:form (baz)] [:string "zap"])
      "foo ~(bar) baz" ([:string "foo "] [:form (bar)] [:string " baz"])
      "~#(foo /[v.v]/) quux" ([:inline-form (foo [[:string "/[v.v]/"]])]
                             [:string " quux"])
      "~#(foo~))" ([:inline-form (foo [[:string ")"]])])
      ;; nesting in inline forms
      "~#(foo bar~(baz)zap)" ([:inline-form (foo [[:string "bar"]
                                                 [:form (baz)]
                                                 [:string "zap"]])])
      "~#(foo~#[bar~#(baz)])" ([:inline-form (foo [[:inline-form
                                                 [bar [[:inline-form
                                                        (baz [])]]]]])])
      ;; Uh, it's legal because we do clojure.core/read so yeah
      "~ #=(clojure.lang.PersistentArrayMap/create {})" ([:form {}])))
  (testing "other symbols for evaluating"
    (binding [*unquote-char* \$
              *splice-char* \^
              *inline-char* \>]
      (are [s forms] (= (parse-string s) (quote forms))
        "foo bar" ([:string "foo bar"])
        "tilde: ~" ([:string "tilde: ~"])
        "$(foo bar)" ([:form (foo bar)])
        "$^(foo bar)" ([:splice-form (foo bar)])
        "$>(foo~bar~)" ([:inline-form (foo [[:string "~bar~"]])])
        "$>(a $>[:b $(c)])" ([:inline-form (a [[:inline-form [:b [[:form (c)]]]]])]))))
  (testing "errors"
    (is (thrown-with-msg? java.io.EOFException #"Stream ends with ~#"
                          (dorun (parse-string "~#"))))
    (is (thrown-with-msg? RuntimeException #"Inside ~# form: Expected \) at some point"
                          (dorun (parse-string "~#(foo bar baz banana"))))
    (is (thrown-with-msg? RuntimeException #"~# only supports \[ and \( as delimiters"
                          (dorun (parse-string "~#{}"))))
    (is (thrown-with-msg? RuntimeException #"Unmatched delimiter: \]"
                          (dorun (parse-string "~(]"))))
    (is (thrown-with-msg? RuntimeException #"Unmatched delimiter: \]"
                          (dorun (parse-string "~@(]"))))
    (is (thrown-with-msg? RuntimeException #"EOF while reading"
                          (dorun (parse-string "~"))))))

