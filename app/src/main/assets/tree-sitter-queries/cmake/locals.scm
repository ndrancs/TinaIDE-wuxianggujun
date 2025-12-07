; CMake local variable tracking

; Function/macro definitions create scope
(function_def) @local.scope
(macro_def) @local.scope

; Variable definitions
(normal_command
  (identifier) @_cmd
  .
  (argument
    (unquoted_argument) @local.definition.var)
  (#eq? @_cmd "set"))

; Function parameters
(function_command
  (argument
    (unquoted_argument) @local.definition.function)
  (argument
    (unquoted_argument) @local.definition.parameter)*)

; Macro parameters
(macro_command
  (argument
    (unquoted_argument) @local.definition.function)
  (argument
    (unquoted_argument) @local.definition.parameter)*)

; Variable references
(variable) @local.reference
