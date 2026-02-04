package io.specmatic.stub

import java.io.Closeable
import java.util.concurrent.Callable

interface SpecmaticMockRunner  : Callable<Int>, Closeable