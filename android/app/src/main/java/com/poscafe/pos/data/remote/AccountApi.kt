package com.poscafe.pos.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * The person's own ERP account (mobile app shell): the same `auth/` routes the web app signs in with, plus device
 * self-enrolment. Calls that act as the person carry `Authorization: Bearer`, which the server prefers over the
 * device token. Responses stay raw [JsonObject]s, read defensively by `AccountParsers`.
 */
interface AccountApi {

    @POST("auth/login")
    suspend fun login(@Body body: JsonObject): JsonObject

    @POST("auth/mfa-login")
    suspend fun mfaLogin(@Body body: JsonObject): JsonObject

    @POST("auth/refresh")
    suspend fun refresh(@Body body: JsonObject): JsonObject

    /** Admin sign-in enrols this phone (`organization:update`); the token in the answer is shown only this once. */
    @POST("sync/devices/register")
    suspend fun registerDevice(@Header("Authorization") auth: String, @Body body: JsonObject): JsonObject
}
