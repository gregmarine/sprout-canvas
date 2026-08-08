# Sprout Canvas Lab — the conformance harness is never minified today (isMinifyEnabled = false),
# so this file is a placeholder.
#
# Keep it: when the Onyx and Supernote adapters arrive, running the Lab through R8 at least once is
# the only way to prove their consumer-rules.pro keep rules actually protect the reflectively
# discovered engine factories (PLAN.md D10). A shrunk build that silently falls back to the generic
# engine is exactly the failure this repo must not ship.
