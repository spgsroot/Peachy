package ru.spgsroot.peachy.translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.data.db.LibraryGalleryDao
import ru.spgsroot.peachy.data.db.PageTranslationDao
import ru.spgsroot.peachy.data.manga.MangaFiles
import ru.spgsroot.peachy.data.manga.MangaLibraryRepository
import ru.spgsroot.peachy.data.manga.NhentaiApi
import ru.spgsroot.peachy.manga.NhentaiCdn

@RunWith(AndroidJUnit4::class)
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class PageBytesDeviceTest {
    private inline fun <reified T> unusedDao(): T = Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { _,_,_ -> error("DAO must not be touched") } as T
    @Test fun savedThenCoilThenNetworkWithoutRedownload() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val files=MangaFiles(ctx)
        val library=MangaLibraryRepository(unusedDao<LibraryGalleryDao>(),NhentaiApi(),files,TranslationStore(unusedDao<PageTranslationDao>()))
        var requests=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(byteArrayOf(3).toResponseBody()).build()
        }.build()
        val path="eval-smoke/${System.nanoTime()}.webp"
        val url=NhentaiCdn.page(path)!!
        val disk=ctx.imageLoader.diskCache!!
        val editor=disk.openEditor(url)!!
        editor.data.toFile().writeBytes(byteArrayOf(2))
        editor.commit()
        val source=PageBytes(library,http,ctx).also { it.bind(listOf(path)) }
        val traces=mutableListOf<Map<String,Long>>()
        suspend fun load():ByteArray {
            val trace=PageTrace()
            val bytes=withContext(PageTraceElement(trace)) { source.raw(-999,0) }
            traces.add(trace.stageMs)
            return bytes
        }
        try {
            files.save(-999,path,byteArrayOf(1))
            assertArrayEquals(byteArrayOf(1),load())
            files.deleteGallery(-999)
            assertArrayEquals(byteArrayOf(2),load())
            disk.remove(url)
            assertArrayEquals(byteArrayOf(3),load())
            assertEquals(1,requests)
            assertEquals(listOf(setOf("fetch.local"),setOf("fetch.coil"),setOf("fetch.net")),traces.map { it.keys })
        } finally { files.deleteGallery(-999);disk.remove(url) }
    }
}
