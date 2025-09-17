package io.specmatic.test

import java.io.File

interface SpecmaticAfterAllHook {
    fun onAfterAllTests(junitReportDir: File? = null)
}