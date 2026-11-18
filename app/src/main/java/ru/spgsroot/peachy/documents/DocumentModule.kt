package ru.spgsroot.peachy.documents

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentModule {
    @Binds
    @Singleton
    abstract fun bindDocumentSessions(impl: DocumentSessionsImpl): DocumentSessions
}
