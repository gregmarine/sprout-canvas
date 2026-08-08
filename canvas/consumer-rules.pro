# Consumer ProGuard/R8 rules for :canvas.
#
# AGP applies these automatically to any app that depends on this AAR, so a consuming app never
# has to copy keep rules for our internals.
#
# Phase 0: nothing to keep yet.
#
# Coming in later phases:
#   • Engine factories are discovered by reflection on a fixed FQCN list (PLAN.md D10), so each
#     ADAPTER module (:canvas-onyx, :canvas-supernote) ships the keep rule for its own factory in
#     its own consumer-rules.pro. That is deliberate — the rule travels with the code it protects,
#     so an app that does not depend on an adapter never carries a rule referencing a missing class.
