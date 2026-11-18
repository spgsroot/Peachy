package ru.spgsroot.peachy.perfqa

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.JUnitCore
import org.junit.runner.manipulation.Filter
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runner.Description
import ru.spgsroot.peachy.documents.image.ImageRasterExportTest
import ru.spgsroot.peachy.documents.image.ImageRasterFreeRotationTest
import ru.spgsroot.peachy.documents.image.ImageRasterJpegMemoryTest

/** Self-targeted: no app components, app data, shell permissions or network access. */
class QaRunner : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        InstrumentationRegistry.registerInstance(this, args)
        start()
    }
    override fun onStart() {
        val report = StringBuilder()
        fun line(s: String) { report.appendLine(s) }
        fun junit(clazz: Class<*>, method: String? = null) {
            val runner = BlockJUnit4ClassRunner(clazz)
            if (method != null) runner.filter(Filter.matchMethodDescription(Description.createTestDescription(clazz, method)))
            val result = JUnitCore().run(runner)
            check(result.wasSuccessful() && result.runCount > 0 && result.assumptionFailureCount == 0 && result.ignoreCount == 0) {
                result.failures.joinToString("\n") { it.trace }.ifBlank { "No actual tests ran" }
            }
            line("PASS ${clazz.simpleName} ${method.orEmpty()} tests=${result.runCount}")
        }
        try {
            check(targetContext.packageName == "ru.spgsroot.peachy.perfqa")
            line("pid=${Process.myPid()}")
            when (val scenario = args.getString("scenario")) {
                "combined" -> {
                    BrowserProbe.generations(targetContext); line("PASS concurrent generations/session/clear")
                    junit(ImageRasterExportTest::class.java); junit(ImageRasterFreeRotationTest::class.java)
                    BrowserProbe.measure(targetContext, ::line); DetectorProbe.measure(targetContext, ::line)
                }
                "browser" -> {
                    BrowserProbe.generations(targetContext); line("PASS concurrent generations/session/clear")
                    BrowserProbe.measure(targetContext, ::line)
                }
                "ocr" -> DetectorProbe.measure(targetContext, ::line)
                "legacyArray4096", "canvas4096" -> junit(ImageRasterJpegMemoryTest::class.java, scenario)
                else -> error("Unknown scenario: $scenario")
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (t: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "$report\nFAIL ${t.stackTraceToString()}") })
        }
    }
}
