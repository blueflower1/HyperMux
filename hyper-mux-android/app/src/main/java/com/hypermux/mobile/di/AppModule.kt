package com.hypermux.mobile.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hypermux.mobile.data.api.AuthInterceptor
import com.hypermux.mobile.data.api.ApiService
import com.hypermux.mobile.data.api.ServerUrlInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .serializeNulls()
        .create()

    @Provides
    @Singleton
    fun provideOkHttp(
        authInterceptor: AuthInterceptor,
        serverUrlInterceptor: ServerUrlInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(serverUrlInterceptor)
        .addInterceptor(authInterceptor)
        // 日志拦截器全实现编译（AppModule 按 BuildConfig.DEBUG 开关级别）
        .addInterceptor(loggingInterceptor)
        // play-url 换直链可能偏慢（OpenList 挂载源解析），读超时放宽到 30s（对齐 web 端）
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (com.hypermux.mobile.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            // 占位 baseUrl：实际地址由 ServerUrlInterceptor 按 DataStore 配置重写
            .baseUrl("http://localhost:8080/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)
}
