; C language highlights (simplified, compatible)

; =======================================================================
; Keywords
; =======================================================================

[
  "break"
  "case"
  "continue"
  "default"
  "do"
  "else"
  "for"
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
  "const"
  "extern"
  "inline"
  "static"
  "volatile"
  "register"
] @keyword

; =======================================================================
; Types
; =======================================================================

[
  "bool"
  "char"
  "double"
  "float"
  "int"
  "long"
  "short"
  "signed"
  "unsigned"
  "void"
] @type.builtin

(type_identifier) @type
(primitive_type) @type.builtin

; =======================================================================
; Literals
; =======================================================================

(number_literal) @number
(char_literal) @string
(string_literal) @string
(true) @constant.builtin
(false) @constant.builtin
(null) @constant.builtin

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
; Punctuation
; =======================================================================

["(" ")" "[" "]" "{" "}"] @punctuation.bracket
["," ";"] @punctuation.delimiter

; =======================================================================
; Functions
; =======================================================================

(call_expression function: (identifier) @function)
(function_declarator declarator: (identifier) @function)

; =======================================================================
; Structs
; =======================================================================

(struct_specifier name: (type_identifier) @type)
(union_specifier name: (type_identifier) @type)

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
