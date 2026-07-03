package com.homelab.calendar

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {
    @GET("api/tasks")
    suspend fun getTasks(): Response<List<Task>>

    @POST("api/tasks")
    suspend fun addTask(@Body taskRequest: Map<String, String>): Response<Task>

    @DELETE("api/tasks/{id}")
    suspend fun deleteTask(@Path("id") taskId: Int): Response<Map<String, Any>>

    @POST("api/tasks/{id}/toggle")
    suspend fun toggleTask(@Path("id") taskId: Int): Response<Map<String, Any>>
}
