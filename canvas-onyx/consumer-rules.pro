# Consumer ProGuard/R8 rules for :canvas-onyx.
#
# AGP applies these automatically to any app that depends on this AAR.
#
# The factory is found by reflection over a fixed class-name list in EngineRegistry (PLAN.md D10),
# so nothing in compiled code ever references it and R8 would otherwise be entirely correct to
# delete it. The failure that produces is the quiet kind: a shrunk release build on a BOOX falls
# back to the generic engine, draws perfectly well through software, and gives no hint that the
# hardware ink path was stripped at package time.
#
# The rule travels with the code it protects — an app that does not depend on this adapter never
# carries a keep rule naming a class it does not have.
-keep class com.symmetricalpalmtree.sprout.canvas.onyx.OnyxInkEngineFactory { *; }

# The SDK reaches hidden framework methods by reflection and keeps its own reflective registries.
# Onyx ships no consumer rules of its own, so shrinking its classes is the host app's problem to
# avoid — and one it has no way to diagnose.
-keep class com.onyx.android.sdk.** { *; }
-dontwarn com.onyx.android.sdk.**
