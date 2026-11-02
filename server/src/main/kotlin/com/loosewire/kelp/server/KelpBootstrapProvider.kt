package com.loosewire.kelp.server

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.LightSdkServer

class KelpBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        with(LightSdkServer) {
            defaultClientFilterLevel = ClientFilterLevel.AllowLightSignedApks
            checkCertOverride = { packageName ->
                if (packageName == appContext.packageName) {
                    ClientCertType.LightSdkSignedUnverified
                } else {
                    ClientCertType.Unknown
                }
            }
            customServiceMethodResolver = { _, methodId, payload ->
                KelpServiceMethods.dispatch(methodId, payload)
            }
        }
        KelpServiceMethods.initialize(appContext)
        KelpRuntime.initialize(appContext)
        KelpRuntime.tidalAuth()?.credentialsProvider?.let { provider ->
            KelpServiceMethods.initializeCatalog(TidalCatalog(provider))
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
