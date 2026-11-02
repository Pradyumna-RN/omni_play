# The bundled client calls only this annotated bridge surface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# No DSP implementation or coefficients are included in this project.
