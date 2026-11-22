package com.poscafe.pos.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.QueryMap

/**
 * The online-only CRM screens (CRM enhancements T3c). Unlike [SyncApi] these calls act as a person: they carry the
 * salesperson's own access token (`Authorization: Bearer`), obtained from `/auth/login`. Nothing here is stored in
 * Room or queued for sync — CRM on the till needs a connection.
 *
 * Responses stay raw [JsonObject]s and are read by `CrmParsers`, so a field the server adds later never breaks the app.
 */
interface CrmApi {

    @POST("auth/login")
    suspend fun login(@Body body: JsonObject): JsonObject

    @POST("auth/mfa-login")
    suspend fun mfaLogin(@Body body: JsonObject): JsonObject

    @POST("auth/refresh")
    suspend fun refresh(@Body body: JsonObject): JsonObject

    @GET("crm/planner")
    suspend fun planner(
        @Header("Authorization") auth: String,
        @QueryMap query: Map<String, String>,
    ): JsonObject

    @POST("crm/planner/{id}/done")
    suspend fun done(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Body body: JsonObject,
    ): JsonObject

    @GET("crm/leads/duplicates")
    suspend fun duplicates(
        @Header("Authorization") auth: String,
        @QueryMap query: Map<String, String>,
    ): JsonObject

    @POST("crm/leads")
    suspend fun createLead(@Header("Authorization") auth: String, @Body body: JsonObject): JsonObject

    @GET("crm/customers")
    suspend fun customers(
        @Header("Authorization") auth: String,
        @QueryMap query: Map<String, String>,
    ): JsonObject

    @GET("crm/customers/{id}")
    suspend fun customer(@Header("Authorization") auth: String, @Path("id") id: String): JsonObject
}
