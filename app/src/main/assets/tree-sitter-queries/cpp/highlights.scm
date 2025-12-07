; C++ language highlights (simplified, compatible)

; =======================================================================
; Keywords
; =======================================================================

[
  "break"
  "case"
  "concept"
  "consteval"
  "constinit"
  "co_await"
  "co_return"
  "co_yield"
  "continue"
  "default"
  "do"
  "else"
  "for"
  "final"
  "goto"
  "if"
  "return"
  "switch"
  "while"
  "enum"
  "struct"
  "typedef"
  "union"
  "sizeof"
  "class"
  "namespace"
  "typename"
  "template"
  "private"
  "protected"
  "public"
  "explicit"
  "override"
  "friend"
  "virtual"
  "delete"
  "new"
  "requires"
  "operator"
  "using"
  "catch"
  "throw"
  "try"
  "noexcept"
  "const"
  "extern"
  "inline"
  "static"
  "volatile"
  "constexpr"
  "mutable"
] @keyword

; =======================================================================
; Types
; =======================================================================

(type_identifier) @type
(primitive_type) @type.builtin

; =======================================================================
; Literals
; =======================================================================

(number_literal) @number
(char_literal) @string
(string_literal) @string
(raw_string_literal) @string
(true) @constant.builtin
(false) @constant.builtin
(null) @constant.builtin
"nullptr" @constant.builtin
(this) @variable.builtin

; =======================================================================
; Comments
; =======================================================================

(comment) @comment

; =======================================================================
; Preprocessor
; =======================================================================

(preproc_directive) @keyword.directive
(preproc_def name: (identifier) @constant.macro)
(preproc_function_def name: (identifier) @function.macro)
(system_lib_string) @string

; =======================================================================
; Operators & Punctuation
; =======================================================================

["(" ")" "[" "]" "{" "}"] @punctuation.bracket
["," ";"] @punctuation.delimiter

; =======================================================================
; Namespaces
; =======================================================================

(namespace_definition name: (namespace_identifier) @namespace)
(namespace_identifier) @namespace

; =======================================================================
; Functions
; =======================================================================

(call_expression function: (identifier) @function)
(call_expression function: (qualified_identifier name: (identifier) @function))
(function_declarator declarator: (identifier) @function)
(function_declarator declarator: (qualified_identifier name: (identifier) @function))
(function_declarator declarator: (field_identifier) @function)

; =======================================================================
; Classes
; =======================================================================

(class_specifier name: (type_identifier) @type)
(struct_specifier name: (type_identifier) @type)

; =======================================================================
; Variables & Fields
; =======================================================================

(identifier) @variable
(field_identifier) @property

; =======================================================================
; Parameters
; =======================================================================

(parameter_declaration declarator: (identifier) @variable.parameter)

; =======================================================================
; Enums
; =======================================================================

(enum_specifier name: (type_identifier) @type)
(enumerator name: (identifier) @constant)
