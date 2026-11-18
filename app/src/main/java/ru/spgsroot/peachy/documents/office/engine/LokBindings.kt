package ru.spgsroot.peachy.documents.office.engine

import android.content.res.AssetManager
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/**
 * The one place that knows the upstream Java class shapes. The app must not copy the MPL-2.0 glue
 * files into our source tree, so every call goes through these confined bindings, resolved once and
 * verified by exact parameter/return types. If a single signature differs, [resolve] fails and the
 * bridge reports an unavailable engine instead of calling a half-matching method.
 *
 * Classes (pinned commit `861effc…`, `android/Bootstrap/src/org/libreoffice/kit/`):
 * `LibreOfficeKit` (init/putenv/handle), `Office` (load/destroy/password/error), `Document`
 * (everything a rendered, editable document needs) and `Document$MessageCallback`.
 */
internal class LokBindings private constructor(
    val officeCtor: Constructor<*>,
    val initializeNative: Method,
    val getHandle: Method,
    val putenv: Method,
    val documentLoad: Method,
    val officeGetError: Method,
    val officeDestroy: Method,
    val setDocumentPassword: Method,
    val documentDestroy: Method,
    val saveAs: Method,
    val paintTile: Method,
    val postKeyEvent: Method,
    val postMouseEvent: Method,
    val postUnoCommand: Method,
    val getCommandValues: Method,
    val setPart: Method,
    val getParts: Method,
    val getPartName: Method,
    val setPartMode: Method,
    val getPartPageRectangles: Method,
    val getDocumentWidth: Method,
    val getDocumentHeight: Method,
    val getDocumentType: Method,
    val initializeForRendering: Method,
    val setClientZoom: Method,
    val setDocumentMessageCallback: Method,
    private val messageCallback: Class<*>,
    private val loader: ClassLoader,
) {

    /** Forward payload-free signals, and inspect only explicit UNO result callbacks. */
    fun newDocumentCallback(onCommandResult: (String) -> Unit, onSignal: (Int) -> Unit): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "messageRetrieved" -> {
                    val signal = args?.firstOrNull() as? Int
                    if (signal == OfficeEngine.CALLBACK_UNO_COMMAND_RESULT) {
                        (args?.getOrNull(1) as? String)?.let(onCommandResult)
                    } else if (signal != null) {
                        onSignal(signal)
                    }
                    null
                }

                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "OfficeDocumentCallback"
                else -> null
            }
        }
        return Proxy.newProxyInstance(loader, arrayOf(messageCallback), handler)
    }

    fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? {
        try {
            return method.invoke(receiver, *args)
        } catch (failure: InvocationTargetException) {
            // Surface the engine/JNI failure itself; callers map it to a typed bridge failure.
            val cause = failure.targetException
            if (cause is Exception) throw cause
            if (cause is Error) throw cause
            throw failure
        }
    }

    companion object {
        private const val PACKAGE = "org.libreoffice.kit"

        private val KIT = "$PACKAGE.LibreOfficeKit"
        private val OFFICE = "$PACKAGE.Office"
        private val DOCUMENT = "$PACKAGE.Document"
        private val CALLBACK = "$PACKAGE.Document\$MessageCallback"

        /**
         * Cheap presence check. `initialize = false` keeps the `static {}` block from loading the
         * native libraries, so this cannot raise `UnsatisfiedLinkError` during a capability probe.
         */
        fun isBridgePresent(loader: ClassLoader): Boolean = try {
            Class.forName(KIT, false, loader)
            Class.forName(OFFICE, false, loader)
            Class.forName(DOCUMENT, false, loader)
            Class.forName(CALLBACK, false, loader)
            true
        } catch (missing: ClassNotFoundException) {
            false
        } catch (missing: NoClassDefFoundError) {
            false
        } catch (missing: LinkageError) {
            false
        }

        /** Fail-closed resolution of every member the bridge uses. */
        fun resolve(loader: ClassLoader): LokBindings {
            val kit = Class.forName(KIT, false, loader)
            val office = Class.forName(OFFICE, false, loader)
            val document = Class.forName(DOCUMENT, false, loader)
            val callback = Class.forName(CALLBACK, false, loader)

            val initializeNative = kit.getDeclaredMethod(
                "initializeNative",
                String::class.java,
                String::class.java,
                String::class.java,
                AssetManager::class.java,
            ).apply { isAccessible = true }

            return LokBindings(
                officeCtor = office.getConstructor(ByteBuffer::class.java),
                initializeNative = initializeNative.returnsBoolean(),
                getHandle = kit.static("getLibreOfficeKitHandle", ByteBuffer::class.java),
                putenv = kit.static("putenv", Void.TYPE, String::class.java),
                documentLoad = office.method("documentLoad", document, String::class.java),
                officeGetError = office.method("getError", String::class.java),
                officeDestroy = office.method("destroy", Void.TYPE),
                setDocumentPassword = office.method(
                    "setDocumentPassword", Void.TYPE, String::class.java, String::class.java,
                ),
                documentDestroy = document.method("destroy", Void.TYPE),
                saveAs = document.method(
                    "saveAs", Void.TYPE, String::class.java, String::class.java, String::class.java,
                ),
                paintTile = document.method(
                    "paintTile", Void.TYPE, ByteBuffer::class.java,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                ),
                postKeyEvent = document.method(
                    "postKeyEvent", Void.TYPE,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                ),
                postMouseEvent = document.method(
                    "postMouseEvent", Void.TYPE,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                ),
                postUnoCommand = document.method(
                    "postUnoCommand", Void.TYPE,
                    String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
                ),
                getCommandValues = document.method(
                    "getCommandValues", String::class.java, String::class.java,
                ),
                setPart = document.method("setPart", Void.TYPE, Int::class.javaPrimitiveType!!),
                getParts = document.method("getParts", Int::class.javaPrimitiveType!!),
                getPartName = document.method("getPartName", String::class.java, Int::class.javaPrimitiveType!!),
                setPartMode = document.method("setPartMode", Void.TYPE, Int::class.javaPrimitiveType!!),
                getPartPageRectangles = document.method("getPartPageRectangles", String::class.java),
                getDocumentWidth = document.method("getDocumentWidth", Long::class.javaPrimitiveType!!),
                getDocumentHeight = document.method("getDocumentHeight", Long::class.javaPrimitiveType!!),
                getDocumentType = document.method("getDocumentType", Int::class.javaPrimitiveType!!),
                initializeForRendering = document.method("initializeForRendering", Void.TYPE),
                setClientZoom = document.method(
                    "setClientZoom", Void.TYPE,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                ),
                setDocumentMessageCallback = document.method("setMessageCallback", Void.TYPE, callback),
                messageCallback = callback,
                loader = loader,
            )
        }

        private fun Class<*>.static(name: String, returns: Class<*>, vararg parameters: Class<*>): Method =
            getMethod(name, *parameters).also { method ->
                require(java.lang.reflect.Modifier.isStatic(method.modifiers)) { "expected static $name" }
                require(method.returnType == returns) { "unexpected return type for $name" }
            }

        private fun Class<*>.method(name: String, returns: Class<*>, vararg parameters: Class<*>): Method =
            getMethod(name, *parameters).also { method ->
                require(!java.lang.reflect.Modifier.isStatic(method.modifiers)) { "expected instance $name" }
                require(method.returnType == returns) { "unexpected return type for $name" }
            }

        private fun Method.returnsBoolean(): Method = also {
            require(returnType == Boolean::class.javaPrimitiveType) { "unexpected return type for $name" }
        }
    }
}
