package com.example.lucy.vision

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

/**
 * Configura Firebase App Check de forma segura antes de inicializar UI o llamadas a Firebase AI Logic.
 * - En Debug: persiste de forma síncrona (`commit()`) el token de depuración recibido por Intent extra.
 * - En Release: resuelve `recaptcha_site_key` de recursos sin lanzar excepción si falta.
 */
object LucyAppCheckInitializer {

    private const val TAG = "LucyAppCheckInit"
    private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
    private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
    private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
    private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

    fun configureAppCheck(context: Context, intent: Intent?) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val initialized = FirebaseApp.initializeApp(context)
                if (initialized == null) {
                    Log.w(TAG, "FirebaseApp no pudo inicializarse (posible ausencia de google-services.json).")
                    return
                }
            }

            if (BuildConfig.DEBUG) {
                val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
                if (!debugToken.isNullOrEmpty()) {
                    val persistenceKey = FirebaseApp.getInstance().persistenceKey
                    val prefs = context.getSharedPreferences(
                        "$DEBUG_STORE_PREFIX$persistenceKey",
                        Context.MODE_PRIVATE
                    )
                    // Síncrono con commit() antes de instalar DebugAppCheckProviderFactory
                    prefs.edit()
                        .putString(DEBUG_SECRET_KEY, debugToken)
                        .commit()
                    Log.d(TAG, "App Check debug secret configurado.")
                } else {
                    Log.d(TAG, "Sin debug token explícito en Intent extra; usando proveedor Debug por defecto.")
                }

                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            } else {
                val resId = context.resources.getIdentifier(
                    RECAPTCHA_SITE_KEY_RES,
                    "string",
                    context.packageName
                )
                if (resId != 0) {
                    val siteKey = context.getString(resId)
                    if (siteKey.isNotBlank()) {
                        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                        )
                    } else {
                        Log.e(TAG, "recaptcha_site_key está vacío.")
                    }
                } else {
                    Log.e(TAG, "No se encontró el recurso string recaptcha_site_key.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando Firebase App Check", e)
        }
    }
}
