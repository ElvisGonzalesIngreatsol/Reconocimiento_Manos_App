package com.ingreatsol.reconocimiento_manos_python

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface YoloApiService {
    @Multipart
    @POST("predict")
    fun enviarImagen(
        @Part file: MultipartBody.Part
    ): Call<ApiResponse>
}