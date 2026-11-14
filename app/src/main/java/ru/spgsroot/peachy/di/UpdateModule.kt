package ru.spgsroot.peachy.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ru.spgsroot.peachy.data.update.GitHubReleaseClient

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    @Provides
    @Singleton
    fun provideGitHubReleaseClient(): GitHubReleaseClient = GitHubReleaseClient()
}
